package com.daigan.record.backup

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 一条对象级迁移诊断：写明受影响对象与原因，绝不"降级继续"。 */
data class MigrationDiagnostic(
    val code: String,
    val objects: Map<String, String>,
    val reason: String,
)

/** 规范化结果：迁移后的数据集 + 可展示/可归档的报告。 */
data class NormalizeResult(
    val dataSet: BackupDataSet,
    val applied: List<String>,
    val counts: Map<String, Int>,
    val notes: List<String>,
    val legacy: Boolean,
)

/**
 * 把受支持的 legacy 输入规范化成 portable schema 15 语义。
 *
 * 与网页端 `src/backup/normalize/` 是同一套**确定性**规则的两个平台实现：
 * 1. `category-service-defaults`：模板/分类的建单默认值分层映射；
 * 2. `web-12-to-15-business` 的订单部分：由期次反推订单级托管模式；
 * 3. `android-count-period-backfill`：计次订单"恰一个期次"因而归属可证；
 * 4. `web-count-sessions-from-dates`：`C:<date>` → `N:<订单全局序号>`；
 * 5. 终态断言：没有残留 `C:`，每个轮次都有归属期次。
 *
 * 任何一步的诊断都会让整次恢复在**激活前**失败，当前数据集保持可用。
 */
object BackupNormalizer {

    private const val LEGACY_COUNT_PREFIX = "C:"
    private const val SESSION_PREFIX = "N:"
    private const val MAX_TARGET_SESSIONS = 3650
    private const val MAX_SESSION_INDEX = Int.MAX_VALUE

    fun normalize(dataSet: BackupDataSet, schemaVersion: Int): NormalizeResult {
        val legacy = schemaVersion < BackupTableCatalog.PORTABLE_SCHEMA_VERSION
        val tables = dataSet.tables.associateBy { it.table.name }
        val applied = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        val notes = ArrayList<String>()

        val mutable = MutableDataSet(tables)
        if (legacy) {
            mutable.inferItemTypes()
            applied += "attach-legacy-occurrence-keys:itemType"

            // HOSTING_ALL 形状必须在任何"按批次定位目标"之前归一；历史游戏日随上下文
            // 保留，供后面的批次明细链接修复使用（不要过早丢弃证据）。
            val batches = mutable.normalizeLegacyBatches()
            counts["hostingAllBatchesNormalized"] = batches.hostingAllNormalized

            val categoryReport = mutable.applyCategoryServiceDefaults()
            applied += "category-service-defaults"
            counts["categoriesMigrated"] = categoryReport.categories
            counts["templatesMigrated"] = categoryReport.templates
            notes += categoryReport.notes

            val derived = mutable.alignOrderHostingMode()
            applied += "web-12-to-15-business"
            counts["ordersHostingModeDerived"] = derived

            val filled = mutable.backfillCountPeriods()
            applied += "android-count-period-backfill"
            counts["periodsServiceCountFilled"] = filled

            val sessions = mutable.migrateCountSessionsFromDates()
            applied += "web-count-sessions-from-dates"
            counts["sessionsCreated"] = sessions.sessionsCreated
            counts["occurrencesRewritten"] = sessions.occurrencesRewritten
            counts["ordersMigrated"] = sessions.ordersMigrated

            // C:→N: 重写之后再补链：键会变，id 不会变，证据依然成立。
            val links = mutable.repairLegacyBatchLinks(batches)
            counts["batchLinksRecovered"] = links.linked
            counts["batchLinksWithoutTarget"] = links.withoutTarget
            notes += links.notes
        }

        // CD-01：只**审计**已撤销批次的目标，不改写任何 id。这一步对**所有**便携 schema
        // 生效：它不是版本迁移，而是同一 schema 的可表达性断言（本端该列是 RESTRICT 外键，
        // 悬空 id 无法插入）。原本为 null 的无目标历史被接受并计数；已知但悬空的 id 在这里
        // 就中止，绝不改写成 null、也绝不留给校验器或 SQLite 才发现。
        val undoneTargets = mutable.auditUndoneBatchTargets()
        counts["undoneTargetsWithoutReference"] = undoneTargets.nullTargetHistory

        assertNoLegacyCountKeys(mutable)
        return NormalizeResult(
            dataSet = mutable.toDataSet(),
            applied = applied,
            counts = counts,
            notes = notes,
            legacy = legacy,
        )
    }

    // ---------------------------------------------------------------- itemType

    /**
     * 历史文件缺 `itemType` 列时的确定性推断（schema 13 之前）。
     *
     * 有 `occurrenceId` → OCCURRENCE；否则看被引用的任务：**托管任务（有期次）必然对应
     * 打卡记录**，短期单任务才用 SHORT_TASK。绝不能因为引用缺失就一律判成 SHORT_TASK，
     * 那会把"缺失打卡引用"的唯一证据抹掉，让后面的补链永远无法完成。
     */
    private fun MutableDataSet.inferItemTypes() {
        val items = table("completion_batch_items") ?: return
        if (items.hadColumn("itemType")) return
        val tasks = table("task_definitions")
        val hostingTaskIds = tasks?.rows.orEmpty()
            .filter { tasks!!.str(it, "periodId") != null }
            .mapNotNull { tasks!!.str(it, "id") }
            .toSet()
        items.rows.forEach { row ->
            val hasOccurrence = items.str(row, "occurrenceId") != null
            val taskId = items.str(row, "taskDefinitionId")
            val isHostingTask = taskId != null && taskId in hostingTaskIds
            items.setStr(row, "itemType", if (hasOccurrence || isHostingTask) "OCCURRENCE" else "SHORT_TASK")
        }
    }

    // ------------------------------------------------- category service defaults

