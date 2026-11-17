package com.daigan.record.backup

import com.daigan.record.data.model.ProgressScopeType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复期派生状态重建（S2-R3，安卓侧）。
 *
 * 与网页 `src/domain/derivedState.ts` 是同一套规则：事件是累计进度的权威，occurrence 历史
 * 是普通任务完成的权威，重建只写轮次完成时刻、累计任务缓存与订单完成标志。
 */
class DerivedStateReconstructionTest {

    private val now = 1_760_000_000_000L
    private val today = "2026-02-10"

    // ------------------------------------------------------------------ fixtures

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

    private fun order(
        id: String = "o1",
        hostingMode: String? = "COUNT_BASED",
        targetSessions: Int? = 2,
        completedAt: Long? = null,
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
        "completedAt" to completedAt,
        "hostingMode" to hostingMode,
        "targetSessions" to targetSessions,
    )

    private fun period(
        id: String = "p1",
        orderId: String = "o1",
        periodIndex: Int = 0,
        serviceCount: Int? = 2,
        startDate: String = today,
    ) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodIndex" to periodIndex,
        "startDate" to startDate,
        "endDate" to startDate,
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
        periodId: String? = "p1",
        orderId: String = "o1",
        progressType: String = "NUMERIC",
        numericMode: String? = "CUMULATIVE",
        targetValue: String = "20",
        currentValue: String = "0",
        completedAt: Long? = null,
        activeFrom: String? = null,
    ) = mapOf(
        "id" to id,
        "orderId" to orderId,
        "periodId" to periodId,
        "title" to "任务$id",
        "progressType" to progressType,
        "startValue" to "0",
        "targetValue" to targetValue,
        "currentValue" to currentValue,
        "recurrence" to "DAILY",
        "sortOrder" to 0,
        "completedAt" to completedAt,
        "createdAt" to now,
        "updatedAt" to now,
        "activeFrom" to activeFrom,
        "numericMode" to numericMode,
    )

    private fun session(
        id: String,
        index: Int = 1,
        periodId: String = "p1",
        completedAt: Long? = null,
    ) = mapOf(
        "id" to id,
        "orderId" to "o1",
        "sessionIndex" to index,
        "createdAt" to now,
        "completedAt" to completedAt,
        "periodId" to periodId,
    )

    private fun event(
        id: String,
        taskId: String,
        scopeSessionId: String = "s1",
        total: String? = "20",
        sequence: Int = 1,
        kind: String = "SUBMIT",
        corrects: String? = null,
        gameDate: String = today,
        recordedAt: Long = now,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "operationId" to "op-$id",
        "kind" to kind,
        "scopeType" to ProgressScopeType.SESSION.name,
        "scopeSessionId" to scopeSessionId,
        "gameDate" to gameDate,
        "totalValue" to total,
        "sequence" to sequence,
        "correctsEventId" to corrects,
        "recordedAt" to recordedAt,
        "createdAt" to now,
    )

    private fun occurrence(
        id: String,
        taskId: String,
        key: String = "N:1",
        completedAt: Long? = now,
        value: String? = null,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "occurrenceKey" to key,
        "gameDate" to today,
        "completedAt" to completedAt,
        "recordedAt" to completedAt,
        "backfilled" to 0,
        "completionBatchId" to null,
        "value" to value,
        "createdAt" to now,
    )

    private fun build(
        orders: List<Map<String, Any?>> = listOf(order()),
        periods: List<Map<String, Any?>> = listOf(period()),
        tasks: List<Map<String, Any?>>,
        sessions: List<Map<String, Any?>> = emptyList(),
        events: List<Map<String, Any?>> = emptyList(),
        occurrences: List<Map<String, Any?>> = emptyList(),
    ): BackupDataSet = BackupFixtures.build(
        BackupFixtures.table("owners", listOf(owner())),
        BackupFixtures.table("game_accounts", listOf(account())),
        BackupFixtures.table("orders", orders),
        BackupFixtures.table("hosting_periods", periods),
        BackupFixtures.table("task_definitions", tasks),
        BackupFixtures.table("hosting_sessions", sessions),
        BackupFixtures.table("task_progress_events", events),
        BackupFixtures.table("task_occurrences", occurrences),
    )

    private fun cell(dataSet: BackupDataSet, table: String, row: Int, column: String): String? {
        val spec = checkNotNull(BackupTableCatalog.table(table))
        val index = spec.columnNames.indexOf(column)
        val value: JsonElement? = dataSet.rows(table)[row].getOrNull(index)
        if (value == null || value is JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }

    private fun rowOf(dataSet: BackupDataSet, table: String, id: String): Int {
        val rows = dataSet.rows(table)
        val spec = checkNotNull(BackupTableCatalog.table(table))
        val idIndex = spec.columnNames.indexOf("id")
        val index = rows.indexOfFirst { (it[idIndex] as? JsonPrimitive)?.content == id }
        check(index >= 0) { "缺少行 $table/$id" }
        return index
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `stale caches rounds and order flags are rebuilt from authoritative rows`() {
        val dataSet = build(
            tasks = listOf(
                task("t1", currentValue = "3"),
                task("t2", progressType = "CHECKBOX", numericMode = null, targetValue = "1"),
            ),
            sessions = listOf(session("s1", completedAt = now + 500)),
            events = listOf(event("e1", "t1", total = "20", recordedAt = now)),
            occurrences = emptyList(),
        )

        val result = DerivedStateReconstructor.reconstruct(dataSet)
        val out = result.dataSet

        // 应做集里有勾选任务却没有打卡证据 → 轮次必须被重新打开。
        assertEquals(1, result.report.sessionsReopened)
        assertEquals(0, result.report.sessionsCompleted)
        assertNull(cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s1"), "completedAt"))
        // 活动轮次仍是 s1，缓存必须等于事件解析值。
        assertEquals("20", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertEquals(0, result.report.orderFlagsChanged)
    }

    @Test
    fun `two cumulative tasks in one session are reconstructed independently`() {
        val dataSet = build(
            orders = listOf(order(targetSessions = 1)),
            periods = listOf(period(serviceCount = 1)),
            tasks = listOf(
                task("t1", targetValue = "10", currentValue = "9"),
                task("t2", targetValue = "20", currentValue = "9"),
            ),
            sessions = listOf(session("s1")),
            events = listOf(
                event("e1", "t1", total = "10"),
                event("e2", "t2", total = "20", recordedAt = now + 120_000),
            ),
        )

        val result = DerivedStateReconstructor.reconstruct(dataSet)
        val out = result.dataSet
        assertEquals("10", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertEquals("20", cell(out, "task_definitions", rowOf(out, "task_definitions", "t2"), "currentValue"))
        assertEquals(1, result.report.sessionsCompleted)
        assertEquals(
            (now + 120_000).toString(),
            cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s1"), "completedAt"),
        )
    }

    @Test
    fun `a pending next round starts from the start value`() {
        val dataSet = build(
            orders = listOf(order(targetSessions = 3)),
            periods = listOf(period(serviceCount = 3)),
            tasks = listOf(task("t1")),
            sessions = listOf(
                session("s1", index = 1, completedAt = now),
                session("s2", index = 2),
            ),
            events = listOf(event("e1", "t1", total = "20")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("0", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertEquals(
            now.toString(),
            cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s1"), "completedAt"),
        )
        assertNull(cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s2"), "completedAt"))
    }

    @Test
    fun `a historical retraction reopens the earlier round`() {
        val dataSet = build(
            orders = listOf(order(targetSessions = 1)),
            periods = listOf(period(serviceCount = 1)),
            tasks = listOf(task("t1")),
            sessions = listOf(session("s1", completedAt = now)),
            events = listOf(
                event("e1", "t1", total = "20"),
                event("e2", "t1", total = null, sequence = 2, kind = "RETRACT", corrects = "e1"),
            ),
        )

        val result = DerivedStateReconstructor.reconstruct(dataSet)
        val out = result.dataSet
        assertNull(cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s1"), "completedAt"))
        assertEquals("0", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertEquals(1, result.report.sessionsReopened)
    }

    @Test
    fun `a task that is not yet active does not enter the due set`() {
        val dataSet = build(
            tasks = listOf(
                task("t1"),
                task("t2", activeFrom = "2999-01-01"),
            ),
            sessions = listOf(session("s1")),
            events = listOf(event("e1", "t1", total = "20")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals(
            now.toString(),
            cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s1"), "completedAt"),
        )
    }

    @Test
    fun `empty and partial due sets are never marked complete`() {
        val dataSet = build(
            tasks = listOf(
                task("t1"),
                task("t2", progressType = "CHECKBOX", numericMode = null, targetValue = "1"),
            ),
            sessions = listOf(
                session("s-empty", index = 1, completedAt = now),
                session("s-partial", index = 2, completedAt = now),
            ),
            events = listOf(event("e1", "t1", scopeSessionId = "s-partial", total = "20")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertNull(cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s-empty"), "completedAt"))
        assertNull(cell(out, "hosting_sessions", rowOf(out, "hosting_sessions", "s-partial"), "completedAt"))
    }

    @Test
    fun `ordinary occurrence history and task state are preserved`() {
        val dataSet = build(
            tasks = listOf(
                task("t1"),
                task("t2", progressType = "CHECKBOX", numericMode = null, targetValue = "1", completedAt = now - 5),
            ),
            sessions = listOf(session("s1")),
            events = listOf(event("e1", "t1", total = "20")),
            occurrences = listOf(occurrence("oc1", "t2", completedAt = now - 60_000)),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals(
            (now - 60_000).toString(),
            cell(out, "task_occurrences", rowOf(out, "task_occurrences", "oc1"), "completedAt"),
        )
        // 普通任务（非累计）的完成态是权威历史，重建不得改写。
        assertEquals(
            (now - 5).toString(),
            cell(out, "task_definitions", rowOf(out, "task_definitions", "t2"), "completedAt"),
        )
        assertEquals(out.rows("task_occurrences").size, 1)
    }

    @Test
    fun `order completion is derived only from every period target being met`() {
        val dataSet = build(
            orders = listOf(order(targetSessions = 2, completedAt = now + 999)),
            periods = listOf(period(serviceCount = 2)),
            tasks = listOf(task("t1")),
            sessions = listOf(session("s1", completedAt = now)),
            events = listOf(event("e1", "t1", total = "20")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `reconstruction is idempotent`() {
        val dataSet = build(
            orders = listOf(order(targetSessions = 1)),
            periods = listOf(period(serviceCount = 1)),
            tasks = listOf(
                task("t1"),
                task("t2", progressType = "CHECKBOX", numericMode = null, targetValue = "1"),
            ),
            sessions = listOf(session("s1")),
            events = listOf(event("e1", "t1", total = "20")),
            occurrences = listOf(occurrence("oc1", "t2")),
        )

        val snapshot: (BackupDataSet) -> String = { data ->
            listOf("hosting_sessions", "task_definitions", "orders").joinToString("|") { table ->
                val spec = checkNotNull(BackupTableCatalog.table(table))
                val idIndex = spec.columnNames.indexOf("id")
                val valueIndex = spec.columnNames.indexOf(
                    if (table == "orders") "completedAt" else if (table == "hosting_sessions") "completedAt" else "currentValue",
                )
                data.rows(table).joinToString(",") { row ->
                    val id = (row[idIndex] as? JsonPrimitive)?.content
                    val value = row.getOrNull(valueIndex)
                    "$table:$id=$value"
                }
            }
        }

        val first = DerivedStateReconstructor.reconstruct(dataSet)
        val once = snapshot(first.dataSet)
        val second = DerivedStateReconstructor.reconstruct(first.dataSet)
        assertEquals(once, snapshot(second.dataSet))
        assertEquals(1, first.report.sessionsCompleted)
        assertEquals(0, second.report.sessionsCompleted)
        assertEquals(0, second.report.sessionsReopened)
    }

    @Test
    fun `non count orders keep their scope caches from events`() {
        // 按日期托管：PERIOD scope 的累计缓存由事件重建，普通任务不动。
        val dataSet = build(
            orders = listOf(
                order(hostingMode = "DATE_RANGE", targetSessions = null),
            ),
            periods = listOf(period(serviceCount = null)),
            tasks = listOf(task("t1", currentValue = "1")),
            events = listOf(
                mapOf(
                    "id" to "e1",
                    "taskDefinitionId" to "t1",
                    "operationId" to "op-e1",
                    "kind" to "SUBMIT",
                    "scopeType" to ProgressScopeType.PERIOD.name,
                    "scopePeriodId" to "p1",
                    "gameDate" to today,
                    "totalValue" to "7",
                    "sequence" to 1,
                    "recordedAt" to now,
                    "createdAt" to now,
                ),
            ),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("7", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
    }

    // ---------------------------------------------------- 非轮次 scope（TASK / PERIOD）

    private fun shortOrder(completedAt: Long? = null) = mapOf(
        "id" to "o1",
        "accountId" to "ac1",
        "type" to "SHORT_TASK",
        "title" to "短期单",
        "priceCents" to 30000L,
        "paymentMode" to "INSTALLMENTS",
        "gameZoneId" to "Asia/Shanghai",
        "dailyRefreshMinutes" to 300,
        "weeklyRefreshDay" to 1,
        "weeklyRefreshMinutes" to 300,
        "createdAt" to now,
        "updatedAt" to now,
        "completedAt" to completedAt,
        "hostingMode" to null,
        "targetSessions" to null,
    )

    private fun taskScopeEvent(
        id: String,
        taskId: String,
        total: String? = "20",
        sequence: Int = 1,
        kind: String = "SUBMIT",
        corrects: String? = null,
        recordedAt: Long = now,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "operationId" to "op-$id",
        "kind" to kind,
        "scopeType" to ProgressScopeType.TASK.name,
        "scopePeriodId" to null,
        "scopeSessionId" to null,
        "gameDate" to today,
        "totalValue" to total,
        "sequence" to sequence,
        "correctsEventId" to corrects,
        "recordedAt" to recordedAt,
        "createdAt" to now,
    )

    private fun periodScopeEvent(
        id: String,
        taskId: String,
        periodId: String = "p1",
        total: String? = "7",
        sequence: Int = 1,
        recordedAt: Long = now,
    ) = mapOf(
        "id" to id,
        "taskDefinitionId" to taskId,
        "operationId" to "op-$id",
        "kind" to "SUBMIT",
        "scopeType" to ProgressScopeType.PERIOD.name,
        "scopePeriodId" to periodId,
        "scopeSessionId" to null,
        "gameDate" to today,
        "totalValue" to total,
        "sequence" to sequence,
        "correctsEventId" to null,
        "recordedAt" to recordedAt,
        "createdAt" to now,
    )

    @Test
    fun `a short cumulative task reaching its target derives its completion from the event`() {
        val dataSet = build(
            orders = listOf(shortOrder()),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null, currentValue = "999")),
            events = listOf(taskScopeEvent("e1", "t1", total = "20", recordedAt = now + 60_000)),
        )

        val result = DerivedStateReconstructor.reconstruct(dataSet)
        val out = result.dataSet
        assertEquals("20", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertEquals(
            (now + 60_000).toString(),
            cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"),
        )
        assertEquals(
            (now + 60_000).toString(),
            cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"),
        )
        assertTrue(result.report.taskCompletionsChanged > 0)
        assertEquals(1, result.report.orderFlagsChanged)
    }

    @Test
    fun `a short cumulative task below target clears a stale completion`() {
        val dataSet = build(
            orders = listOf(shortOrder(completedAt = now + 5)),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null, completedAt = now + 5)),
            events = listOf(taskScopeEvent("e1", "t1", total = "5")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("5", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `a zero event short cumulative task resets stale cache and completion`() {
        val dataSet = build(
            orders = listOf(shortOrder(completedAt = now + 100)),
            periods = emptyList(),
            tasks = listOf(
                task("t1", periodId = null, currentValue = "999", completedAt = now + 100),
            ),
            events = emptyList(),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("0", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `retracting the only effective short observation reopens task and order`() {
        val dataSet = build(
            orders = listOf(shortOrder(completedAt = now)),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null, completedAt = now)),
            events = listOf(
                taskScopeEvent("e1", "t1", total = "20"),
                taskScopeEvent("e2", "t1", total = null, sequence = 2, kind = "RETRACT", corrects = "e1"),
            ),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("0", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
        assertEquals(2, out.rows("task_progress_events").size)
    }

    @Test
    fun `correcting below target reopens a completed short task`() {
        val dataSet = build(
            orders = listOf(shortOrder(completedAt = now + 60_000)),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null, completedAt = now + 60_000)),
            events = listOf(
                taskScopeEvent("e1", "t1", total = "20", recordedAt = now + 60_000),
                taskScopeEvent(
                    "e2", "t1", total = "5", sequence = 2, kind = "CORRECT",
                    corrects = "e1", recordedAt = now + 90_000,
                ),
            ),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("5", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `several short tasks including an ordinary one decide order completion`() {
        val ordinary = task(
            "t2", periodId = null, progressType = "CHECKBOX", numericMode = null,
            targetValue = "1", currentValue = "1", completedAt = now - 1_000,
        )
        val reached = build(
            orders = listOf(shortOrder()),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null), ordinary),
            events = listOf(taskScopeEvent("e1", "t1", total = "20", recordedAt = now + 60_000)),
        )

        val first = DerivedStateReconstructor.reconstruct(reached).dataSet
        assertEquals(
            (now - 1_000).toString(),
            cell(first, "task_definitions", rowOf(first, "task_definitions", "t2"), "completedAt"),
        )
        assertEquals(
            (now + 60_000).toString(),
            cell(first, "orders", rowOf(first, "orders", "o1"), "completedAt"),
        )

        val retracted = build(
            orders = listOf(shortOrder(completedAt = now + 60_000)),
            periods = emptyList(),
            tasks = listOf(
                task("t1", periodId = null, completedAt = now + 60_000),
                ordinary,
            ),
            events = listOf(
                taskScopeEvent("e1", "t1", total = "20", recordedAt = now + 60_000),
                taskScopeEvent("e2", "t1", total = null, sequence = 2, kind = "RETRACT", corrects = "e1"),
            ),
        )

        val second = DerivedStateReconstructor.reconstruct(retracted).dataSet
        assertEquals(
            (now - 1_000).toString(),
            cell(second, "task_definitions", rowOf(second, "task_definitions", "t2"), "completedAt"),
        )
        assertNull(cell(second, "task_definitions", rowOf(second, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(second, "orders", rowOf(second, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `a date based period cumulative task rebuilds its period total and keeps no task completion`() {
        val dataSet = build(
            orders = listOf(order(hostingMode = "DATE_RANGE", targetSessions = null)),
            periods = listOf(period(serviceCount = null)),
            tasks = listOf(task("t1", currentValue = "1", completedAt = now + 7)),
            events = listOf(periodScopeEvent("e1", "t1", total = "7")),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("7", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        // 托管任务的存储规则要求任务级完成时间为 null（完成度由期次总值派生）。
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertEquals(0, out.rows("hosting_sessions").size)
    }

    @Test
    fun `a date based period cumulative task without events resets the stale cache`() {
        val dataSet = build(
            orders = listOf(order(hostingMode = "DATE_RANGE", targetSessions = null)),
            periods = listOf(period(serviceCount = null)),
            tasks = listOf(task("t1", currentValue = "999", completedAt = now + 9)),
            events = emptyList(),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("0", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertEquals(0, out.rows("hosting_sessions").size)
    }

@Test
    fun `a non positive observation instant never becomes a completion evidence`() {
        val dataSet = build(
            orders = listOf(shortOrder()),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null)),
            events = listOf(taskScopeEvent("e1", "t1", total = "20", recordedAt = 0)),
        )

        val out = DerivedStateReconstructor.reconstruct(dataSet).dataSet
        assertEquals("20", cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "currentValue"))
        // 0 不是可用时刻：既不写 0（安卓校验要求正数），也不编造恢复时刻。
        assertNull(cell(out, "task_definitions", rowOf(out, "task_definitions", "t1"), "completedAt"))
        assertNull(cell(out, "orders", rowOf(out, "orders", "o1"), "completedAt"))
    }

    @Test
    fun `repeated reconstruction keeps stored evidence timestamps stable`() {
        val dataSet = build(
            orders = listOf(shortOrder()),
            periods = emptyList(),
            tasks = listOf(task("t1", periodId = null)),
            events = listOf(taskScopeEvent("e1", "t1", total = "20", recordedAt = now + 60_000)),
        )

        val first = DerivedStateReconstructor.reconstruct(dataSet)
        val firstTask = cell(first.dataSet, "task_definitions", 0, "completedAt")
        val firstOrder = cell(first.dataSet, "orders", 0, "completedAt")
        val second = DerivedStateReconstructor.reconstruct(first.dataSet)
        assertEquals(firstTask, cell(second.dataSet, "task_definitions", 0, "completedAt"))
        assertEquals(firstOrder, cell(second.dataSet, "orders", 0, "completedAt"))
        assertEquals(0, second.report.taskCompletionsChanged)
        assertEquals(0, second.report.orderFlagsChanged)
        assertEquals(1, second.dataSet.rows("task_progress_events").size)
    }
}
