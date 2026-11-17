package com.daigan.record.backup

import com.daigan.record.backup.BackupFixtures.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 规范化迁移的**产品级**测试：真实走 `BackupDataSet` → 目录列对齐 → 迁移。
 *
 * 这些用例只覆盖迁移算法本身；真实 Room 迁移由 `MigrationV14To15SchemaTest` 静态核对，
 * 执行级验证仍需要设备（见交接文档的"未验证"章节）。
 */
class BackupNormalizerTest {

    private val now = 1_760_000_000_000L

    private fun owner() = mapOf(
        "id" to "ow1",
        "number" to 1,
        "nickname" to "老板",
        "createdAt" to now,
        "updatedAt" to now,
    )

    private fun account(categoryId: Any? = null) = mapOf(
        "id" to "ac1",
        "ownerId" to "ow1",
        "gameName" to "游戏",
        "server" to "官服",
        "uid" to "1",
        "loginAccount" to "acc",
        "loginMethod" to "PASSWORD",
        "password" to "p",
        "createdAt" to now,
        "updatedAt" to now,
        "categoryId" to categoryId,
    )

    private fun order(
        id: String,
        hostingMode: String? = null,
        targetSessions: Any? = null,
    ) = mapOf(
        "id" to id,
        "accountId" to "ac1",
        "type" to "HOSTING",
        "title" to "订单",
        "paymentMode" to "INSTALLMENTS",
        "gameZoneId" to "Asia/Shanghai",
        "dailyRefreshMinutes" to 300,
        "weeklyRefreshDay" to 1,
        "weeklyRefreshMinutes" to 300,
        "createdAt" to now,
        "updatedAt" to now,
        "hostingMode" to hostingMode,
        "targetSessions" to targetSessions,
    )