    private data class ServiceDefaults(
        val hostingMode: String?,
        val targetSessions: Int?,
        val serviceWeekdays: String?,
    )

    private data class CategoryReport(val categories: Int, val templates: Int, val notes: List<String>)

    private val DATE_RANGE = ServiceDefaults("DATE_RANGE", null, null)
    private val WEEKDAYS = ServiceDefaults("DATE_RANGE", null, "1,2,3,4,5")
    private val CLEARED = ServiceDefaults(null, null, null)

    private fun MutableDataSet.applyCategoryServiceDefaults(): CategoryReport {
        val templates = table("game_templates")
        val categories = table("template_categories")
        val diagnostics = ArrayList<MigrationDiagnostic>()
        val notes = ArrayList<String>()

        val templateHasColumn = templates?.hadColumn("serviceMode") == true
        val categoryHasColumn = categories?.hadColumn("serviceMode") == true
        val templateDefaults = HashMap<String, ServiceDefaults>()

        templates?.rows?.forEach { row ->
            val templateId = templates.str(row, "id").orEmpty()
            val resolved = if (templateHasColumn) {
                resolveLegacyMode(
                    templates.str(row, "serviceMode"),
                    "template",
                    mapOf("templateId" to templateId),
                    templates.int(row, "serviceCount"),
                    diagnostics,
                )
            } else {
                ServiceDefaults(
                    templates.str(row, "hostingMode"),
                    templates.int(row, "targetSessions"),
                    templates.str(row, "serviceWeekdays"),
                )
            }
            if (resolved != null) {
                templates.setStr(row, "hostingMode", resolved.hostingMode)
                templates.setInt(row, "targetSessions", resolved.targetSessions)
                templates.setStr(row, "serviceWeekdays", resolved.serviceWeekdays)
                templateDefaults[templateId] = resolved
            }
        }

        var migratedCategories = 0
        categories?.rows?.forEach { row ->
            val categoryId = categories.str(row, "id").orEmpty()
            val templateId = categories.str(row, "templateId").orEmpty()
            val objects = mapOf("categoryId" to categoryId, "templateId" to templateId)
            val resolved = if (categoryHasColumn) {
                resolveLegacyMode(
                    categories.str(row, "serviceMode"),
                    "category",
                    objects,
                    categories.int(row, "serviceCount"),
                    diagnostics,
                )
            } else {
                // 该分类表早于扩展：历史读取口径就是用模板值，因此把模板值固化到分类行。
                templateDefaults[templateId]?.also {
                    notes += "category $categoryId 按模板 $templateId 的默认值固化（历史缺列）"
                } ?: CLEARED
            }
            if (resolved != null) {
                categories.setStr(row, "hostingMode", resolved.hostingMode)
                categories.setInt(row, "targetSessions", resolved.targetSessions)
                categories.setStr(row, "serviceWeekdays", resolved.serviceWeekdays)
            }
            migratedCategories += 1
        }

        if (diagnostics.isNotEmpty()) failAll(diagnostics, "模板/分类的建单默认值无法安全迁移")

        // 模板行 = 分类共识镜像：一致才写，分歧写 null（永不被当作覆盖）。
        var migratedTemplates = 0
        templates?.rows?.forEach { row ->
            val templateId = templates.str(row, "id").orEmpty()
            val own = categories?.rows?.filter { categories.str(it, "templateId") == templateId }.orEmpty()
            if (own.isEmpty()) return@forEach
            val keys = own.map { category ->
                "${categories!!.str(category, "hostingMode")}|" +
                    "${categories.int(category, "targetSessions")}|" +
                    "${categories.str(category, "serviceWeekdays")}"
            }.toSet()
            val before = "${templates.str(row, "hostingMode")}|" +
                "${templates.int(row, "targetSessions")}|" +
                "${templates.str(row, "serviceWeekdays")}"
            if (keys.size == 1) {
                val first = own.first()
                templates.setStr(row, "hostingMode", categories!!.str(first, "hostingMode"))
                templates.setInt(row, "targetSessions", categories.int(first, "targetSessions"))
                templates.setStr(row, "serviceWeekdays", categories.str(first, "serviceWeekdays"))
            } else {
                templates.setStr(row, "hostingMode", null)
                templates.setInt(row, "targetSessions", null)
                templates.setStr(row, "serviceWeekdays", null)
            }
            val after = "${templates.str(row, "hostingMode")}|" +
                "${templates.int(row, "targetSessions")}|" +
                "${templates.str(row, "serviceWeekdays")}"
            if (after != before) migratedTemplates += 1
        }

        // 退役列不再参与写出（encode 已过滤），明确置空以免被后续步骤误读。
        templates?.rows?.forEach { row ->
            templates.setStr(row, "serviceMode", null)
            templates.setInt(row, "serviceCount", null)
        }
        categories?.rows?.forEach { row ->
            categories.setStr(row, "serviceMode", null)
            categories.setInt(row, "serviceCount", null)
        }

        return CategoryReport(migratedCategories, migratedTemplates, notes)
    }

    private fun resolveLegacyMode(
        mode: String?,
        level: String,
        objects: Map<String, String>,
        count: Int?,
        diagnostics: MutableList<MigrationDiagnostic>,
    ): ServiceDefaults? = when (mode) {
        null -> if (level == "template") CLEARED else DATE_RANGE
        "dates" -> DATE_RANGE
        "weekdays" -> WEEKDAYS
        "count" -> {
            if (count == null || count < 1 || count > MAX_TARGET_SESSIONS) {
                diagnostics += MigrationDiagnostic(
                    "COUNT_TARGET_MISSING",
                    objects + ("serviceCount" to count?.toString().orEmpty()),
                    "count 模式的服务次数必须在 1–$MAX_TARGET_SESSIONS，绝不静默降级为 dates",
                )
                null
            } else {
                ServiceDefaults("COUNT_BASED", count, null)
            }
        }

        else -> {
            diagnostics += MigrationDiagnostic(
                "UNSUPPORTED_LEGACY_COLUMN_VALUE",
                objects + ("serviceMode" to mode),
                "未知的服务模式",
            )
            null
        }
    }

