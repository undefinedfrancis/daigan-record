package com.daigan.record.backup

import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.AccountCategoryEntity
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.HostingSessionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.entity.TaskProgressEventEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressEventContext
import com.daigan.record.data.model.ProgressEventValidator
import com.daigan.record.data.model.ProgressEventViolation
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressScopeType
import com.daigan.record.data.model.TaskProgressEvent
import com.daigan.record.data.model.allowedScopeType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.ReceiptCorrectionKind
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateTaskDraft
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Business validation of a staged restore.
 *
 * Structural validation (types, enums, dates, decimals) already happened while the rows
 * were written into the isolated staging database: Room's converters throw on an unknown
 * enum, an unparseable date or an invalid decimal. This validator adds the **relational
 * and financial contracts** the rest of the app relies on, so a restored dataset cannot
 * contain an order without an account, a receipt belonging to another order's period, a
 * negative amount, or an attachment whose declared size does not match its file.
 */
@Singleton
class BackupValidator @Inject constructor() {

    suspend fun validate(
        database: RecordDatabase,
        dataSet: BackupDataSet,
        extracted: ExtractedArchive,
    ) {
        // Row counts are verified against the **source file** by the restore service before
        // normalization; the normalized dataset legitimately contains extra session rows.
        val (owners, accounts, orders, periods, tasks, occurrences, batches, items, sessions) =
            readStructured(database)
        val (attachments, receipts, corrections, operations) = readFinancial(database)
        val (templates, categories, templateTasks, seeds, pricePresets) = readTemplates(database)
        val accountCategories = wrapStructural { database.accountCategoryDao().findAll() }
        val progressEvents = wrapStructural { database.taskProgressEventDao().findAll() }
        val reminder = database.reminderSettingsDao().find()

        val ownerById = owners.associateBy { it.id }
        val accountById = accounts.associateBy { it.id }
        val orderById = orders.associateBy { it.id }
        val periodById = periods.associateBy { it.id }
        val taskById = tasks.associateBy { it.id }
        val occurrenceById = occurrences.associateBy { it.id }
        val batchById = batches.associateBy { it.id }
        val receiptById = receipts.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        val categoryById = categories.associateBy { it.id }
        val sessionsByOrder = sessions.groupBy { it.orderId }

        validateOwners(owners, dataSet)
        validateAccounts(accounts, ownerById)
        validateOrders(orders, accountById)
        validatePeriods(periods, orderById)
        validateTasks(tasks, orderById, periodById)
        validateOccurrences(occurrences, taskById, orderById, sessionsByOrder)
        validateSessions(sessions, orderById, periodById)
        validateBatches(batches, items, taskById, occurrenceById, orderById)
        validateReceipts(receipts, orders, orderById, periodById, receiptById)
        validateCorrections(corrections, receiptById)
        validateOperations(operations, orderById, receiptById)
        validateTemplates(
            templates,
            categories,
            templateTasks,
            templateById,
            categoryById,
            seeds,
            pricePresets,
        )
        validateAttachments(attachments, taskById, occurrenceById, extracted)
        validateAccountCategories(accountCategories, accounts)
        validateProgressEvents(progressEvents, tasks, periods, sessions, orders)
        validateReminderSettings(reminder)

        // A batch of writes can only be trusted if SQLite itself sees no dangling foreign
        // key after the staging insert.
        val raw = database.openHelper.writableDatabase
        raw.query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.moveToNext()) {
                throw BackupException("备份中的记录关系不完整，无法恢复（存在无对应记录的数据）。")
            }
        }
        raw.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0) != "ok") {
                throw BackupException("备份中的数据库结构校验失败，无法恢复。")
            }
        }
        // A single batch may not reference rows of another dataset.
        if (batchById.isEmpty() && items.isNotEmpty()) {
            throw BackupException("备份中的批量完成记录缺少归属，无法恢复。")
        }
    }

    private data class StructuredRows(
        val owners: List<OwnerEntity>,
        val accounts: List<GameAccountEntity>,
        val orders: List<OrderEntity>,
        val periods: List<HostingPeriodEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val batches: List<CompletionBatchEntity>,
        val items: List<CompletionBatchItemEntity>,
        val sessions: List<HostingSessionEntity>,
    )

    private suspend fun readStructured(database: RecordDatabase): StructuredRows = wrapStructural {
        StructuredRows(
            owners = database.ownerDao().findAll(),
            accounts = database.gameAccountDao().findAll(),
            orders = database.orderDao().findAllOrders(),
            periods = database.orderDao().findAllPeriods(),
            tasks = database.orderDao().findAllTasks(),
            occurrences = database.orderDao().findAllOccurrences(),
            batches = database.completionBatchDao().findAllBatches(),
            items = database.completionBatchDao().findAllItems(),
            sessions = database.hostingSessionDao().findAll(),
        )
    }

    private data class FinancialRows(
        val attachments: List<AttachmentEntity>,
        val receipts: List<ReceiptEntity>,
        val corrections: List<ReceiptCorrectionEntity>,
        val operations: List<OrderCreationOperationEntity>,
    )

    private suspend fun readFinancial(database: RecordDatabase): FinancialRows = wrapStructural {
        FinancialRows(
            attachments = database.orderDao().findAllAttachments(),
            receipts = database.receiptDao().findAll(),
            corrections = database.receiptDao().findAllCorrections(),
            operations = database.creationOperationDao().findAll(),
        )
    }

    private data class TemplateRows(
        val templates: List<GameTemplateEntity>,
        val categories: List<com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        val tasks: List<com.daigan.record.data.local.entity.TemplateTaskEntity>,
        val seeds: List<com.daigan.record.data.local.entity.TemplateSeedStateEntity>,
        val presets: List<com.daigan.record.data.local.entity.TemplatePricePresetEntity>,
    )

    private suspend fun readTemplates(database: RecordDatabase): TemplateRows = wrapStructural {
        val dao = database.templateDao()
        TemplateRows(
            templates = dao.findAllTemplates(),
            categories = dao.findAllCategories(),
            tasks = dao.findAllTemplateTasks(),
            seeds = dao.findAllSeedStates(),
            presets = dao.findAllPricePresets(),
        )
    }

    /** Room converters throw on unknown enums / invalid dates / invalid decimals. */
    private inline fun <T> wrapStructural(block: () -> T): T = try {
        block()
    } catch (error: BackupException) {
        throw error
    } catch (error: RuntimeException) {
        throw BackupException("备份包含无效或无法识别的业务数据，无法恢复。", error)
    }

    private fun validateOwners(owners: List<OwnerEntity>, dataSet: BackupDataSet) {
        owners.forEach { owner ->
            ensure(owner.id.isNotBlank()) { "备份中的老板记录缺少编号，无法恢复。" }
            ensure(owner.number > 0) { "备份中的老板编号无效，无法恢复。" }
            ensure(owner.nickname.isNotBlank()) { "备份中的老板昵称无效，无法恢复。" }
            ensure(owner.createdAt.toEpochMilli() > 0) { "备份中的老板创建时间无效，无法恢复。" }
        }
        val sequenceRows = dataSet.rows("owner_number_sequence")
        ensure(sequenceRows.size <= 1) { "备份中的老板编号计数器重复，无法恢复。" }
        sequenceRows.firstOrNull()?.let { row ->
            val nextNumber = (row.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ensure(nextNumber != null && nextNumber > 0) { "备份中的老板编号计数器无效，无法恢复。" }
        }
        if (owners.isNotEmpty()) {
            ensure(sequenceRows.size == 1) { "备份缺少老板编号计数器，无法恢复。" }
            val maxNumber = owners.maxOf { it.number }
            val nextNumber = (sequenceRows.first().getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull()
                ?: throw BackupException("备份中的老板编号计数器无效，无法恢复。")
            ensure(nextNumber > maxNumber) { "备份中的老板编号计数器与老板编号不一致，无法恢复。" }
        }
    }

    private fun validateAccounts(
        accounts: List<GameAccountEntity>,
        ownerById: Map<String, OwnerEntity>,
    ) {
        accounts.forEach { account ->
            ensure(ownerById.containsKey(account.ownerId)) { "备份中的游戏账号没有对应老板，无法恢复。" }
            ensure(account.gameName.isNotBlank()) { "备份中的游戏账号缺少游戏名称，无法恢复。" }
            ensure(account.server.isNotBlank()) { "备份中的游戏账号缺少区服，无法恢复。" }
            ensure(account.uid.isNotBlank()) { "备份中的游戏账号缺少 UID，无法恢复。" }
            ensure(account.loginAccount.isNotBlank()) { "备份中的游戏账号缺少登录账号，无法恢复。" }
            if (account.loginMethod == LoginMethod.PASSWORD) {
                ensure(account.password != null) { "备份中的密码登录账号缺少密码，无法恢复。" }
            } else {
                ensure(account.password == null) {
                    "备份中的非密码登录账号保存了密码，与登录方式不一致，无法恢复。"
                }
            }
            ensure((account.loginDevice?.length ?: 0) <= 60) {
                "备份中的登录设备名称过长，无法恢复。"
            }
        }
    }

    private fun validateOrders(
        orders: List<OrderEntity>,
        accountById: Map<String, GameAccountEntity>,
    ) {
        orders.forEach { order ->
            ensure(accountById.containsKey(order.accountId)) {
                "备份中的订单没有对应游戏账号，无法恢复。"
            }
            ensure(order.title.isNotBlank()) { "备份中的订单缺少标题，无法恢复。" }
            // schema 14: an optional order colour must be a usable opaque ARGB value.
            order.orderColor?.let { color ->
                ensure(((color ushr 24) and 0xFF) == 0xFF) {
                    "备份中的订单颜色无效，无法恢复。"
                }
            }
            ensureRefresh(order.gameZoneId, order.dailyRefreshMinutes, order.weeklyRefreshDay,
                order.weeklyRefreshMinutes)
            order.monthlyRefreshDays?.let { days ->
                ensure(com.daigan.record.data.model.parseMonthlyDates(days) != null) {
                    "备份中的订单每月刷新日无效，无法恢复。"
                }
            }
            when (order.type) {
                OrderType.SHORT_TASK -> {
                    val price = order.priceCents
                    ensure(price != null && price in 0..Money.MAX_CENTS) {
                        "备份中的短期订单金额无效，无法恢复。"
                    }
                }

                OrderType.HOSTING -> ensure(order.priceCents == null) {
                    "备份中的托管订单不应保存整单金额，无法恢复。"
                }
            }
            if (order.hostingMode == com.daigan.record.data.model.HostingMode.COUNT_BASED) {
                ensure(order.type == OrderType.HOSTING) {
                    "备份中的非托管订单不能使用打卡式托管，无法恢复。"
                }
                ensure(
                    order.targetSessions != null &&
                        order.targetSessions in 1..com.daigan.record.data.model.CountHosting.MAX_TARGET_SESSIONS,
                ) { "备份中的打卡式托管目标次数无效，无法恢复。" }
            } else {
                ensure(order.targetSessions == null) {
                    "备份中的按日期订单不应保存打卡目标次数，无法恢复。"
                }
            }
        }
    }

    /**
     * Count-based rounds (UF-033). A round may only exist on a count-based hosting order,
     * its index is a positive, per-order unique identity, and a completed round has a
     * real completion instant. Date-based orders must not carry rounds: their progress is
     * derived from occurrences, and mixing the two would silently double-count work.
     */
    private fun validateSessions(
        sessions: List<HostingSessionEntity>,
        orderById: Map<String, OrderEntity>,
        periodById: Map<String, HostingPeriodEntity>,
    ) {
        val seen = mutableSetOf<Pair<String, Int>>()
        sessions.forEach { session ->
            val order = orderById[session.orderId]
            ensure(order != null && order.type == OrderType.HOSTING) {
                "备份中的打卡轮次没有对应托管订单，无法恢复。"
            }
            ensure(order.hostingMode == com.daigan.record.data.model.HostingMode.COUNT_BASED) {
                "备份中的按日期托管订单包含打卡轮次，无法恢复。"
            }
            ensure(session.sessionIndex >= 1) {
                "备份中的打卡轮次序号无效，无法恢复。"
            }
            ensure(seen.add(session.orderId to session.sessionIndex)) {
                "备份中的打卡轮次重复，无法恢复。"
            }
            ensure(session.completedAt == null || session.completedAt.toEpochMilli() > 0) {
                "备份中的打卡轮次完成时间无效，无法恢复。"
            }
            ensure(session.completedAt == null || !session.completedAt.isBefore(session.createdAt)) {
                "备份中的打卡轮次完成时间早于创建时间，无法恢复。"
            }
            // 归属期次必须存在、属于同一订单，且该期次确实是计次期次。
            val period = periodById[session.periodId]
            ensure(period != null && period.orderId == order.id) {
                "备份中的打卡轮次期次归属不一致，无法恢复。"
            }
            ensure(period!!.serviceCount != null) {
                "备份中的打卡轮次指向了非计次期次，无法恢复。"
            }
        }
        // 一个期次已完成的轮次数不得超过该期次的目标。
        val completedByPeriod = sessions.filter { it.completedAt != null }.groupingBy { it.periodId }.eachCount()
        completedByPeriod.forEach { (periodId, completed) ->
            val period = periodById[periodId]
            ensure(period?.serviceCount?.let { completed <= it } ?: true) {
                "备份中某期次已完成轮次超过目标次数，无法恢复。"
            }
        }
    }

    private fun validatePeriods(
        periods: List<HostingPeriodEntity>,
        orderById: Map<String, OrderEntity>,
    ) {
        periods.forEach { period ->
            val order = orderById[period.orderId]
            ensure(order != null && order.type == OrderType.HOSTING) {
                "备份中的托管期次没有对应托管订单，无法恢复。"
            }
            ensure(period.periodIndex >= 0) { "备份中的托管期次序号无效，无法恢复。" }
            ensure(!period.endDate.isBefore(period.startDate)) {
                "备份中的托管期次结束日期早于开始日期，无法恢复。"
            }
            if (period.serviceDays != null) {
                val weekdays = com.daigan.record.data.local.parseWeekdays(period.serviceWeekdays)
                ensure(period.serviceDays in 1..3650 && weekdays.isNotEmpty()) { "备份中的累计服务日配置无效，无法恢复。" }
                ensure(com.daigan.record.core.time.GameCalendar.hostingEndDateForWeekdays(period.startDate, period.serviceDays, weekdays) == period.endDate) { "备份中的累计服务日与期限不一致，无法恢复。" }
            } else ensure(period.serviceWeekdays.isNullOrBlank()) { "备份中的服务日模式不完整，无法恢复。" }
            ensure(period.priceCents in 0..Money.MAX_CENTS) {
                "备份中的托管期次金额无效，无法恢复。"
            }
            period.serviceCount?.let { count ->
                ensure(count in 1..CountHosting.MAX_TARGET_SESSIONS) {
                    "备份中的期次打卡目标无效，无法恢复。"
                }
                ensure(period.serviceDays == null) {
                    "备份中的期次不能同时按工作日天数和次数服务，无法恢复。"
                }
            }
            period.monthlyRefreshDays?.let { days ->
                ensure(com.daigan.record.data.model.parseMonthlyDates(days) != null) {
                    "备份中的期次每月刷新日无效，无法恢复。"
                }
            }
            ensureRefresh(period.gameZoneId, period.dailyRefreshMinutes, period.weeklyRefreshDay,
                period.weeklyRefreshMinutes)
        }
    }

    private fun validateTasks(
        tasks: List<TaskDefinitionEntity>,
        orderById: Map<String, OrderEntity>,
        periodById: Map<String, HostingPeriodEntity>,
    ) {
        tasks.forEach { task ->
            val order = orderById[task.orderId]
            ensure(order != null) { "备份中的任务没有对应订单，无法恢复。" }
            ensure(task.title.isNotBlank()) { "备份中的任务缺少标题，无法恢复。" }
            ensure(!task.targetValue.toString().isBlank()) {
                "备份中的任务数值无效，无法恢复。"
            }
            if (order.type == OrderType.SHORT_TASK) {
                ensure(task.periodId == null) {
                    "备份中的短期订单任务关联了托管期次，无法恢复。"
                }
                ensure(task.completedAt == null || task.completedAt.toEpochMilli() > 0) {
                    "备份中的短期任务完成时间无效，无法恢复。"
                }
            } else {
                val period = task.periodId?.let(periodById::get)
                ensure(period != null && period.orderId == order.id) {
                    "备份中的托管任务未正确归属于本期，无法恢复。"
                }
                ensure(task.recurrence != RecurrenceType.ONE_TIME) {
                    "备份中的托管任务使用了一次性周期，无法恢复。"
                }
                ensure(task.completedAt == null) {
                    "备份中的托管任务不应保存任务级完成时间，无法恢复。"
                }
            }
            if (task.progressType == ProgressType.NUMERIC) {
                ensure(task.targetValue > task.startValue) {
                    "备份中的数值任务目标不大于起始值，无法恢复。"
                }
            }
            // 累计口径只属于数值任务；NULL 读作 PER_OCCURRENCE（普通周期任务不变）。
            if (task.numericMode == NumericMode.CUMULATIVE) {
                ensure(task.progressType == ProgressType.NUMERIC) {
                    "备份中的累计任务必须是数值任务，无法恢复。"
                }
            }
            task.colorArgb?.let { color ->
                ensure(((color ushr 24) and 0xFF) == 0xFF) {
                    "备份中的任务颜色无效，无法恢复。"
                }
            }
            if (task.activeFrom != null && task.activeUntil != null) {
                ensure(!task.activeUntil.isBefore(task.activeFrom)) {
                    "备份中的任务生效截止日早于生效日，无法恢复。"
                }
            }
            if (task.recurrence == RecurrenceType.MONTHLY) {
                ensure((com.daigan.record.data.model.parseMonthlyDates(task.monthDays) ?: setOfNotNull(task.monthDay)).let { it.isNotEmpty() && it.all { day -> day in 1..31 } } && (task.monthDays == null || com.daigan.record.data.model.parseMonthlyDates(task.monthDays) != null)) { "备份中的每月日号无效，无法恢复。" }
            }
            if (task.recurrence == RecurrenceType.EVERY_N_DAYS) {
                ensure((task.everyNDays ?: 0) >= 1) {
                    "备份中的任务间隔天数无效，无法恢复。"
                }
            }
        }
    }

    private fun validateOccurrences(
        occurrences: List<TaskOccurrenceEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        orderById: Map<String, OrderEntity>,
        sessionsByOrder: Map<String, List<HostingSessionEntity>>,
    ) {
        occurrences.forEach { occurrence ->
            val task = taskById[occurrence.taskDefinitionId]
            ensure(task != null) { "备份中的打卡记录没有对应任务，无法恢复。" }
            val order = orderById[task.orderId]
            ensure(order != null && order.type == OrderType.HOSTING) {
                "备份中的打卡记录不属于托管订单，无法恢复。"
            }
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                // Count-based work is identified by its round, never by a date.
                val index = CountHosting.sessionIndexOrNull(occurrence.occurrenceKey)
                ensure(index != null) { "备份中的打卡记录轮次无效，无法恢复。" }
                val session = sessionsByOrder[order.id].orEmpty().firstOrNull { it.sessionIndex == index }
                ensure(session != null) { "备份中的打卡记录引用了不存在的轮次，无法恢复。" }
                ensure(session!!.periodId == task.periodId) {
                    "备份中的打卡记录轮次不属于任务所在期次，无法恢复。"
                }
                ensure((occurrence.windowStart == null) == (occurrence.windowEnd == null)) {
                    "备份中的打卡记录时间窗口不完整，无法恢复。"
                }
                if (occurrence.completedAt != null) {
                    ensure(occurrence.recordedAt != null) {
                        "备份中的已完成打卡缺少记录时间，无法恢复。"
                    }
                }
                return@forEach
            }
            ensure(!occurrence.occurrenceKey.startsWith("C:")) {
                "备份中的打卡记录仍使用已退役的 C: 键，无法恢复。"
            }
            val expectedPrefix = when (task.recurrence) {
                RecurrenceType.MONTHLY -> "M:"
                RecurrenceType.DAILY -> "D:"
                RecurrenceType.SELECTED_WEEKDAYS -> "S:"
                RecurrenceType.EVERY_N_DAYS -> "E:"
                RecurrenceType.WEEKLY -> "W:"
                RecurrenceType.ONE_TIME -> throw BackupException(
                    "备份中存在一次性任务的打卡记录，无法恢复。",
                )
            }
            ensure(occurrence.occurrenceKey.startsWith(expectedPrefix)) {
                "备份中的打卡记录键与任务周期不一致，无法恢复。"
            }
            val datePart = occurrence.occurrenceKey.removePrefix(expectedPrefix)
            ensure(runCatching { java.time.LocalDate.parse(datePart) }.isSuccess) {
                "备份中的打卡记录日期无效，无法恢复。"
            }
            if (task.recurrence == RecurrenceType.MONTHLY) {
                val date = java.time.LocalDate.parse(datePart)
                ensure(date.dayOfMonth in (com.daigan.record.data.model.parseMonthlyDates(task.monthDays) ?: setOfNotNull(task.monthDay)) && date == occurrence.gameDate) {
                    "备份中的每月打卡日期与固定日号不一致，无法恢复。"
                }
            }
            ensure((occurrence.windowStart == null) == (occurrence.windowEnd == null)) {
                "备份中的打卡记录时间窗口不完整，无法恢复。"
            }
            if (occurrence.completedAt != null) {
                ensure(occurrence.recordedAt != null) {
                    "备份中的已完成打卡缺少记录时间，无法恢复。"
                }
            }
        }
    }

    private fun validateBatches(
        batches: List<CompletionBatchEntity>,
        items: List<CompletionBatchItemEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        occurrenceById: Map<String, TaskOccurrenceEntity>,
        orderById: Map<String, OrderEntity>,
    ) {
        val batchById = batches.associateBy { it.id }
        batches.forEach { batch ->
            ensure(batch.operationId.isNotBlank()) {
                "备份中的批量完成记录缺少操作编号，无法恢复。"
            }
            if (batch.undoneAt != null) {
                ensure(!batch.undoneAt.isBefore(batch.createdAt)) {
                    "备份中的批量完成撤销时间无效，无法恢复。"
                }
            }
            // D-21：HOSTING_ALL 横跨订单，没有单一目标与游戏日。
            if (batch.scope == BatchScope.HOSTING_ALL) {
                ensure(batch.targetId == null && batch.gameDate == null) {
                    "HOSTING_ALL 批次 ${batch.id} 的 targetId/gameDate 必须为空，无法恢复。"
                }
            } else {
                ensure(batch.targetId != null && orderById.containsKey(batch.targetId)) {
                    "备份中的批次 ${batch.id} 缺少有效目标订单，无法恢复。"
                }
                ensure(batch.gameDate != null) {
                    "备份中的批次 ${batch.id} 缺少游戏日，无法恢复。"
                }
            }
        }
        items.forEach { item ->
            val batch = batchById[item.batchId]
            ensure(batch != null) { "备份中的批量完成明细没有对应批次，无法恢复。" }
            when (item.itemType) {
                BatchItemType.OCCURRENCE -> {
                    if (item.occurrenceId == null) {
                        // CD-01：只有"已撤销批次 + 有效任务引用"才允许无目标历史（目标
                        // **从未记录**）。这与"已知 id 但记录已消失"是两类：后者由归一阶段
                        // 以 UNDONE_BATCH_TARGET_DANGLING 拒绝，绝不被改写成 null。
                        ensure(batch.undoneAt != null && item.taskDefinitionId != null) {
                            "备份中的批量完成明细 ${item.id} 缺少打卡记录引用，无法恢复。"
                        }
                        ensure(taskById.containsKey(item.taskDefinitionId)) {
                            "备份中的批量完成明细 ${item.id} 引用了不存在的任务，无法恢复。"
                        }
                    } else {
                        // 该列是 RESTRICT 外键：任何批次（含已撤销）都不接受悬空引用；
                        // 归一阶段已在激活前给出对象级诊断。
                        val occurrence = occurrenceById[item.occurrenceId]
                        ensure(occurrence != null) {
                            "备份中的批量完成明细 ${item.id} 引用了不存在的打卡记录 ${item.occurrenceId}，无法恢复。"
                        }
                        // Bulk completion stores both references for undo and archive guards.
                        // Older archives may omit the task reference; when present it must
                        // describe the same task as the occurrence, not a second owner.
                        ensure(item.taskDefinitionId == null ||
                            item.taskDefinitionId == occurrence.taskDefinitionId) {
                            "备份中的批量完成明细 ${item.id} 的任务引用与打卡记录不一致，无法恢复。"
                        }
                    }
                }

                BatchItemType.SHORT_TASK -> {
                    ensure(item.taskDefinitionId != null && item.occurrenceId == null) {
                        "备份中的批量完成明细归属不唯一，无法恢复。"
                    }
                    ensure(taskById.containsKey(item.taskDefinitionId)) {
                        "备份中的批量完成明细没有对应任务，无法恢复。"
                    }
                }
            }
            ensure(batch.scope != BatchScope.SHORT_ORDER || item.itemType == BatchItemType.SHORT_TASK) {
                "备份中的批量完成范围与明细不一致，无法恢复。"
            }
        }
    }

    private fun validateReceipts(
        receipts: List<ReceiptEntity>,
        orders: List<OrderEntity>,
        orderById: Map<String, OrderEntity>,
        periodById: Map<String, HostingPeriodEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        val activeTotals = receipts.filter { it.status == ReceiptStatus.ACTIVE }
            .groupBy { it.orderId to it.periodId }
        activeTotals.values.forEach { rows ->
            // A corrected agreed price may be zero or less than actual historical
            // receipts. Keep the money and restore that legitimate overpayment;
            // only arithmetic overflow is invalid at the aggregate level.
            try { rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountCents) } }
            catch (error: ArithmeticException) {
                throw BackupException("备份中的收款总额超出范围，无法恢复。", error)
            }
        }
        receipts.forEach { receipt ->
            ensure(receipt.amountCents in 1..Money.MAX_CENTS) {
                "备份中的收款金额无效，无法恢复。"
            }
            ensure(receipt.operationId.isNotBlank()) {
                "备份中的收款记录缺少操作编号，无法恢复。"
            }
            val order = orderById[receipt.orderId]
            ensure(order != null) { "备份中的收款没有对应订单，无法恢复。" }
            when (order.type) {
                OrderType.SHORT_TASK -> ensure(receipt.periodId == null) {
                    "备份中的短期订单收款关联了托管期次，无法恢复。"
                }

                OrderType.HOSTING -> {
                    val period = receipt.periodId?.let(periodById::get)
                    ensure(period != null && period.orderId == order.id) {
                        "备份中的托管收款没有归属到该订单的期次，无法恢复。"
                    }
                }
            }
            when (receipt.status) {
                ReceiptStatus.ACTIVE -> ensure(receipt.replacedByReceiptId == null) {
                    "备份中的有效收款指向了替换记录，无法恢复。"
                }

                ReceiptStatus.REPLACED -> {
                    val replacement = receipt.replacedByReceiptId?.let(receiptById::get)
                    ensure(replacement != null && replacement.id != receipt.id &&
                        replacement.orderId == receipt.orderId && replacement.periodId == receipt.periodId
                    ) {
                        "备份中的收款替换关系不完整或归属不一致，无法恢复。"
                    }
                }

                ReceiptStatus.VOIDED -> ensure(receipt.replacedByReceiptId == null) {
                    "备份中的作废收款不应指向替换记录，无法恢复。"
                }
            }
            ensure(receipt.receivedAt.toEpochMilli() > 0) {
                "备份中的实际到账时间无效，无法恢复。"
            }
        }
        // Every order in the backup must be readable through the map (cheap consistency).
        ensure(orders.size == orderById.size) { "备份中的订单编号重复，无法恢复。" }
    }

    private fun validateCorrections(
        corrections: List<ReceiptCorrectionEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        corrections.forEach { correction ->
            ensure(correction.operationId.isNotBlank()) {
                "备份中的更正记录缺少操作编号，无法恢复。"
            }
            ensure(receiptById.containsKey(correction.receiptId)) {
                "备份中的更正记录没有对应收款，无法恢复。"
            }
            ensure(correction.previousAmountCents in 1..Money.MAX_CENTS) {
                "备份中的更正前金额无效，无法恢复。"
            }
            when (correction.kind) {
                ReceiptCorrectionKind.AMEND -> {
                    ensure(correction.newReceiptId != null) {
                        "备份中的金额更正缺少新收款记录，无法恢复。"
                    }
                    val previous = checkNotNull(receiptById[correction.receiptId])
                    val replacement = receiptById[correction.newReceiptId]
                    ensure(replacement != null && replacement.id != previous.id &&
                        replacement.orderId == previous.orderId && replacement.periodId == previous.periodId
                    ) {
                        "备份中的金额更正指向了不存在或归属不一致的收款，无法恢复。"
                    }
                    val newAmount = correction.newAmountCents
                    ensure(newAmount != null && newAmount in 1..Money.MAX_CENTS) {
                        "备份中的更正后金额无效，无法恢复。"
                    }
                    ensure(correction.newReceivedAt != null && correction.newChannel != null) {
                        "备份中的更正后信息不完整，无法恢复。"
                    }
                }

                ReceiptCorrectionKind.VOID -> {
                    ensure(correction.newReceiptId == null && correction.newAmountCents == null &&
                        correction.newReceivedAt == null && correction.newChannel == null &&
                        correction.newNote == null
                    ) {
                        "备份中的作废记录包含了新金额，无法恢复。"
                    }
                }
            }
        }
    }

    private fun validateOperations(
        operations: List<OrderCreationOperationEntity>,
        orderById: Map<String, OrderEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        operations.forEach { operation ->
            val order = orderById[operation.orderId]
            ensure(order != null) { "备份中的建单记录没有对应订单，无法恢复。" }
            // The operation describes the original request for idempotent retries.
            // A later unused-order type correction must not rewrite that history.
            ensure(operation.fingerprint.isNotBlank()) {
                "备份中的建单记录缺少请求指纹，无法恢复。"
            }
            operation.receiptId?.let { receiptId ->
                val receipt = receiptById[receiptId]
                ensure(receipt != null && receipt.orderId == operation.orderId) {
                    "备份中的首笔收款与订单不一致，无法恢复。"
                }
            }
        }
    }

    private fun validateTemplates(
        templates: List<GameTemplateEntity>,
        categories: List<com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        templateTasks: List<com.daigan.record.data.local.entity.TemplateTaskEntity>,
        templateById: Map<String, GameTemplateEntity>,
        categoryById: Map<String, com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        seeds: List<com.daigan.record.data.local.entity.TemplateSeedStateEntity>,
        pricePresets: List<com.daigan.record.data.local.entity.TemplatePricePresetEntity>,
    ) {
        val builtInKeys = HashSet<String>()
        templates.forEach { template ->
            ensure(template.gameName.isNotBlank()) { "备份中的模板缺少游戏名称，无法恢复。" }
            ensure(template.templateVersion.isNotBlank()) { "备份中的模板缺少版本，无法恢复。" }
            ensureRefresh(template.zoneId, template.dailyRefreshMinutes, template.weeklyRefreshDay,
                template.weeklyRefreshMinutes)
            template.builtInKey?.let {
                ensure(builtInKeys.add(it)) { "备份中的内置模板标识重复，无法恢复。" }
            }
        }
        templates.forEach { template ->
            // 模板级托管默认值是分类共识镜像；NULL 读作按日期。
            template.targetSessions?.let { target ->
                ensure(template.hostingMode == HostingMode.COUNT_BASED) {
                    "备份中的模板非计次模式不得保留目标次数，无法恢复。"
                }
                ensure(target in 1..CountHosting.MAX_TARGET_SESSIONS) {
                    "备份中的模板打卡目标无效，无法恢复。"
                }
            }
            if (template.hostingMode == HostingMode.COUNT_BASED) {
                ensure(template.targetSessions != null) {
                    "备份中的模板计次模式缺少目标次数，无法恢复。"
                }
            }
            template.serviceWeekdays?.let { weekdays ->
                ensure(com.daigan.record.data.local.parseWeekdays(weekdays).isNotEmpty()) {
                    "备份中的模板服务星期无效，无法恢复。"
                }
            }
            template.defaultOrderTitle?.let { title ->
                ensure(title == title.trim() && title.isNotEmpty() && title.length <= 60) {
                    "备份中的模板默认标题无效，无法恢复。"
                }
            }
        }
        categories.forEach { category ->
            ensure(templateById.containsKey(category.templateId)) {
                "备份中的模板分类没有对应模板，无法恢复。"
            }
            ensure(category.name.isNotBlank()) { "备份中的模板分类缺少名称，无法恢复。" }
            // 分类 hostingMode 为 NULL 表示继承模板；DATE_RANGE 是显式按日期。
            if (category.hostingMode == HostingMode.DATE_RANGE) {
                ensure(category.targetSessions == null) {
                    "备份中的分类显式按日期时不得保留目标次数，无法恢复。"
                }
            }
            if (category.hostingMode == HostingMode.COUNT_BASED) {
                ensure(category.targetSessions != null) {
                    "备份中的分类计次模式缺少目标次数，无法恢复。"
                }
            }
            category.targetSessions?.let { target ->
                ensure(target in 1..CountHosting.MAX_TARGET_SESSIONS) {
                    "备份中的分类打卡目标无效，无法恢复。"
                }
            }
            category.serviceWeekdays?.let { weekdays ->
                ensure(com.daigan.record.data.local.parseWeekdays(weekdays).isNotEmpty()) {
                    "备份中的分类服务星期无效，无法恢复。"
                }
            }
        }
        templateTasks.forEach { task ->
            if (task.recurrence == RecurrenceType.MONTHLY) {
                ensure((com.daigan.record.data.model.parseMonthlyDates(task.monthDays) ?: setOfNotNull(task.monthDay)).let { it.isNotEmpty() && it.all { day -> day in 1..31 } } && (task.monthDays == null || com.daigan.record.data.model.parseMonthlyDates(task.monthDays) != null)) { "备份中的模板每月日号无效，无法恢复。" }
            }
            ensure(templateById.containsKey(task.templateId)) {
                "备份中的模板任务没有对应模板，无法恢复。"
            }
            val category = categoryById[task.categoryId]
            ensure(category != null && category.templateId == task.templateId) {
                "备份中的模板任务分类归属不一致，无法恢复。"
            }
            ensure(task.title.isNotBlank()) { "备份中的模板任务缺少名称，无法恢复。" }
            if (task.progressType == ProgressType.NUMERIC) {
                ensure(task.targetValue > task.startValue) {
                    "备份中的模板数值任务目标不大于起始值，无法恢复。"
                }
            }
        }
        val seenPresetAmounts = HashMap<String, MutableSet<Long>>()
        pricePresets.forEach { preset ->
            ensure(templateById.containsKey(preset.templateId)) {
                "备份中的价格预设没有对应模板，无法恢复。"
            }
            ensure(preset.amountCents in 0..Money.MAX_CENTS) {
                "备份中的价格预设金额无效，无法恢复。"
            }
            ensure(
                seenPresetAmounts.getOrPut(preset.templateId) { HashSet() }
                    .add(preset.amountCents),
            ) {
                "备份中同一模板存在重复的价格预设，无法恢复。"
            }
            ensure((preset.label?.length ?: 0) <= TemplateDraft.MAX_PRICE_PRESET_LABEL_LENGTH) {
                "备份中的价格预设名称过长，无法恢复。"
            }
        }
        seeds.forEach { seed ->
            ensure(seed.seedKey.isNotBlank()) { "备份中的内置模板标记无效，无法恢复。" }
        }
    }

    private fun validateAttachments(
        attachments: List<AttachmentEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        occurrenceById: Map<String, TaskOccurrenceEntity>,
        extracted: ExtractedArchive,
    ) {
        val directory = extracted.attachmentsDirectory
        val manifestByPath = extracted.manifest.attachments.associateBy { it.relativePath }
        attachments.forEach { attachment ->
            ensure((attachment.taskDefinitionId == null) != (attachment.occurrenceId == null)) {
                "备份中的截图归属不唯一，无法恢复。"
            }
            attachment.taskDefinitionId?.let {
                ensure(taskById.containsKey(it)) { "备份中的截图没有对应任务，无法恢复。" }
            }
            attachment.occurrenceId?.let {
                ensure(occurrenceById.containsKey(it)) { "备份中的截图没有对应打卡记录，无法恢复。" }
            }
            ensure(SafeNames.isSafeAttachmentName(attachment.relativePath)) {
                "备份中的截图文件名不安全，无法恢复。"
            }
            ensure(attachment.sizeBytes in 1..BackupFormat.Limits.MAX_ATTACHMENT_BYTES) {
                "备份中的截图大小无效，无法恢复。"
            }
            ensure(attachment.mimeType.startsWith("image/")) {
                "备份中的截图类型无效，无法恢复。"
            }
            val manifestEntry = manifestByPath[attachment.relativePath]
                ?: throw BackupException("备份清单中没有截图「${attachment.relativePath}」，无法恢复。")
            ensure(manifestEntry.id == attachment.id && manifestEntry.mimeType == attachment.mimeType) {
                "备份中的截图编号或类型与清单不一致，无法恢复。"
            }
            ensure(manifestEntry.sizeBytes == attachment.sizeBytes) {
                "备份中的截图大小与清单不一致，无法恢复。"
            }
            val file = File(directory, attachment.relativePath)
            ensure(file.isFile) { "备份缺少截图文件「${attachment.relativePath}」，无法恢复。" }
            ensure(file.length() == attachment.sizeBytes) {
                "备份中的截图「${attachment.relativePath}」内容大小不一致，无法恢复。"
            }
        }
        val referenced = attachments.map { it.relativePath }.toSet()
        val declared = manifestByPath.keys
        ensure(referenced == declared) {
            "备份中的截图清单与截图记录不一致，无法恢复。"
        }
    }

    /**
     * 账号分类（UF-037）。分类是全局的；账号只能引用存在的分类，删除分类的路径
     * 必须在同一事务里把成员账号置为未分类，因此不允许悬空引用。
     */
    private fun validateAccountCategories(
        categories: List<AccountCategoryEntity>,
        accounts: List<GameAccountEntity>,
    ) {
        val byId = categories.associateBy { it.id }
        categories.forEach { category ->
            ensure(category.name.isNotBlank() && category.name.length <= 30) {
                "备份中的账号分类名称无效，无法恢复。"
            }
            ensure(category.name == category.name.trim()) {
                "备份中的账号分类名称未规范化，无法恢复。"
            }
            ensure(category.sortOrder >= 0) { "备份中的账号分类排序无效，无法恢复。" }
        }
        ensure(byId.size == categories.size) { "备份中的账号分类编号重复，无法恢复。" }
        val names = categories.map { it.name }
        ensure(names.toSet().size == names.size) { "备份中的账号分类名称重复，无法恢复。" }
        accounts.forEach { account ->
            account.categoryId?.let { categoryId ->
                ensure(byId.containsKey(categoryId)) {
                    "备份中的账号引用了不存在的分类，无法恢复。"
                }
            }
        }
    }

    /**
     * 累计事件链（UF-038）。事件是权威：scope 矩阵、归属、更正链与序号关系全部校验，
     * 导入的缓存值不会被信任。
     */
    private fun validateProgressEvents(
        events: List<TaskProgressEventEntity>,
        tasks: List<TaskDefinitionEntity>,
        periods: List<HostingPeriodEntity>,
        sessions: List<HostingSessionEntity>,
        orders: List<OrderEntity>,
    ) {
        if (events.isEmpty()) return
        val taskById = tasks.associateBy { it.id }
        val orderById = orders.associateBy { it.id }
        val periodOrder = periods.associate { it.id to it.orderId }
        val sessionOrder = sessions.associate { it.id to it.orderId }
        val sessionPeriod = sessions.associate { it.id to it.periodId }
        val scopeTypeByTask = tasks.associate { task ->
            val order = orderById.getValue(task.orderId)
            task.id to allowedScopeType(order.type, order.hostingMode)
        }
        val context = ProgressEventContext(
            scopeTypeByTask = scopeTypeByTask,
            sessionOrder = sessionOrder,
            sessionPeriod = sessionPeriod,
            periodOrder = periodOrder,
            taskOrder = tasks.associate { it.id to it.orderId },
            taskPeriod = tasks.associate { it.id to it.periodId },
            cumulativeTaskIds = tasks
                .filter { it.progressType == ProgressType.NUMERIC && it.numericMode == NumericMode.CUMULATIVE }
                .map { it.id }
                .toSet(),
            countPeriodIds = periods.filter { it.serviceCount != null }.map { it.id }.toSet(),
            periodStart = periods.associate { it.id to it.startDate },
            // 计次期次的下界是实际服务起点（F1）：锚点被唯一约束推后时，同日续期的
            // 合法进度仍必须通过恢复校验。无法换算时退回锚点，绝不放大区间。
            countPeriodServiceStart = periods.associate { period ->
                period.id to runCatching { period.toModel().serviceStartDate }
                    .getOrDefault(period.startDate)
            },
            periodEnd = periods.associate { it.id to it.endDate },
            taskActiveFrom = tasks.associate { it.id to it.activeFrom },
            taskActiveUntil = tasks.associate { it.id to it.activeUntil },
        )
        // 原始实体的 scope 字段形状必须先于任何映射检查：领域对象把不一致的形状抹平了。
        val shapeViolations = events.mapNotNull { event ->
            val periodOk = if (event.scopeType == ProgressScopeType.PERIOD) {
                event.scopePeriodId != null
            } else {
                event.scopePeriodId == null
            }
            val sessionOk = if (event.scopeType == ProgressScopeType.SESSION) {
                event.scopeSessionId != null
            } else {
                event.scopeSessionId == null
            }
            if (periodOk && sessionOk) {
                null
            } else {
                ProgressEventViolation(
                    "SCOPE_FIELDS_INCONSISTENT",
                    event.id,
                    "scopeType=${event.scopeType} 与 scopePeriodId=${event.scopePeriodId}/scopeSessionId=${event.scopeSessionId} 不一致",
                    mapOf(
                        "eventId" to event.id,
                        "taskDefinitionId" to event.taskDefinitionId,
                        "scopePeriodId" to event.scopePeriodId.orEmpty(),
                        "scopeSessionId" to event.scopeSessionId.orEmpty(),
                    ),
                )
            }
        }
        events.forEach { event ->
            ensure(taskById.containsKey(event.taskDefinitionId)) {
                "备份中的累计事件没有对应任务，无法恢复。"
            }
            event.totalValue?.let { value ->
                ensure(value.abs() <= TemplateTaskDraft.MAX_DECIMAL_ABS) {
                    "备份中的累计事件 ${event.id} 总值超出支持范围，无法恢复。"
                }
                ensure(value.scale() <= TemplateTaskDraft.MAX_DECIMAL_SCALE) {
                    "备份中的累计事件 ${event.id} 总值小数位过多，无法恢复。"
                }
            }
        }
        val domain = events.map { event ->
            val scope = when (event.scopeType) {
                ProgressScopeType.PERIOD -> ProgressScope.Period(event.scopePeriodId.orEmpty())
                ProgressScopeType.SESSION -> ProgressScope.Session(event.scopeSessionId.orEmpty())
                ProgressScopeType.TASK -> ProgressScope.Task
            }
            TaskProgressEvent(
                id = event.id,
                taskDefinitionId = event.taskDefinitionId,
                operationId = event.operationId,
                kind = event.kind,
                scope = scope,
                gameDate = event.gameDate,
                totalValue = event.totalValue,
                sequence = event.sequence,
                correctsEventId = event.correctsEventId,
                recordedAt = event.recordedAt,
                createdAt = event.createdAt,
            )
        }
        val violations = shapeViolations + ProgressEventValidator.validate(domain, context)
        ensure(violations.isEmpty()) {
            val first = violations.first()
            val objects = first.objects.entries
                .filter { it.value.isNotEmpty() }
                .joinToString(" ") { "${it.key}=${it.value}" }
            "备份中的累计事件不合法（${first.code}，事件 ${first.eventId}" +
                (if (objects.isEmpty()) "" else "，$objects") +
                "）：${first.reason}"
        }
    }

    private fun validateReminderSettings(
        settings: com.daigan.record.data.local.entity.ReminderSettingsEntity?,
    ) {
        if (settings == null) return
        ensure(settings.id == 0) { "备份中的提醒设置重复，无法恢复。" }
        listOf(
            settings.shortDeadlineMinutes,
            settings.hostingExpiryMinutes,
            settings.unfinishedTasksMinutes,
        ).forEach { minutes ->
            ensure(minutes in 0..1439) { "备份中的提醒时间无效，无法恢复。" }
        }
    }

    private fun ensureRefresh(
        zoneId: String,
        dailyMinutes: Int,
        weeklyDay: Int,
        weeklyMinutes: Int,
    ) {
        ensure(runCatching { ZoneId.of(zoneId) }.isSuccess) {
            "备份中的时区无效，无法恢复。"
        }
        ensure(dailyMinutes in 0..1439) { "备份中的每日刷新时间无效，无法恢复。" }
        ensure(weeklyDay in 1..7) { "备份中的每周刷新日无效，无法恢复。" }
        ensure(weeklyMinutes in 0..1439) { "备份中的每周刷新时间无效，无法恢复。" }
    }

    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    private inline fun ensure(condition: Boolean, message: () -> String) {
        kotlin.contracts.contract { returns() implies condition }
        if (!condition) throw BackupException(message())
    }
}