    private fun period(
        id: String,
        orderId: String,
        periodIndex: Int = 0,
        serviceCount: Any? = null,
        startDate: String = "2026-02-01",
        endDate: String = "2026-02-28",
    ) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodIndex" to periodIndex,
        "startDate" to startDate,
        "endDate" to endDate,
        "priceCents" to 30000L,
        "gameZoneId" to "Asia/Shanghai",
        "dailyRefreshMinutes" to 300,
        "weeklyRefreshDay" to 1,
        "weeklyRefreshMinutes" to 300,
        "createdAt" to now,
        "serviceCount" to serviceCount,
    )

    private fun task(
        id: String,
        orderId: String,
        periodId: String?,
        recurrence: String = "DAILY",
        sortOrder: Int = 0,
    ) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodId" to periodId,
        "title" to "任务",
        "progressType" to "CHECKBOX",
        "startValue" to "0",
        "targetValue" to "1",
        "currentValue" to "0",
        "recurrence" to recurrence,
        "sortOrder" to sortOrder,
        "createdAt" to now,
        "updatedAt" to now,
    )

    private fun occurrence(
        id: String,
        taskId: String,
        key: String,
        gameDate: String,
        completedAt: Long? = now + 1000,
        createdAt: Long = now,
        batchId: String? = null,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "occurrenceKey" to key,
        "gameDate" to gameDate,
        "completedAt" to completedAt,
        "recordedAt" to completedAt,
        "backfilled" to 0,
        "value" to null,
        "createdAt" to createdAt,
        "completionBatchId" to batchId,
    )

    @Test
    fun `web count data becomes one session per period and date, shared by tasks`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table("game_accounts", listOf(account())),
            table("orders", listOf(order("o1"), order("o2"))),
            table(
                "hosting_periods",
                listOf(
                    period("o1p1", "o1", 0, 5),
                    period("o1p2", "o1", 1, 4, "2026-03-01", "2026-03-31"),
                    period("o2p1", "o2", 0, 3),
                ),
            ),
            table(
                "task_definitions",
                listOf(
                    task("t1", "o1", "o1p1"),
                    task("t2", "o1", "o1p1", sortOrder = 1),
                    task("t3", "o1", "o1p2"),
                    task("t4", "o2", "o2p1"),
                ),
            ),
            table(
                "task_occurrences",
                listOf(
                    occurrence("oc1", "t1", "C:2026-02-02", "2026-02-02"),
                    occurrence("oc2", "t2", "C:2026-02-02", "2026-02-02"),
                    occurrence("oc3", "t1", "C:2026-02-03", "2026-02-03"),
                    occurrence("oc4", "t3", "C:2026-02-02", "2026-02-02", completedAt = null),
                    occurrence("oc5", "t4", "C:2026-02-02", "2026-02-02"),
                ),
            ),
            table("hosting_sessions", emptyList(), presentColumns = null),
        )

        val result = BackupNormalizer.normalize(dataSet, 12)
        val sessions = result.dataSet.tableRows2("hosting_sessions")
        assertEquals(4, sessions.size)
        // 每个订单独立编号；同一日期跨期次得到不同号。
        val identities = sessions.map { row -> "${row["orderId"]}#${row["sessionIndex"]}:${row["periodId"]}" }
        assertEquals(listOf("o1#1:o1p1", "o1#2:o1p1", "o1#3:o1p2", "o2#1:o2p1"), identities)

        val keys = result.dataSet.tableRows2("task_occurrences").associate { it["id"] to it["occurrenceKey"] }
        assertEquals("N:1", keys["oc1"])
        assertEquals("N:1", keys["oc2"])
        assertEquals("N:2", keys["oc3"])
        assertEquals("N:3", keys["oc4"])
        assertEquals("N:1", keys["oc5"])

        // 完成度按历史应做集重建：02-02 两个任务都有行且完成 → 取最晚完成时刻。
        assertEquals(now + 1000, sessions.first { it["orderId"] == "o1" && it["sessionIndex"] == 1L }["completedAt"])
        // 02-03 只有 t1 一行，t2 缺行 → 不完成。
        assertNull(sessions.first { it["orderId"] == "o1" && it["sessionIndex"] == 2L }["completedAt"])
        // o1p2 的 02-02 只有 t3 一行（未完成）→ 不完成。
        assertNull(sessions.first { it["orderId"] == "o1" && it["sessionIndex"] == 3L }["completedAt"])

        // 订单级托管模式由期次推导（web 12 从未写该列）。
        val orders = result.dataSet.tableRows2("orders").associateBy { it["id"] }
        assertEquals("COUNT_BASED", orders["o1"]?.get("hostingMode"))
        assertEquals(4L, orders["o1"]?.get("targetSessions"))
        assertTrue(result.applied.contains("web-count-sessions-from-dates"))
        assertTrue(result.applied.contains("category-service-defaults"))
    }

    @Test
    fun `mixed C and N in one order is rejected before any rewrite`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table("game_accounts", listOf(account())),
            table("orders", listOf(order("o1", "COUNT_BASED", 3))),
            table("hosting_periods", listOf(period("o1p1", "o1", 0, 3))),
            table("task_definitions", listOf(task("t1", "o1", "o1p1"))),
            table(
                "task_occurrences",
                listOf(
                    occurrence("oc1", "t1", "C:2026-02-02", "2026-02-02"),
                    occurrence("oc2", "t1", "N:1", "2026-02-03"),
                ),
            ),
            table(
                "hosting_sessions",
                listOf(
                    mapOf(
                        "id" to "s1",
                        "orderId" to "o1",
                        "sessionIndex" to 1,
                        "createdAt" to now,
                        "periodId" to "o1p1",
                    ),
                ),
            ),
        )
        try {
            BackupNormalizer.normalize(dataSet, 14)
            fail("mixed C:/N: input was accepted")
        } catch (error: BackupException) {
            assertTrue(error.message!!.contains("UNSUPPORTED_MIXED_C_N_INPUT"))
        }
        // 拒绝后原始数据集不变。
        assertEquals("C:2026-02-02", dataSet.tableRows2("task_occurrences").first()["occurrenceKey"])
    }

    @Test
    fun `android count order with one period is provable, with two it aborts`() {
        val provable = BackupFixtures.build(
            table("owners", listOf(owner())),
            table("game_accounts", listOf(account())),
            table("orders", listOf(order("o1", "COUNT_BASED", 6))),
            table("hosting_periods", listOf(period("o1p1", "o1"))),
            table("task_definitions", listOf(task("t1", "o1", "o1p1"))),
            table("task_occurrences", listOf(occurrence("oc1", "t1", "N:1", "2026-02-02"))),
            table(
                "hosting_sessions",
                listOf(
                    mapOf(
                        "id" to "s1",
                        "orderId" to "o1",
                        "sessionIndex" to 1,
                        "createdAt" to now,
                    ),
                ),
                // schema 13/14 的文件没有这一列。
                presentColumns = BackupFixtures.legacyColumns(
                    "hosting_sessions",
                    setOf("id", "orderId", "sessionIndex", "createdAt", "completedAt", "completionBatchId"),
                ),
            ),
        )
        val result = BackupNormalizer.normalize(provable, 14)
        assertEquals(6L, result.dataSet.tableRows2("hosting_periods").first()["serviceCount"])
        assertEquals("o1p1", result.dataSet.tableRows2("hosting_sessions").first()["periodId"])

        val ambiguous = BackupFixtures.build(
            table("owners", listOf(owner())),
            table("game_accounts", listOf(account())),
            table("orders", listOf(order("o1", "COUNT_BASED", 6))),
            table(
                "hosting_periods",
                listOf(period("o1p1", "o1", 0), period("o1p2", "o1", 1)),
            ),
            table(
                "hosting_sessions",
                listOf(
                    mapOf(
                        "id" to "s1",
                        "orderId" to "o1",
                        "sessionIndex" to 1,
                        "createdAt" to now,
                    ),
                ),
            ),
        )
        try {
            BackupNormalizer.normalize(ambiguous, 14)
            fail("ambiguous count ownership was accepted")
        } catch (error: BackupException) {
            assertTrue(error.message!!.contains("COUNT_PERIOD_AMBIGUOUS"))
        }
    }

    private fun legacyTemplateRows(
        serviceMode: Any? = "count",
        serviceCount: Any? = 12,
    ) = listOf(
        mapOf(
            "id" to "gt1",
            "gameName" to "游戏",
            "templateVersion" to "1.0",
            "zoneId" to "Asia/Shanghai",
            "dailyRefreshMinutes" to 300,
            "weeklyRefreshDay" to 1,
            "weeklyRefreshMinutes" to 300,
            "origin" to "USER",
            "sortOrder" to 0,
            "createdAt" to now,
            "updatedAt" to now,
            "serviceMode" to serviceMode,
            "serviceCount" to serviceCount,
        ),
    )

    private val legacyTemplateColumns = setOf(
        "id", "gameName", "templateVersion", "gameVersionNotes", "zoneId",
        "dailyRefreshMinutes", "weeklyRefreshDay", "weeklyRefreshMinutes",
        "origin", "builtInKey", "sortOrder", "createdAt", "updatedAt",
        "serviceMode", "serviceCount",
    )

    @Test
    fun `category table without the service columns materialises the template value`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table(
                "game_templates",
                legacyTemplateRows(),
                presentColumns = BackupFixtures.legacyColumns("game_templates", legacyTemplateColumns),
            ),
            table(
                "template_categories",
                listOf(
                    mapOf("id" to "tc1", "templateId" to "gt1", "name" to "历史缺列", "sortOrder" to 0),
                ),
                // 早于分类扩展的文件：整张分类表都没有 serviceMode/serviceCount。
                presentColumns = BackupFixtures.legacyColumns(
                    "template_categories",
                    setOf("id", "templateId", "name", "sortOrder"),
                ),
            ),
        )

        val result = BackupNormalizer.normalize(dataSet, 12)
        val category = result.dataSet.tableRows2("template_categories").single()
        // 缺列 = 没有意见 → 固化模板值（count 12），不是"继承"也不是"按日期"。
        assertEquals("COUNT_BASED", category["hostingMode"])
        assertEquals(12L, category["targetSessions"])
        assertTrue(result.notes.any { it.contains("按模板") })
        // 只有一个分类且与模板一致 → 模板镜像保留该值。
        val template = result.dataSet.tableRows2("game_templates").single()
        assertEquals("COUNT_BASED", template["hostingMode"])
        assertEquals(12L, template["targetSessions"])
    }

    @Test
    fun `category column present distinguishes stored null from explicit count`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table(
                "game_templates",
                legacyTemplateRows(),
                presentColumns = BackupFixtures.legacyColumns("game_templates", legacyTemplateColumns),
            ),
            table(
                "template_categories",
                listOf(
                    mapOf(
                        "id" to "tc1",
                        "templateId" to "gt1",
                        "name" to "显式日期",
                        "sortOrder" to 0,
                        "serviceMode" to null,
                    ),
                    mapOf(
                        "id" to "tc3",
                        "templateId" to "gt1",
                        "name" to "计次",
                        "sortOrder" to 1,
                        "serviceMode" to "count",
                        "serviceCount" to 8,
                    ),
                    mapOf(
                        "id" to "tc4",
                        "templateId" to "gt1",
                        "name" to "仅工作日",
                        "sortOrder" to 2,
                        "serviceMode" to "weekdays",
                    ),
                ),
                presentColumns = BackupFixtures.legacyColumns(
                    "template_categories",
                    setOf("id", "templateId", "name", "sortOrder", "serviceMode", "serviceCount"),
                ),
            ),
        )

        val result = BackupNormalizer.normalize(dataSet, 12)
        val categories = result.dataSet.tableRows2("template_categories").associateBy { it["id"] }
        // 存 null = 保存路径把 'dates' 归一 → 显式按日期，绝不是继承。
        assertEquals("DATE_RANGE", categories["tc1"]?.get("hostingMode"))
        assertNull(categories["tc1"]?.get("targetSessions"))
        assertEquals("COUNT_BASED", categories["tc3"]?.get("hostingMode"))
        assertEquals(8L, categories["tc3"]?.get("targetSessions"))
        assertEquals("DATE_RANGE", categories["tc4"]?.get("hostingMode"))
        assertEquals("1,2,3,4,5", categories["tc4"]?.get("serviceWeekdays"))

        // 三个分类分歧 → 模板镜像三项全 null。
        val template = result.dataSet.tableRows2("game_templates").single()
        assertNull(template["hostingMode"])
        assertNull(template["targetSessions"])
        assertNull(template["serviceWeekdays"])
    }

    @Test
    fun `unknown legacy service mode aborts with an object level diagnostic`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table(
                "game_templates",
                listOf(
                    mapOf(
                        "id" to "gt1",
                        "gameName" to "游戏",
                        "templateVersion" to "1.0",
                        "zoneId" to "Asia/Shanghai",
                        "dailyRefreshMinutes" to 300,
                        "weeklyRefreshDay" to 1,
                        "weeklyRefreshMinutes" to 300,
                        "origin" to "USER",
                        "sortOrder" to 0,
                        "createdAt" to now,
                        "updatedAt" to now,
                        "serviceMode" to "monthly",
                    ),
                ),
                presentColumns = BackupFixtures.legacyColumns(
                    "game_templates",
                    setOf(
                        "id", "gameName", "templateVersion", "zoneId", "dailyRefreshMinutes",
                        "weeklyRefreshDay", "weeklyRefreshMinutes", "origin", "sortOrder",
                        "createdAt", "updatedAt", "serviceMode",
                    ),
                ),
            ),
        )
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("unknown legacy mode was accepted")
        } catch (error: BackupException) {
            assertTrue(error.message!!.contains("UNSUPPORTED_LEGACY_COLUMN_VALUE"))
            assertTrue(error.message!!.contains("templateId=gt1"))
        }
    }

    @Test
    fun `count target outside the allowed range aborts instead of downgrading to dates`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table(
                "game_templates",
                listOf(
                    mapOf(
                        "id" to "gt1",
                        "gameName" to "游戏",
                        "templateVersion" to "1.0",
                        "zoneId" to "Asia/Shanghai",
                        "dailyRefreshMinutes" to 300,
                        "weeklyRefreshDay" to 1,
                        "weeklyRefreshMinutes" to 300,
                        "origin" to "USER",
                        "sortOrder" to 0,
                        "createdAt" to now,
                        "updatedAt" to now,
                        "serviceMode" to "count",
                        "serviceCount" to 99999,
                    ),
                ),
                presentColumns = BackupFixtures.legacyColumns(
                    "game_templates",
                    setOf(
                        "id", "gameName", "templateVersion", "zoneId", "dailyRefreshMinutes",
                        "weeklyRefreshDay", "weeklyRefreshMinutes", "origin", "sortOrder",
                        "createdAt", "updatedAt", "serviceMode", "serviceCount",
                    ),
                ),
            ),
        )
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("out-of-range count was accepted")
        } catch (error: BackupException) {
            assertTrue(error.message!!.contains("COUNT_TARGET_MISSING"))
        }
    }

    @Test
    fun `schema 15 input is passed through without inventing sessions`() {
        val dataSet = BackupFixtures.build(
            table("owners", listOf(owner())),
            table("game_accounts", listOf(account())),
            table("orders", listOf(order("o1", "COUNT_BASED", 3))),
            table("hosting_periods", listOf(period("o1p1", "o1", 0, 3))),
            table("hosting_sessions", emptyList()),
        )
        val result = BackupNormalizer.normalize(dataSet, 15)
        assertEquals(false, result.legacy)
        assertEquals(0, result.dataSet.tableRows2("hosting_sessions").size)
        assertTrue(result.applied.isEmpty())
    }

    /** 行 → 便于断言的 map：INTEGER 列给 Long，TEXT 列给 String，NULL 给 null。 */
    private fun BackupDataSet.tableRows2(name: String): List<Map<String, Any?>> {
        val rows = checkNotNull(tableRows(name))
        val spec = rows.table
        return rows.rows.map { row ->
            spec.columns.mapIndexed { index, column ->
                val primitive = row.getOrNull(index) as? kotlinx.serialization.json.JsonPrimitive
                column.name to when {
                    // JsonNull 也是 JsonPrimitive：必须先判空值，否则会读成字符串 "null"。
                    primitive == null || primitive is kotlinx.serialization.json.JsonNull -> null
                    column.type == BackupTableCatalog.ColumnType.INTEGER -> primitive.content.toLongOrNull()
                    else -> primitive.content
                }
            }.toMap()
        }
    }
}
