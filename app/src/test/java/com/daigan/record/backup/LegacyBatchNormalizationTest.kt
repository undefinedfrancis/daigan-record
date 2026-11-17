package com.daigan.record.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 历史批次归一（S2-R4 / S2-R5，安卓侧）。
 *
 * 1. HOSTING_ALL 的 `targetId='all'` + 单一游戏日 → 规范的 null/null；
 * 2. 缺失的 `occurrenceId` 只在证据唯一时补链，歧义/无证据一律对象级中止；
 * 3. 已撤销的历史批次目标可以合法地不存在，但绝不臆造打卡记录；
 * 4. schema-15 输入按原样进入严格校验（不静默改写）。
 */
class LegacyBatchNormalizationTest {

    private val now = 1_760_000_000_000L

    private fun owner() = mapOf(
        "id" to "ow1",
        "number" to 1,
        "nickname" to "老板",
        "createdAt" to now,
        "updatedAt" to now,
    )

    private fun account() = mapOf(
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
    )

    private fun order(id: String, hostingMode: String? = "DATE_RANGE") = mapOf(
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
    )

    private fun period(id: String, orderId: String) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodIndex" to 0,
        "startDate" to "2026-02-10",
        "endDate" to "2026-02-10",
        "priceCents" to 30000L,
        "gameZoneId" to "Asia/Shanghai",
        "dailyRefreshMinutes" to 300,
        "weeklyRefreshDay" to 1,
        "weeklyRefreshMinutes" to 300,
        "createdAt" to now,
    )

    private fun task(id: String, orderId: String, periodId: String?) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodId" to periodId,
        "title" to "任务",
        "progressType" to "CHECKBOX",
        "startValue" to "0",
        "targetValue" to "1",
        "currentValue" to "0",
        "recurrence" to "DAILY",
        "sortOrder" to 0,
        "createdAt" to now,
        "updatedAt" to now,
    )

    private fun batch(
        id: String,
        scope: String,
        targetId: String?,
        gameDate: String?,
        undoneAt: Long? = null,
    ) = mapOf(
        "id" to id,
        "operationId" to "op-$id",
        "scope" to scope,
        "targetId" to targetId,
        "gameDate" to gameDate,
        "createdAt" to now,
        "undoneAt" to undoneAt,
    )

    private fun item(id: String, batchId: String, taskId: String?, occurrenceId: String?) = mapOf(
        "id" to id,
        "batchId" to batchId,
        "itemType" to if (occurrenceId != null || taskId != null) "OCCURRENCE" else "SHORT_TASK",
        "occurrenceId" to occurrenceId,
        "taskDefinitionId" to taskId,
        "appliedCompletedAt" to now,
        "appliedRecordedAt" to now,
        "appliedBackfilled" to 0,
        "appliedCompletionBatchId" to batchId,
    )

    private fun occurrence(
        id: String,
        taskId: String,
        key: String,
        gameDate: String,
        batchId: String?,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "occurrenceKey" to key,
        "gameDate" to gameDate,
        "completedAt" to now,
        "recordedAt" to now,
        "backfilled" to 0,
        "completionBatchId" to batchId,
        "createdAt" to now,
    )

    private fun build(vararg extra: BackupFixtures.TableInput): BackupDataSet = BackupFixtures.build(
        BackupFixtures.table("owners", listOf(owner())),
        BackupFixtures.table("game_accounts", listOf(account())),
        *extra,
    )

    private fun cell(dataSet: BackupDataSet, table: String, row: Int, column: String): String? {
        val spec = checkNotNull(BackupTableCatalog.table(table))
        val index = spec.columnNames.indexOf(column)
        val value: JsonElement? = dataSet.rows(table)[row].getOrNull(index)
        if (value == null || value is JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }

    @Test
    fun `hosting all sentinel and game date become canonical nulls`() {
        val dataSet = build(
            BackupFixtures.table("orders", listOf(order("o1"))),
            BackupFixtures.table("hosting_periods", listOf(period("p1", "o1"))),
            BackupFixtures.table("task_definitions", listOf(task("t1", "o1", "p1"))),
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ALL", "all", "2026-02-10")),
            ),
            BackupFixtures.table("completion_batch_items", listOf(item("i1", "b1", "t1", null))),
            BackupFixtures.table(
                "task_occurrences",
                listOf(occurrence("oc1", "t1", "D:2026-02-10", "2026-02-10", "b1")),
            ),
        )

        val result = BackupNormalizer.normalize(dataSet, 12)
        assertEquals(1, result.counts["hostingAllBatchesNormalized"])
        assertNull(cell(result.dataSet, "completion_batches", 0, "targetId"))
        assertNull(cell(result.dataSet, "completion_batches", 0, "gameDate"))
        assertEquals("op-b1", cell(result.dataSet, "completion_batches", 0, "operationId"))
        assertEquals(now.toString(), cell(result.dataSet, "completion_batches", 0, "createdAt"))
        // 链接修复仍然依赖保留下来的历史游戏日。
        assertEquals("oc1", cell(result.dataSet, "completion_batch_items", 0, "occurrenceId"))
    }

    @Test
    fun `per order game dates stay attached to their own occurrence`() {
        val dataSet = build(
            BackupFixtures.table("orders", listOf(order("o1"), order("o2"))),
            BackupFixtures.table(
                "hosting_periods",
                listOf(period("p1", "o1"), period("p2", "o2")),
            ),
            BackupFixtures.table(
                "task_definitions",
                listOf(task("t1", "o1", "p1"), task("t2", "o2", "p2")),
            ),
            // 单一历史游戏日 02-10，但 o2 的实际打卡在 02-11：不能按批次日期收窄。
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ALL", "all", "2026-02-10")),
            ),
            BackupFixtures.table(
                "completion_batch_items",
                listOf(item("i1", "b1", "t1", null), item("i2", "b1", "t2", null)),
            ),
            BackupFixtures.table(
                "task_occurrences",
                listOf(
                    occurrence("oc1", "t1", "D:2026-02-10", "2026-02-10", "b1"),
                    occurrence("oc2", "t2", "D:2026-02-11", "2026-02-11", "b1"),
                ),
            ),
        )

        val out = BackupNormalizer.normalize(dataSet, 12).dataSet
        val links = out.rows("completion_batch_items").associate { row ->
            val id = checkNotNull(BackupTableCatalog.table("completion_batch_items"))
            val idIndex = id.columnNames.indexOf("id")
            val occIndex = id.columnNames.indexOf("occurrenceId")
            (row[idIndex] as? JsonPrimitive)?.content to (row[occIndex] as? JsonPrimitive)?.content
        }
        assertEquals("oc1", links["i1"])
        assertEquals("oc2", links["i2"])
    }

    @Test
    fun `an unknown legacy target shape is rejected`() {
        val dataSet = build(
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ALL", "weird", "2026-02-10")),
            ),
        )
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("未知的 HOSTING_ALL 目标必须中止")
        } catch (error: BackupException) {
            assertTrue(error.message.orEmpty().contains("UNSUPPORTED_LEGACY_BATCH_SHAPE"))
        }
    }

    @Test
    fun `an ambiguous occurrence link is rejected and nothing is rewritten`() {
        val dataSet = build(
            BackupFixtures.table("orders", listOf(order("o1"))),
            BackupFixtures.table("hosting_periods", listOf(period("p1", "o1"))),
            BackupFixtures.table("task_definitions", listOf(task("t1", "o1", "p1"))),
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ALL", "all", "2026-02-10")),
            ),
            BackupFixtures.table("completion_batch_items", listOf(item("i1", "b1", "t1", null))),
            BackupFixtures.table(
                "task_occurrences",
                listOf(
                    occurrence("oc1", "t1", "D:2026-02-10", "2026-02-10", "b1"),
                    occurrence("oc2", "t1", "D:2026-02-11", "2026-02-11", "b1"),
                ),
            ),
        )
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("歧义的历史链接必须中止")
        } catch (error: BackupException) {
            assertTrue(error.message.orEmpty().contains("LEGACY_BATCH_LINK_AMBIGUOUS"))
        }
        // 归一与补链都是候选数据集内的操作：中止后原始数据未被改写。
        assertNull(cell(dataSet, "completion_batch_items", 0, "occurrenceId"))
        assertEquals("all", cell(dataSet, "completion_batches", 0, "targetId"))
    }

    @Test
    fun `an undone batch without a surviving occurrence keeps its history`() {
        val dataSet = build(
            BackupFixtures.table("orders", listOf(order("o1"))),
            BackupFixtures.table("hosting_periods", listOf(period("p1", "o1"))),
            BackupFixtures.table("task_definitions", listOf(task("t1", "o1", "p1"))),
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ORDER", "o1", "2026-02-10", undoneAt = now + 1)),
            ),
            BackupFixtures.table("completion_batch_items", listOf(item("i1", "b1", "t1", null))),
        )

        val out = BackupNormalizer.normalize(dataSet, 12).dataSet
        assertEquals(0, out.rows("task_occurrences").size)
        assertNull(cell(out, "completion_batch_items", 0, "occurrenceId"))
    }

    @Test
    fun `schema fifteen input is not silently normalized`() {
        val dataSet = build(
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ALL", "all", "2026-02-10")),
            ),
        )
        val out = BackupNormalizer.normalize(dataSet, 15).dataSet
        // 不静默改写：schema 15 输入原样进入严格校验（校验在恢复流程里执行）。
        assertEquals("all", cell(out, "completion_batches", 0, "targetId"))
        assertEquals("2026-02-10", cell(out, "completion_batches", 0, "gameDate"))
        assertEquals(false, BackupNormalizer.normalize(dataSet, 15).legacy)
    }

    // ------------------------------------------------------------ CD-01（无目标历史）

    private fun legacyUndoneBatch(
        undoneAt: Long?,
        taskId: String? = "t1",
        occurrenceId: String? = "oc-gone",
    ): BackupDataSet = build(
        BackupFixtures.table("orders", listOf(order("o1"))),
        BackupFixtures.table("hosting_periods", listOf(period("p1", "o1"))),
        BackupFixtures.table("task_definitions", listOf(task("t1", "o1", "p1"))),
        BackupFixtures.table(
            "completion_batches",
            listOf(batch("b1", "HOSTING_ORDER", "o1", "2026-02-10", undoneAt = undoneAt)),
        ),
        BackupFixtures.table(
            "completion_batch_items",
            listOf(item("i1", "b1", taskId, occurrenceId)),
        ),
    )

    @Test
    fun `an undone dangling target is rejected with the original id preserved`() {
        val dataSet = legacyUndoneBatch(
            undoneAt = now + 1,
            occurrenceId = "deleted-occurrence-original-123",
        )
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("已撤销批次的已知悬空目标必须中止（不改写为无目标历史）")
        } catch (error: BackupException) {
            val message = error.message.orEmpty()
            assertTrue(message, message.contains("UNDONE_BATCH_TARGET_DANGLING"))
            assertTrue(message, message.contains("deleted-occurrence-original-123"))
            assertTrue(message, message.contains("itemId=i1"))
            assertTrue(message, message.contains("batchId=b1"))
            assertTrue(message, message.contains("taskDefinitionId=t1"))
        }
        // 输入不被改写：原 id 仍在、不臆造打卡记录、不删明细。
        assertEquals(
            "deleted-occurrence-original-123",
            cell(dataSet, "completion_batch_items", 0, "occurrenceId"),
        )
        assertEquals(0, dataSet.rows("task_occurrences").size)
        assertEquals(1, dataSet.rows("completion_batch_items").size)
    }

    @Test
    fun `an active batch with a dangling target is rejected before activation`() {
        val dataSet = legacyUndoneBatch(undoneAt = null)
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("未撤销批次的悬空目标必须中止")
        } catch (error: BackupException) {
            assertTrue(error.message.orEmpty().contains("BATCH_TARGET_DANGLING"))
        }
        // 中止后原始数据保持原样（含悬空 id）。
        assertEquals("oc-gone", cell(dataSet, "completion_batch_items", 0, "occurrenceId"))
    }

    @Test
    fun `an undone dangling target without a task reference is still rejected`() {
        val dataSet = legacyUndoneBatch(undoneAt = now + 1, taskId = null)
        try {
            BackupNormalizer.normalize(dataSet, 12)
            fail("已知悬空 id 无论有没有任务引用都不能被改写")
        } catch (error: BackupException) {
            val message = error.message.orEmpty()
            assertTrue(message, message.contains("UNDONE_BATCH_TARGET_DANGLING"))
            assertTrue(message, message.contains("oc-gone"))
        }
        assertEquals("oc-gone", cell(dataSet, "completion_batch_items", 0, "occurrenceId"))
    }

    @Test
    fun `the null target audit applies to schema fifteen and is idempotent`() {
        val dataSet = legacyUndoneBatch(undoneAt = now + 1, occurrenceId = null)
        val once = BackupNormalizer.normalize(dataSet, 15)
        assertEquals(1, once.counts["undoneTargetsWithoutReference"])
        assertNull(cell(once.dataSet, "completion_batch_items", 0, "occurrenceId"))
        // 没有任何改写计数（这一步只审计，不改写 id）。
        assertNull(once.counts["undoneTargetsRewritten"])

        val twice = BackupNormalizer.normalize(once.dataSet, 15)
        assertEquals(1, twice.counts["undoneTargetsWithoutReference"])
        assertNull(cell(twice.dataSet, "completion_batch_items", 0, "occurrenceId"))
    }

    @Test
    fun `a dangling target is not rewritten in any portable schema version`() {
        listOf(12, 15).forEach { schemaVersion ->
            val dataSet = legacyUndoneBatch(
                undoneAt = now + 1,
                occurrenceId = "deleted-occurrence-original-123",
            )
            try {
                BackupNormalizer.normalize(dataSet, schemaVersion)
                fail("schema $schemaVersion 的悬空目标必须中止")
            } catch (error: BackupException) {
                val message = error.message.orEmpty()
                assertTrue(message, message.contains("UNDONE_BATCH_TARGET_DANGLING"))
                assertTrue(message, message.contains("deleted-occurrence-original-123"))
            }
            assertEquals(
                "deleted-occurrence-original-123",
                cell(dataSet, "completion_batch_items", 0, "occurrenceId"),
            )
        }
    }

    @Test
    fun `an undone batch without a target keeps a truthful null target count`() {
        val dataSet = build(
            BackupFixtures.table("orders", listOf(order("o1"))),
            BackupFixtures.table("hosting_periods", listOf(period("p1", "o1"))),
            BackupFixtures.table("task_definitions", listOf(task("t1", "o1", "p1"))),
            BackupFixtures.table(
                "completion_batches",
                listOf(batch("b1", "HOSTING_ORDER", "o1", "2026-02-10", undoneAt = now + 1)),
            ),
            BackupFixtures.table("completion_batch_items", listOf(item("i1", "b1", "t1", null))),
        )
        val result = BackupNormalizer.normalize(dataSet, 12)
        assertEquals(1, result.counts["undoneTargetsWithoutReference"])
        assertEquals(0, result.dataSet.rows("task_occurrences").size)
        assertNull(cell(result.dataSet, "completion_batch_items", 0, "occurrenceId"))
    }
}
