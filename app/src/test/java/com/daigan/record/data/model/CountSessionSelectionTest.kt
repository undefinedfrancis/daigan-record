package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 / G1：选择（期次局部）与分配（订单全局）分离、四种活动状态、五种缓存状态。
 *
 * 这里的 `sessionIndex` 一律是**订单全局**号，期次目标只用于判满。
 */
class CountSessionSelectionTest {

    private val now = Instant.ofEpochMilli(1_760_000_000_000)

    private fun session(
        id: String,
        index: Int,
        periodId: String,
        completed: Boolean = false,
        orderId: String = "o1",
    ) = HostingSession(
        id = id,
        orderId = orderId,
        sessionIndex = index,
        createdAt = now,
        completedAt = if (completed) now.plusSeconds(3600) else null,
        periodId = periodId,
    )

    private fun period(id: String, index: Int, serviceCount: Int?, orderId: String = "o1") = HostingPeriod(
        id = id,
        orderId = orderId,
        periodIndex = index,
        startDate = LocalDate.parse("2026-02-01"),
        endDate = LocalDate.parse("2026-02-28"),
        priceCents = 30000,
        refresh = GameRefreshRulesDomain(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(5, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        ),
        createdAt = now,
        renewalOperationId = null,
        serviceCount = serviceCount,
    )

    private fun task(
        id: String = "t1",
        periodId: String? = "p1",
        start: String = "0",
        target: String = "50",
    ) = OrderTask(
        id = id,
        orderId = "o1",
        periodId = periodId,
        title = "累计",
        progressType = ProgressType.NUMERIC,
        startValue = BigDecimal(start),
        targetValue = BigDecimal(target),
        currentValue = BigDecimal(start),
        unit = null,
        recurrence = RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
        numericMode = NumericMode.CUMULATIVE,
    )

    @Test
    fun `a new index is the order maximum plus one, never the period maximum`() {
        // 全局 1 已完成、全局 5 存在于另一个期次：新号必须是 6，而不是"本期 2"。
        val sessions = listOf(
            session("s1", 1, "p1", completed = true),
            session("s5", 5, "p2", completed = true),
        )
        assertEquals(6, CountHosting.nextOrderGlobalSessionIndex(sessions))
        assertEquals(
            CountHosting.ActiveSessionChoice.AllocateNew(6),
            CountHosting.selectActiveSession(sessions, "p2", 3),
        )
    }

    @Test
    fun `an incomplete round is reused before the period is judged full`() {
        val sessions = listOf(
            session("s1", 1, "p1", completed = true),
            session("s2", 2, "p1", completed = false),
            session("s3", 3, "p1", completed = true),
        )
        assertEquals(
            CountHosting.ActiveSessionChoice.ReuseExisting(2),
            CountHosting.selectActiveSession(sessions, "p1", 3),
        )
    }

    @Test
    fun `a full period reports PeriodFull and an exhausted index space reports the error state`() {
        val full = listOf(
            session("s1", 1, "p1", completed = true),
            session("s2", 2, "p1", completed = true),
        )
        assertEquals(
            CountHosting.ActiveSessionChoice.PeriodFull,
            CountHosting.selectActiveSession(full, "p1", 2),
        )

        val exhausted = listOf(session("sMax", Int.MAX_VALUE, "p1", completed = true))
        assertEquals(
            CountHosting.ActiveSessionChoice.IndexSpaceExhausted,
            CountHosting.selectActiveSession(exhausted, "p1", 5),
        )
        assertNull(CountHosting.nextOrderGlobalSessionIndex(exhausted))
    }

    @Test
    fun `an unmet period whose row is not created yet becomes AwaitingSessionRow`() {
        val state = CountHosting.resolveActiveSession(
            "o1",
            listOf(period("p1", 0, 2)),
            listOf(session("s1", 1, "p1", completed = true)),
        )
        assertEquals(CountHosting.ActiveCountState.AwaitingSessionRow("p1", 2), state)
    }

    @Test
    fun `awaiting row cache takes the start value and never inherits the previous round`() {
        val periods = listOf(period("p1", 0, 2))
        val sessions = listOf(session("s1", 1, "p1", completed = true))
        val state = CountHosting.resolveActiveSession("o1", periods, sessions)
        val cache = CountHosting.taskScopeCache(task(start = "5"), state, periods, sessions, emptyList())
        assertTrue(cache is CountHosting.TaskScopeCache.AwaitingSessionRow)
        val awaiting = cache as CountHosting.TaskScopeCache.AwaitingSessionRow
        assertEquals(BigDecimal("5"), awaiting.currentValue)
        assertNull(awaiting.completedAt)
        assertEquals(false, awaiting.reachedTarget)
    }

    @Test
    fun `a start value that already meets the target is only reachedTarget, never completion`() {
        val periods = listOf(period("p1", 0, 2))
        val sessions = listOf(session("s1", 1, "p1", completed = true))
        val state = CountHosting.resolveActiveSession("o1", periods, sessions)
        val cache = CountHosting.taskScopeCache(task(start = "60"), state, periods, sessions, emptyList())
        val awaiting = cache as CountHosting.TaskScopeCache.AwaitingSessionRow
        assertEquals(true, awaiting.reachedTarget)
        assertNull(awaiting.completedAt)
    }

    @Test
    fun `all periods complete yields OrderTerminal and a later period terminal never points elsewhere`() {
        val periods = listOf(period("p1", 0, 1), period("p2", 1, 1))
        val sessions = listOf(
            session("s1", 1, "p1", completed = true),
            session("s2", 2, "p2", completed = true),
        )
        assertEquals(
            CountHosting.ActiveCountState.AllPeriodsComplete,
            CountHosting.resolveActiveSession("o1", periods, sessions),
        )
        val cache = CountHosting.taskScopeCache(task(periodId = "p1"), CountHosting.ActiveCountState.AllPeriodsComplete, periods, sessions, emptyList())
        assertTrue(cache is CountHosting.TaskScopeCache.OrderTerminal)

        // p2 尚未完成时，属于 p1 的任务降级为 TaskPeriodTerminal，并冻结在 p1 的最后一个范围。
        val notYet = listOf(
            session("s1", 1, "p1", completed = true),
            session("s2", 2, "p2", completed = false),
        )
        val state = CountHosting.resolveActiveSession("o1", periods, notYet)
        assertEquals(CountHosting.ActiveCountState.ActiveSession("p2", 2, "s2"), state)
        val terminal = CountHosting.taskScopeCache(task(periodId = "p1"), state, periods, notYet, emptyList())
        assertTrue(terminal is CountHosting.TaskScopeCache.TaskPeriodTerminal)
        assertEquals("s1", (terminal as CountHosting.TaskScopeCache.TaskPeriodTerminal).lastScope?.sessionId)
    }

    @Test
    fun `index exhaustion is an error state that still freezes the task period scope`() {
        val periods = listOf(period("p1", 0, 5))
        val sessions = listOf(session("sMax", Int.MAX_VALUE, "p1", completed = true))
        val state = CountHosting.resolveActiveSession("o1", periods, sessions)
        assertEquals(CountHosting.ActiveCountState.IndexSpaceExhausted("p1"), state)
        val cache = CountHosting.taskScopeCache(task(), state, periods, sessions, emptyList())
        assertTrue(cache is CountHosting.TaskScopeCache.IndexSpaceExhausted)
        assertEquals("sMax", (cache as CountHosting.TaskScopeCache.IndexSpaceExhausted).lastScope?.sessionId)
    }
}