    // ------------------------------------------------------ order hosting mode

    /**
     * 由期次反推订单级托管模式（`web-12-to-15-business` 的 orders 部分）。
     *
     * 只有 `hostingMode` 为 NULL 的订单才推导；已声明计次的订单允许多期次（网页语义），
     * 期次缺少自身目标的情况由 [backfillCountPeriods] 单独诊断。
     */
    private fun MutableDataSet.alignOrderHostingMode(): Int {
        val orders = table("orders") ?: return 0
        val periods = table("hosting_periods") ?: return 0
        val sessions = table("hosting_sessions")
        val diagnostics = ArrayList<MigrationDiagnostic>()
        var changed = 0

        orders.rows.forEach { order ->
            val orderId = orders.str(order, "id").orEmpty()
            val type = orders.str(order, "type")
            val own = periods.rows.filter { periods.str(it, "orderId") == orderId }
            val countPeriods = own.filter { periods.int(it, "serviceCount") != null }
            val sessionCount = sessions?.rows?.count { sessions.str(it, "orderId") == orderId } ?: 0
            val mode = orders.str(order, "hostingMode")

            if (type != "HOSTING") {
                if (mode != null) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("orderId" to orderId),
                        "非托管订单不得声明托管模式",
                    )
                }
                if (sessionCount > 0) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("orderId" to orderId),
                        "非托管订单不得包含打卡轮次",
                    )
                }
                return@forEach
            }
            if (mode == "DATE_RANGE") {
                if (countPeriods.isNotEmpty()) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("orderId" to orderId),
                        "按日期订单不得包含计次期次",
                    )
                }
                if (sessionCount > 0) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("orderId" to orderId),
                        "按日期订单不得包含打卡轮次",
                    )
                }
                return@forEach
            }
            if (mode != null) return@forEach
            if (countPeriods.isEmpty()) return@forEach
            if (countPeriods.size != own.size) {
                diagnostics += MigrationDiagnostic(
                    "OWNERSHIP_INCONSISTENT",
                    mapOf("orderId" to orderId),
                    "同一订单混合计次期次与按日期期次，没有安全的托管模式",
                )
                return@forEach
            }
            val highest = countPeriods.maxWithOrNull(
                compareBy({ periods.int(it, "periodIndex") ?: 0 }, { periods.str(it, "id").orEmpty() }),
            ) ?: return@forEach
            orders.setStr(order, "hostingMode", "COUNT_BASED")
            orders.setInt(order, "targetSessions", periods.int(highest, "serviceCount"))
            changed += 1
        }

        orders.rows.forEach { order ->
            if (orders.str(order, "hostingMode") != "COUNT_BASED") {
                orders.setInt(order, "targetSessions", null)
            }
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "订单托管模式无法安全推导")
        return changed
    }

    /**
     * Android 13/14 计次归属回填（`android-count-period-backfill`）。
     *
     * 有效计次订单恰好一个期次 → 归属可证；期次目标来自 `orders.targetSessions`。
     * 歧义即中止，绝不合并或挑选。
     */
    private fun MutableDataSet.backfillCountPeriods(): Int {
        val orders = table("orders") ?: return 0
        val periods = table("hosting_periods") ?: return 0
        val sessions = table("hosting_sessions")
        val diagnostics = ArrayList<MigrationDiagnostic>()
        var filled = 0

        orders.rows.forEach { order ->
            if (orders.str(order, "hostingMode") != "COUNT_BASED") return@forEach
            val orderId = orders.str(order, "id").orEmpty()
            val own = periods.rows.filter { periods.str(it, "orderId") == orderId }
            val ownSessions = sessions?.rows?.filter { sessions.str(it, "orderId") == orderId }.orEmpty()
            val needsBackfill = ownSessions.any { sessions!!.str(it, "periodId") == null } ||
                own.any { periods.int(it, "serviceCount") == null }
            if (!needsBackfill) return@forEach
            if (own.size != 1) {
                diagnostics += MigrationDiagnostic(
                    "COUNT_PERIOD_AMBIGUOUS",
                    mapOf(
                        "orderId" to orderId,
                        "periodCount" to own.size.toString(),
                        "candidatePeriodIds" to own.mapNotNull { periods.str(it, "id") }.joinToString(),
                        "sessionIds" to ownSessions.mapNotNull { sessions?.str(it, "id") }.joinToString(),
                    ),
                    "计次订单期望恰好一个期次；无法证明归属时不合并、不挑选",
                )
                return@forEach
            }
            val period = own.first()
            if (periods.int(period, "serviceCount") == null) {
                val target = orders.int(order, "targetSessions")
                if (target == null || target < 1 || target > MAX_TARGET_SESSIONS) {
                    diagnostics += MigrationDiagnostic(
                        "COUNT_TARGET_MISSING",
                        mapOf("orderId" to orderId, "periodId" to periods.str(period, "id").orEmpty()),
                        "计次订单缺少有效目标次数，期次目标无法回填",
                    )
                    return@forEach
                }
                periods.setInt(period, "serviceCount", target)
                filled += 1
            }
            val periodId = periods.str(period, "id").orEmpty()
            ownSessions.forEach { sessions?.setStr(it, "periodId", periodId) }
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "打卡轮次归属无法安全回填")
        return filled
    }

    // --------------------------------------------------------- C: → N: 迁移

    private data class MigrationSummary(
        val sessionsCreated: Int,
        val occurrencesRewritten: Int,
        val ordersMigrated: Int,
    )

    private data class Group(
        val orderId: String,
        val periodId: String,
        val periodIndex: Int,
        val periodCreatedAt: Long,
        val periodRules: GameRefreshRules,
        val gameDate: String,
        val occurrences: MutableList<MutableList<JsonElement?>>,
    )

    /**
     * 网页 `C:<gameDate>` → `N:<订单全局序号>`。
     *
     * 分组键 `(orderId, periodId, gameDate)`；每组只建一行 `hosting_sessions`、只登记
     * 一次生成的 id；组内所有 occurrence 共用同一个 `N:` 号；每个订单独立计数；
     * 同一游戏日跨期次/跨订单都合法。
     */
    private fun MutableDataSet.migrateCountSessionsFromDates(): MigrationSummary {
        val orders = table("orders") ?: return MigrationSummary(0, 0, 0)
        val periods = table("hosting_periods") ?: return MigrationSummary(0, 0, 0)
        val tasks = table("task_definitions") ?: return MigrationSummary(0, 0, 0)
        val occurrences = table("task_occurrences") ?: return MigrationSummary(0, 0, 0)
        val sessions = table("hosting_sessions") ?: return MigrationSummary(0, 0, 0)
        val diagnostics = ArrayList<MigrationDiagnostic>()

        val taskById = tasks.rows.associateBy { tasks.str(it, "id").orEmpty() }
        val periodById = periods.rows.associateBy { periods.str(it, "id").orEmpty() }
        val existingSessionIds = sessions.rows.mapNotNull { sessions.str(it, "id") }.toHashSet()

        val affectedOrders = LinkedHashSet<String>()
        periods.rows.forEach { period ->
            if (periods.int(period, "serviceCount") != null) {
                periods.str(period, "orderId")?.let(affectedOrders::add)
            }
        }

        // 归属解析 + 混合输入拒绝
        val legacyByOrder = HashMap<String, MutableList<MutableList<JsonElement?>>>()
        val modernByOrder = HashMap<String, MutableList<MutableList<JsonElement?>>>()
        val seenOccurrenceIds = HashSet<String>()
        occurrences.rows.forEach { occurrence ->
            val occurrenceId = occurrences.str(occurrence, "id").orEmpty()
            if (!seenOccurrenceIds.add(occurrenceId)) {
                diagnostics += MigrationDiagnostic(
                    "SOURCE_RECORD_IN_MULTIPLE_GROUPS",
                    mapOf("occurrenceId" to occurrenceId),
                    "同一条源记录出现多次，不能归入多个分组",
                )
                return@forEach
            }
            val taskId = occurrences.str(occurrence, "taskDefinitionId").orEmpty()
            val task = taskById[taskId] ?: run {
                diagnostics += MigrationDiagnostic(
                    "OWNERSHIP_INCONSISTENT",
                    mapOf("occurrenceId" to occurrenceId),
                    "打卡记录引用了不存在的任务",
                )
                return@forEach
            }
            val orderId = tasks.str(task, "orderId").orEmpty()
            val periodId = tasks.str(task, "periodId")
            val period = periodId?.let(periodById::get)
            if (period == null || periods.str(period, "orderId") != orderId) {
                diagnostics += MigrationDiagnostic(
                    "OWNERSHIP_INCONSISTENT",
                    mapOf("occurrenceId" to occurrenceId, "taskId" to taskId),
                    "打卡记录的任务/期次/订单归属不一致",
                )
                return@forEach
            }
            val key = occurrences.str(occurrence, "occurrenceKey").orEmpty()
            if (key.startsWith(LEGACY_COUNT_PREFIX)) {
                if (periods.int(period, "serviceCount") == null) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("occurrenceId" to occurrenceId, "periodId" to periodId.orEmpty()),
                        "C: 打卡键出现在非计次期次，无法映射为轮次",
                    )
                    return@forEach
                }
                legacyByOrder.getOrPut(orderId) { ArrayList() }.add(occurrence)
            } else {
                modernByOrder.getOrPut(orderId) { ArrayList() }.add(occurrence)
            }
        }

        affectedOrders.forEach { orderId ->
            val legacy = legacyByOrder[orderId].orEmpty()
            val modern = modernByOrder[orderId].orEmpty()
            if (legacy.isNotEmpty() && modern.isNotEmpty()) {
                diagnostics += MigrationDiagnostic(
                    "UNSUPPORTED_MIXED_C_N_INPUT",
                    mapOf("orderId" to orderId),
                    "同一订单混合 C: 与 N: 打卡键，没有安全映射",
                )
            }
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "打卡轮次无法安全迁移")

        var sessionsCreated = 0
        var occurrencesRewritten = 0
        var ordersMigrated = 0
        val generatedIdentities = HashSet<String>()

        affectedOrders.sorted().forEach { orderId ->
            val orderPeriods = periods.rows
                .filter { periods.str(it, "orderId") == orderId && periods.int(it, "serviceCount") != null }
                .sortedWith(
                    compareBy({ periods.int(it, "periodIndex") ?: 0 }, { periods.str(it, "id").orEmpty() }),
                )
            val legacyOccurrences = legacyByOrder[orderId].orEmpty()
            val orderTasks = tasks.rows.filter { tasks.str(it, "orderId") == orderId }

            val groups = ArrayList<Group>()
            orderPeriods.forEach { period ->
                val periodId = periods.str(period, "id").orEmpty()
                val rules = refreshRulesOf(periods, period)
                val periodCreatedAt = periods.long(period, "createdAt") ?: 0L
                val inPeriod = legacyOccurrences.filter { occurrence ->
                    val task = taskById[occurrences.str(occurrence, "taskDefinitionId").orEmpty()]
                    tasks.str(task ?: return@filter false, "periodId") == periodId
                }
                inPeriod.groupBy { occurrences.str(it, "gameDate").orEmpty() }
                    .toSortedMap()
                    .forEach { (gameDate, rows) ->
                        groups += Group(
                            orderId = orderId,
                            periodId = periodId,
                            periodIndex = periods.int(period, "periodIndex") ?: 0,
                            periodCreatedAt = periodCreatedAt,
                            periodRules = rules,
                            gameDate = gameDate,
                            occurrences = rows.toMutableList(),
                        )
                    }
            }
            if (groups.isEmpty()) return@forEach
            ordersMigrated += 1

            groups.forEachIndexed { index, group ->
                val sessionIndex = index + 1
                val sessionId = "mig-session-$orderId-$sessionIndex"
                val identity = "$orderId#$sessionIndex"
                if (sessionId in existingSessionIds || !generatedIdentities.add(identity)) {
                    diagnostics += MigrationDiagnostic(
                        "GENERATED_ID_OR_INDEX_COLLISION",
                        mapOf("orderId" to orderId, "sessionId" to sessionId),
                        "生成的轮次 id 或身份与既有记录/其他分组冲突",
                    )
                    return@forEachIndexed
                }
                existingSessionIds += sessionId

                val completion = reconstructCompletion(
                    orderTasks = orderTasks,
                    tasks = tasks,
                    occurrences = occurrences,
                    group = group,
                )

                val createdAt = group.occurrences.minOfOrNull { occurrences.long(it, "createdAt") ?: Long.MAX_VALUE }
                    ?.takeIf { it != Long.MAX_VALUE }
                    ?: group.periodCreatedAt
                sessions.addRow(
                    buildMap {
                        put("id", JsonPrimitive(sessionId))
                        put("orderId", JsonPrimitive(orderId))
                        put("sessionIndex", JsonPrimitive(sessionIndex))
                        put("createdAt", JsonPrimitive(createdAt))
                        put("completedAt", completion.completedAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("completionBatchId", completion.batchId?.let(::JsonPrimitive) ?: JsonNull)
                        put("periodId", JsonPrimitive(group.periodId))
                    },
                )
                sessionsCreated += 1

                group.occurrences.forEach { occurrence ->
                    occurrences.setStr(occurrence, "occurrenceKey", "$SESSION_PREFIX$sessionIndex")
                    occurrencesRewritten += 1
                }
            }
        }

        if (diagnostics.isNotEmpty()) failAll(diagnostics, "打卡轮次无法安全迁移")
        return MigrationSummary(sessionsCreated, occurrencesRewritten, ordersMigrated)
    }

    private data class Completion(val completedAt: Long?, val batchId: String?)

    /**
     * 按**历史应做集**重建完成度（G3 / D-40）。
     *
     * 应做集为空 → 不完成；有应做任务缺少 occurrence 行 → 不完成；全部有行且都完成 →
     * 取这些完成时刻中最晚的一个；否则不完成。绝不从数值总量、今日名册或订单目标推断。
     */
    private fun reconstructCompletion(
        orderTasks: List<MutableList<JsonElement?>>,
        tasks: MutableTable,
        occurrences: MutableTable,
        group: Group,
    ): Completion {
        val due = orderTasks.filter { task ->
            tasks.str(task, "periodId") == group.periodId &&
                tasks.str(task, "recurrence") != "ONE_TIME" &&
                isDueOn(tasks, task, group)
        }
        if (due.isEmpty()) return Completion(null, null)
        val byTask = HashMap<String, MutableList<JsonElement?>>()
        group.occurrences.forEach { occurrence ->
            val taskId = occurrences.str(occurrence, "taskDefinitionId").orEmpty()
            if (taskId.isNotEmpty()) byTask[taskId] = occurrence
        }
        val completed = ArrayList<MutableList<JsonElement?>>()
        due.forEach { task ->
            val taskId = tasks.str(task, "id").orEmpty()
            val occurrence = byTask[taskId] ?: return Completion(null, null)
            if (occurrences.long(occurrence, "completedAt") == null) return Completion(null, null)
            completed += occurrence
        }
        val completedAt = completed.mapNotNull { occurrences.long(it, "completedAt") }.max()
        val batches = completed.map { occurrences.str(it, "completionBatchId") }.toSet()
        val batchId = if (batches.size == 1) batches.first() else null
        return Completion(completedAt, batchId)
    }

    private fun isDueOn(tasks: MutableTable, task: MutableList<JsonElement?>, group: Group): Boolean {
        val gameDate = runCatching { LocalDate.parse(group.gameDate) }.getOrNull() ?: return false
        tasks.str(task, "activeFrom")?.let { from ->
            val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return false
            if (gameDate < start) return false
        }
        tasks.str(task, "activeUntil")?.let { until ->
            val end = runCatching { LocalDate.parse(until) }.getOrNull() ?: return false
            if (gameDate > end) return false
        }
        tasks.long(task, "retiredAt")?.let { retiredAt ->
            val retireDate = GameCalendar.gameDate(Instant.ofEpochMilli(retiredAt), group.periodRules)
            if (gameDate > retireDate) return false
        }
        return true
    }

    private fun refreshRulesOf(periods: MutableTable, period: MutableList<JsonElement?>): GameRefreshRules {
        val zone = runCatching { ZoneId.of(periods.str(period, "gameZoneId").orEmpty()) }
            .getOrDefault(ZoneId.systemDefault())
        val daily = periods.int(period, "dailyRefreshMinutes") ?: 0
        val weeklyDay = periods.int(period, "weeklyRefreshDay") ?: 1
        val weeklyMinutes = periods.int(period, "weeklyRefreshMinutes") ?: 0
        val monthlyDays = com.daigan.record.data.model.parseMonthlyDates(
            periods.str(period, "monthlyRefreshDays"),
        ) ?: emptySet()
        return GameRefreshRules(
            zoneId = zone,
            dailyRefresh = LocalTime.of(daily / 60, daily % 60),
            weeklyRefreshDay = DayOfWeek.of(weeklyDay.coerceIn(1, 7)),
            weeklyRefreshTime = LocalTime.of(weeklyMinutes / 60, weeklyMinutes % 60),
            monthlyRefreshDays = monthlyDays,
        )
    }

    // ------------------------------------------------- 历史批次归一与补链

    /**
     * 历史批次的两处确定性归一（S2-R4 / S2-R5）。
     *
     * 1. **HOSTING_ALL 形状**（D-21）：网页历史写入把 `targetId` 写成字面量 `'all'`、
     *    把当天游戏日写进 `gameDate`；契约要求两者都是 null。
     * 2. **批次明细 → 打卡记录的引用**：历史补建的 occurrence 没有回写
     *    `item.occurrenceId`（B-04），只在**证据唯一**时补链。
     *
     * 两者都只在便携 schema < 15 的历史输入上执行；schema-15 输入按原样进入严格校验。
     */
    private data class BatchContext(
        val legacyGameDateByBatch: Map<String, String>,
        val hostingAllNormalized: Int,
    )

    private fun MutableDataSet.normalizeLegacyBatches(): BatchContext {
        val batches = table("completion_batches") ?: return BatchContext(emptyMap(), 0)
        val legacyDates = LinkedHashMap<String, String>()
        val diagnostics = ArrayList<MigrationDiagnostic>()
        var normalized = 0
        batches.rows.forEach { batch ->
            val batchId = batches.str(batch, "id").orEmpty()
            batches.str(batch, "gameDate")?.let { legacyDates[batchId] = it }
            if (batches.str(batch, "scope") != "HOSTING_ALL") return@forEach
            val targetId = batches.str(batch, "targetId")
            if (targetId != null && targetId != "all") {
                diagnostics += MigrationDiagnostic(
                    "UNSUPPORTED_LEGACY_BATCH_SHAPE",
                    mapOf("batchId" to batchId, "targetId" to targetId),
                    "HOSTING_ALL 批次的目标只允许 null 或历史哨兵值 \"all\"",
                )
                return@forEach
            }
            val gameDate = batches.str(batch, "gameDate")
            if (gameDate != null && !isIsoDate(gameDate)) {
                diagnostics += MigrationDiagnostic(
                    "UNSUPPORTED_LEGACY_BATCH_SHAPE",
                    mapOf("batchId" to batchId, "gameDate" to gameDate),
                    "HOSTING_ALL 批次的游戏日必须是合法 ISO 日期或 null",
                )
                return@forEach
            }
            val target = batches.str(batch, "targetId")
            if (target != null || gameDate != null) normalized += 1
            batches.setStr(batch, "targetId", null)
            batches.setStr(batch, "gameDate", null)
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "历史批量完成记录无法安全归一")
        return BatchContext(legacyDates, normalized)
    }

    private data class LinkRepair(
        val linked: Int,
        val withoutTarget: Int,
        val notes: List<String>,
    )

    private fun MutableDataSet.repairLegacyBatchLinks(context: BatchContext): LinkRepair {
        val items = table("completion_batch_items") ?: return LinkRepair(0, 0, emptyList())
        val batches = table("completion_batches") ?: return LinkRepair(0, 0, emptyList())
        val occurrences = table("task_occurrences") ?: return LinkRepair(0, 0, emptyList())
        val tasks = table("task_definitions") ?: return LinkRepair(0, 0, emptyList())
        val diagnostics = ArrayList<MigrationDiagnostic>()
        val notes = ArrayList<String>()
        val batchById = batches.rows.associateBy { batches.str(it, "id").orEmpty() }
        var linked = 0
        var withoutTarget = 0

        items.rows.forEach { item ->
            if (items.str(item, "itemType") != "OCCURRENCE") return@forEach
            if (items.str(item, "occurrenceId") != null) return@forEach
            val itemId = items.str(item, "id").orEmpty()
            val batchId = items.str(item, "batchId").orEmpty()
            val taskId = items.str(item, "taskDefinitionId")
            if (taskId == null) {
                diagnostics += MigrationDiagnostic(
                    "LEGACY_BATCH_LINK_UNRESOLVED",
                    mapOf("itemId" to itemId, "batchId" to batchId),
                    "批次明细既没有打卡记录引用也没有任务引用，无法定位目标",
                )
                return@forEach
            }
            val batch = batchById[batchId]
            if (batch == null) {
                diagnostics += MigrationDiagnostic(
                    "LEGACY_BATCH_LINK_UNRESOLVED",
                    mapOf("itemId" to itemId, "batchId" to batchId),
                    "批次明细引用了不存在的批次",
                )
                return@forEach
            }
            // HOSTING_ALL 横跨不同时区，其单一历史游戏日不能用于收窄候选。
            val batchDate = if (batches.str(batch, "scope") == "HOSTING_ALL") {
                null
            } else {
                context.legacyGameDateByBatch[batchId]
            }
            val sameTask = occurrences.rows.filter { occurrences.str(it, "taskDefinitionId") == taskId }
            fun matchesDate(row: MutableList<JsonElement?>): Boolean {
                if (batchDate == null) return true
                if (occurrences.str(row, "gameDate") == batchDate) return true
                val start = occurrences.str(row, "windowStart") ?: return false
                val end = occurrences.str(row, "windowEnd") ?: return false
                return start <= batchDate && batchDate <= end
            }
            var candidates = sameTask.filter {
                occurrences.str(it, "completionBatchId") == batchId && matchesDate(it)
            }
            if (candidates.isEmpty()) {
                val appliedAt = items.long(item, "appliedCompletedAt")
                val appliedValue = items.str(item, "appliedValue")
                if (appliedAt != null) {
                    candidates = sameTask.filter { row ->
                        occurrences.long(row, "completedAt") == appliedAt &&
                            (appliedValue == null || occurrences.str(row, "value") == appliedValue) &&
                            matchesDate(row)
                    }
                }
            }
            when {
                candidates.size == 1 -> {
                    items.setStr(item, "occurrenceId", occurrences.str(candidates.first(), "id"))
                    linked += 1
                }

                candidates.size > 1 -> diagnostics += MigrationDiagnostic(
                    "LEGACY_BATCH_LINK_AMBIGUOUS",
                    mapOf(
                        "itemId" to itemId,
                        "batchId" to batchId,
                        "taskDefinitionId" to taskId,
                        "candidateIds" to candidates
                            .mapNotNull { occurrences.str(it, "id") }
                            .joinToString(),
                    ),
                    "存在 ${candidates.size} 条可辩护的打卡记录，无法唯一确定批次明细的目标",
                )

                else -> {
                    // 已撤销的历史批次可能把自己的 occurrence 删掉了：历史格式无法表达，
                    // 保留历史行，绝不臆造一条打卡记录来满足引用。
                    if (batches.long(batch, "undoneAt") != null) {
                        withoutTarget += 1
                        notes += "已撤销批次 $batchId 的明细 $itemId 目标记录已不存在，保留历史（不补造）"
                    } else {
                        diagnostics += MigrationDiagnostic(
                            "LEGACY_BATCH_LINK_UNRESOLVED",
                            mapOf("itemId" to itemId, "batchId" to batchId, "taskDefinitionId" to taskId),
                            "没有任何打卡记录能证明该明细的目标",
                        )
                    }
                }
            }
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "历史批量完成的打卡引用无法安全修复")
        return LinkRepair(linked, withoutTarget, notes)
    }

    private data class UndoneTargetAudit(val nullTargetHistory: Int)

    /**
     * CD-01：已撤销批次的"无目标历史"是唯一可表达的历史形态（与网页端同一规则）。
     *
     * `completion_batch_items.occurrenceId` 允许为 null，**只**在所属批次已撤销
     * （`undoneAt` 非空）且明细带有效任务引用时成立。支持 / 拒绝边界逐条一致：
     * - 已撤销 + `occurrenceId` 原本为 null + 任务引用 → **接受**，只计数，不改写、不臆造；
     * - 已撤销 + 非空 id 且记录存在 → 原样通过（目标身份与前后快照全部保留）；
     * - 已撤销 + 非空 id 但记录不存在 → **拒绝**（`UNDONE_BATCH_TARGET_DANGLING`，激活前、
     *   对象级诊断，原 id 同时留在诊断与输入里）。绝不写 null、绝不猜替代目标、绝不臆造
     *   occurrence、绝不删明细：已知 id 是历史事实，把它丢进临时报告并不等于无损；
     * - 未撤销 + 悬空 id → 继续按严格规则拒绝（`BATCH_TARGET_DANGLING`）。
     *
     * 该步骤对**所有**便携 schema 生效：它不是版本迁移，而是同一 schema 的可表达性断言，
     * 幂等（原本为 null 的仍是 null）。
     */
    private fun MutableDataSet.auditUndoneBatchTargets(): UndoneTargetAudit {
        val items = table("completion_batch_items") ?: return UndoneTargetAudit(0)
        val batches = table("completion_batches") ?: return UndoneTargetAudit(0)
        val occurrences = table("task_occurrences") ?: return UndoneTargetAudit(0)
        val batchById = batches.rows.associateBy { batches.str(it, "id").orEmpty() }
        val occurrenceIds = occurrences.rows.mapNotNull { occurrences.str(it, "id") }.toSet()
        val diagnostics = ArrayList<MigrationDiagnostic>()
        var nullTargetHistory = 0

        items.rows.forEach { item ->
            if (items.str(item, "itemType") != "OCCURRENCE") return@forEach
            val itemId = items.str(item, "id").orEmpty()
            val batchId = items.str(item, "batchId").orEmpty()
            // 缺少批次由严格校验拒绝，这里不猜。
            val batch = batchById[batchId] ?: return@forEach
            val occurrenceId = items.str(item, "occurrenceId")
            val taskId = items.str(item, "taskDefinitionId")
            if (occurrenceId == null) {
                // 只有"已撤销 + 任务引用"才是受支持的无目标历史；任务是否存在由校验器判定。
                if (batches.long(batch, "undoneAt") != null && taskId != null) nullTargetHistory += 1
                return@forEach
            }
            if (occurrenceId in occurrenceIds) return@forEach
            if (batches.long(batch, "undoneAt") == null) {
                diagnostics += MigrationDiagnostic(
                    "BATCH_TARGET_DANGLING",
                    mapOf(
                        "itemId" to itemId,
                        "batchId" to batchId,
                        "occurrenceId" to occurrenceId,
                        "taskDefinitionId" to (taskId ?: ""),
                    ),
                    "未撤销批次的明细引用了不存在的打卡记录；悬空引用无法在两端持久化",
                )
                return@forEach
            }
            // 已知 id 但记录已消失：这一类历史没有无损的持久化表示（本端是 RESTRICT 外键），
            // 因此明确拒绝，而不是把原 id 换成 null。
            diagnostics += MigrationDiagnostic(
                "UNDONE_BATCH_TARGET_DANGLING",
                mapOf(
                    "itemId" to itemId,
                    "batchId" to batchId,
                    "originalOccurrenceId" to occurrenceId,
                    "taskDefinitionId" to (taskId ?: ""),
                ),
                "已撤销批次 $batchId 的明细 $itemId 引用了已不存在的打卡记录 $occurrenceId；" +
                    "原目标 id 无法在两端持久化，整份归档被拒绝（不改写为无目标历史）",
            )
        }

        if (diagnostics.isNotEmpty()) failAll(diagnostics, "批量完成的历史目标无法安全表示")
        return UndoneTargetAudit(nullTargetHistory)
    }

    private fun isIsoDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess

    // ------------------------------------------------------------- 终态断言

    private fun assertNoLegacyCountKeys(dataSet: MutableDataSet) {
        val diagnostics = ArrayList<MigrationDiagnostic>()
        dataSet.table("task_occurrences")?.let { occurrences ->
            occurrences.rows.forEach { row ->
                val key = occurrences.str(row, "occurrenceKey").orEmpty()
                if (key.startsWith(LEGACY_COUNT_PREFIX)) {
                    diagnostics += MigrationDiagnostic(
                        "UNSUPPORTED_MIXED_C_N_INPUT",
                        mapOf("occurrenceId" to occurrences.str(row, "id").orEmpty()),
                        "schema 15 不允许 C: 打卡键",
                    )
                }
                if (key.startsWith(SESSION_PREFIX) &&
                    (key.removePrefix(SESSION_PREFIX).toIntOrNull() ?: 0) < 1
                ) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("occurrenceId" to occurrences.str(row, "id").orEmpty()),
                        "N: 打卡键必须携带 1 起的整数轮次号",
                    )
                }
            }
        }
        dataSet.table("hosting_sessions")?.let { sessions ->
            sessions.rows.forEach { row ->
                if (sessions.str(row, "periodId").isNullOrEmpty()) {
                    diagnostics += MigrationDiagnostic(
                        "OWNERSHIP_INCONSISTENT",
                        mapOf("sessionId" to sessions.str(row, "id").orEmpty()),
                        "打卡轮次缺少归属期次",
                    )
                }
            }
        }
        if (diagnostics.isNotEmpty()) failAll(diagnostics, "迁移后仍存在无法解释的旧计次数据")
    }

    private fun failAll(diagnostics: List<MigrationDiagnostic>, summary: String): Nothing {
        val detail = diagnostics.joinToString(" | ") {
            "${it.code}(${it.objects.entries.joinToString { entry -> "${entry.key}=${entry.value}" }}): ${it.reason}"
        }
        throw BackupException("$summary：$detail")
    }

    // -------------------------------------------------------- mutable wrapper

    private class MutableTable(
        val table: BackupTableCatalog.Table,
        val rows: MutableList<MutableList<JsonElement?>>,
        private val presentColumns: List<String>?,
    ) {
        fun hadColumn(name: String): Boolean = presentColumns?.contains(name) == true

        fun str(row: MutableList<JsonElement?>, column: String): String? {
            val index = indexOf(column)
            if (index < 0) return null
            val value = row.getOrNull(index) as? JsonPrimitive ?: return null
            return if (value.isString) value.content else null
        }

        fun text(row: MutableList<JsonElement?>, column: String): String? {
            val index = indexOf(column)
            if (index < 0) return null
            return (row.getOrNull(index) as? JsonPrimitive)?.content
        }

        fun int(row: MutableList<JsonElement?>, column: String): Int? =
            text(row, column)?.toIntOrNull()

        fun long(row: MutableList<JsonElement?>, column: String): Long? =
            text(row, column)?.toLongOrNull()

        fun setStr(row: MutableList<JsonElement?>, column: String, value: String?) {
            val index = indexOf(column)
            if (index < 0) return
            row[index] = value?.let(::JsonPrimitive) ?: JsonNull
        }

        fun setInt(row: MutableList<JsonElement?>, column: String, value: Int?) {
            val index = indexOf(column)
            if (index < 0) return
            row[index] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }

        fun addRow(values: Map<String, JsonElement>) {
            val row = MutableList<JsonElement?>(table.columns.size) { JsonNull }
            values.forEach { (column, value) ->
                val index = indexOf(column)
                if (index >= 0) row[index] = value
            }
            rows += row
        }

        private fun indexOf(column: String): Int = table.columnNames.indexOf(column)
    }

    private class MutableDataSet(tables: Map<String, BackupDataSet.TableRows>) {
        private val byName: MutableMap<String, MutableTable> = LinkedHashMap()
        private val order: List<BackupTableCatalog.Table>

        init {
            val included = BackupTableCatalog.includedTables
            order = included
            included.forEach { table ->
                val source = tables[table.name]
                val rows = source?.rows.orEmpty().map { it.toMutableList() }.toMutableList()
                byName[table.name] = MutableTable(table, rows, source?.presentColumns)
            }
        }

        fun table(name: String): MutableTable? = byName[name]

        fun toDataSet(): BackupDataSet = BackupDataSet(
            order.map { table ->
                val mutable = checkNotNull(byName[table.name])
                BackupDataSet.TableRows(
                    table = table,
                    rows = mutable.rows.map { it.toList() },
                    presentColumns = mutable.table.writtenColumnNames,
                )
            },
        )
    }
}
