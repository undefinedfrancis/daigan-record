package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 累计事件（UF-038）：任务级隔离、scope 矩阵与归属、解析规则、更正链与撤回语义。
 *
 * 这些规则同时被恢复校验（`BackupValidator`）与写入命令使用，因此必须先在纯领域层钉住。
 */
class ProgressEventsTest {

    private val now = Instant.ofEpochMilli(1_760_000_000_000)

    private fun event(
        id: String,
        taskId: String = "t-count",
        scope: ProgressScope = ProgressScope.Session("s1"),
        kind: ProgressEventKind = ProgressEventKind.SUBMIT,
        gameDate: String = "2026-02-02",
        total: String? = "10",
        sequence: Int = 1,
        operationId: String = "op-$id",
        corrects: String? = null,
    ) = TaskProgressEvent(
        id = id,
        taskDefinitionId = taskId,
        operationId = operationId,
        kind = kind,
        scope = scope,
        gameDate = LocalDate.parse(gameDate),
        totalValue = total?.let(::BigDecimal),
        sequence = sequence,
        correctsEventId = corrects,
        recordedAt = now,
        createdAt = now,
    )

    /**
     * o1：计次托管（p1 / s1、s2）；o2：按日期托管（p2、p3）；o3：短期单（无期次）。
     */
    private fun context() = ProgressEventContext(
        scopeTypeByTask = mapOf(
            "t-count" to ProgressScopeType.SESSION,
            "t-count2" to ProgressScopeType.SESSION,
            "t-check" to ProgressScopeType.SESSION,
            "t-date" to ProgressScopeType.PERIOD,
            "t-short" to ProgressScopeType.TASK,
        ),
        // s9 是"按日期订单上却存在轮次行"的异常历史数据：用于验证 SESSION scope 的
        // 计次归属要求，而不是被订单不一致提前短路。
        sessionOrder = mapOf("s1" to "o1", "s2" to "o1", "s9" to "o2"),
        sessionPeriod = mapOf("s1" to "p1", "s2" to "p1", "s9" to "p2"),
        periodOrder = mapOf("p1" to "o1", "p2" to "o2", "p3" to "o2"),
        taskOrder = mapOf(
            "t-date2" to "o2",
            "t-count" to "o1",
            "t-count2" to "o1",
            "t-check" to "o1",
            "t-date" to "o2",
            "t-short" to "o3",
        ),
        taskPeriod = mapOf(
            "t-date2" to "p3",
            "t-count" to "p1",
            "t-count2" to "p1",
            "t-check" to "p1",
            "t-date" to "p2",
            "t-short" to null,
        ),
        cumulativeTaskIds = setOf("t-count", "t-count2", "t-date", "t-short"),
        countPeriodIds = setOf("p1"),
        periodStart = mapOf(
            "p1" to LocalDate.parse("2026-02-01"),
            "p2" to LocalDate.parse("2026-02-01"),
            "p3" to LocalDate.parse("2026-03-01"),
        ),
        periodEnd = mapOf(
            "p1" to LocalDate.parse("2026-02-28"),
            "p2" to LocalDate.parse("2026-02-28"),
            "p3" to LocalDate.parse("2026-03-31"),
        ),
        taskActiveFrom = emptyMap(),
        taskActiveUntil = emptyMap(),
    )

    @Test
    fun `scope matrix allows exactly one scope per order mode`() {
        assertEquals(ProgressScopeType.TASK, allowedScopeType(OrderType.SHORT_TASK, null))
        assertEquals(ProgressScopeType.PERIOD, allowedScopeType(OrderType.HOSTING, null))
        assertEquals(ProgressScopeType.PERIOD, allowedScopeType(OrderType.HOSTING, HostingMode.DATE_RANGE))
        assertEquals(ProgressScopeType.SESSION, allowedScopeType(OrderType.HOSTING, HostingMode.COUNT_BASED))
    }

    @Test
    fun `count task may not record a period observation`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", scope = ProgressScope.Period("p1"))),
            context(),
        )
        assertTrue(violations.any { it.code == "SCOPE_NOT_ALLOWED" })
    }

    @Test
    fun `session scope must belong to the same order and period`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", scope = ProgressScope.Session("s-missing"))),
            context(),
        )
        assertTrue(violations.any { it.code == "SCOPE_OWNERSHIP_INVALID" })
    }

    @Test
    fun `a valid chain has no violations`() {
        val events = listOf(
            event("e1", sequence = 1),
            event("e2", sequence = 2, kind = ProgressEventKind.CORRECT, corrects = "e1", total = "15"),
        )
        assertTrue(ProgressEventValidator.validate(events, context()).isEmpty())
    }

    @Test
    fun `correction must keep the attributed game date`() {
        val events = listOf(
            event("e1", sequence = 1),
            event(
                "e2",
                sequence = 2,
                kind = ProgressEventKind.CORRECT,
                corrects = "e1",
                total = "15",
                gameDate = "2026-02-03",
            ),
        )
        assertTrue(
            ProgressEventValidator.validate(events, context()).any { it.code == "CORRECTION_DATE_CHANGED" },
        )
    }

    @Test
    fun `a superseded event cannot be corrected twice`() {
        val events = listOf(
            event("e1", sequence = 1),
            event("e2", sequence = 2, kind = ProgressEventKind.CORRECT, corrects = "e1", total = "15"),
            event("e3", sequence = 3, kind = ProgressEventKind.CORRECT, corrects = "e1", total = "20"),
        )
        assertTrue(ProgressEventValidator.validate(events, context()).any { it.code == "SUPERSEDED_EVENT" })
    }

    @Test
    fun `correction cycles and impossible sequences are rejected`() {
        val cycle = listOf(
            event("a", sequence = 2, kind = ProgressEventKind.CORRECT, corrects = "b", total = "5"),
            event("b", sequence = 1, kind = ProgressEventKind.CORRECT, corrects = "a", total = "6"),
        )
        assertTrue(ProgressEventValidator.validate(cycle, context()).any { it.code == "CORRECTION_CYCLE" })

        val backwards = listOf(
            event("e1", sequence = 5),
            event("e2", sequence = 4, kind = ProgressEventKind.CORRECT, corrects = "e1", total = "9"),
        )
        assertTrue(
            ProgressEventValidator.validate(backwards, context()).any { it.code == "IMPOSSIBLE_SEQUENCE" },
        )
    }

    @Test
    fun `resolution takes the latest game date, then the highest sequence`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", sequence = 1, total = "10"),
            event("e2", sequence = 2, total = "15", gameDate = "2026-02-03"),
        )
        assertEquals(
            BigDecimal("15"),
            ProgressResolution.resolveScopeTotal(events, "t-count", scope, BigDecimal.ZERO),
        )
        assertEquals(
            BigDecimal("10"),
            ProgressResolution.resolveScopeTotal(
                events,
                "t-count",
                scope,
                BigDecimal.ZERO,
                LocalDate.parse("2026-02-02"),
            ),
        )
    }

    @Test
    fun `a retract removes the observation without resurrecting the earlier one`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", sequence = 1, total = "10"),
            event("e2", sequence = 2, total = "15", gameDate = "2026-02-03"),
            event(
                "e3",
                sequence = 3,
                kind = ProgressEventKind.RETRACT,
                corrects = "e2",
                total = null,
                gameDate = "2026-02-03",
            ),
        )
        assertEquals(
            BigDecimal("10"),
            ProgressResolution.resolveScopeTotal(events, "t-count", scope, BigDecimal.ZERO),
        )
    }

    @Test
    fun `resolution falls back to the start value when nothing is effective`() {
        val scope = ProgressScope.Period("p2")
        assertEquals(
            BigDecimal("2.5"),
            ProgressResolution.resolveScopeTotal(emptyList(), "t-date", scope, BigDecimal("2.5")),
        )
    }

    // ---------------------------------------------------------------- S2-R1

    @Test
    fun `S2-R1 two tasks in one session resolve independently`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", taskId = "t-count", total = "10"),
            event("e2", taskId = "t-count2", total = "20", gameDate = "2026-02-03"),
        )
        assertEquals(
            BigDecimal("10"),
            ProgressResolution.resolveScopeTotal(events, "t-count", scope, BigDecimal.ZERO),
        )
        assertEquals(
            BigDecimal("20"),
            ProgressResolution.resolveScopeTotal(events, "t-count2", scope, BigDecimal.ZERO),
        )
    }

    @Test
    fun `S2-R1 task scope is not dataset global`() {
        val events = listOf(
            event("e1", taskId = "t-short", scope = ProgressScope.Task, total = "3"),
            event("e2", taskId = "t-short2", scope = ProgressScope.Task, total = "9"),
        )
        assertEquals(
            BigDecimal("3"),
            ProgressResolution.resolveScopeTotal(events, "t-short", ProgressScope.Task, BigDecimal.ZERO),
        )
        assertEquals(
            BigDecimal("9"),
            ProgressResolution.resolveScopeTotal(events, "t-short2", ProgressScope.Task, BigDecimal.ZERO),
        )
    }

    @Test
    fun `S2-R1 period scope is isolated per task`() {
        val scope = ProgressScope.Period("p2")
        val events = listOf(
            event("e1", taskId = "t-date", scope = scope, total = "4"),
            event("e2", taskId = "t-date2", scope = scope, total = "8", gameDate = "2026-02-05"),
        )
        assertEquals(
            BigDecimal("4"),
            ProgressResolution.resolveScopeTotal(events, "t-date", scope, BigDecimal.ZERO),
        )
        assertEquals(
            BigDecimal("8"),
            ProgressResolution.resolveScopeTotal(events, "t-date2", scope, BigDecimal.ZERO),
        )
    }

    @Test
    fun `S2-R1 another task's correction or retraction never changes this task`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", taskId = "t-count", total = "10"),
            event("other-submit", taskId = "t-count2", total = "20"),
            event(
                "other-correct",
                taskId = "t-count2",
                sequence = 2,
                kind = ProgressEventKind.CORRECT,
                corrects = "other-submit",
                total = "25",
            ),
            event(
                "other-retract",
                taskId = "t-count2",
                sequence = 3,
                kind = ProgressEventKind.RETRACT,
                corrects = "other-correct",
                total = null,
            ),
        )
        assertEquals(
            BigDecimal("10"),
            ProgressResolution.resolveScopeTotal(events, "t-count", scope, BigDecimal.ZERO),
        )
    }

    @Test
    fun `S2-R1 same day ordering inside one task follows sequence`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", sequence = 1, total = "10"),
            event("e2", sequence = 2, total = "12"),
            event("e3", sequence = 3, total = "11", gameDate = "2026-02-03"),
        )
        assertEquals(
            BigDecimal("11"),
            ProgressResolution.resolveScopeTotal(events, "t-count", scope, BigDecimal.ZERO),
        )
        assertEquals(
            BigDecimal("12"),
            ProgressResolution.resolveScopeTotal(
                events,
                "t-count",
                scope,
                BigDecimal.ZERO,
                LocalDate.parse("2026-02-02"),
            ),
        )
    }

    @Test
    fun `S2-R1 historical query ignores later observations of the same task`() {
        val scope = ProgressScope.Session("s1")
        val events = listOf(
            event("e1", sequence = 1, total = "10", gameDate = "2026-02-02"),
            event("e2", sequence = 2, total = "30", gameDate = "2026-02-06"),
        )
        assertEquals(
            BigDecimal("10"),
            ProgressResolution.resolveScopeTotal(
                events,
                "t-count",
                scope,
                BigDecimal.ZERO,
                LocalDate.parse("2026-02-03"),
            ),
        )
    }

    // ---------------------------------------------------------------- S2-R2

    @Test
    fun `S2-R2 a same order wrong period scope is rejected`() {
        // t-date 属于 p2；事件却指向同订单的另一个按日期期次 p3。
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Period("p3"))),
            context(),
        )
        assertTrue(violations.any { it.code == "SCOPE_PERIOD_NOT_TASK_PERIOD" })
    }

    @Test
    fun `S2-R2 cross order period and session scopes are rejected`() {
        val crossPeriod = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Period("p1"))),
            context(),
        )
        assertTrue(crossPeriod.any { it.code == "SCOPE_OWNERSHIP_INVALID" })

        val crossSession = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Session("s1"))),
            context(),
        )
        assertTrue(crossSession.any { it.code == "SCOPE_OWNERSHIP_INVALID" })
    }

    @Test
    fun `S2-R2 checkbox and ordinary periodic tasks may not carry events`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-check")),
            context(),
        )
        assertTrue(violations.any { it.code == "TASK_NOT_CUMULATIVE" })
    }

    @Test
    fun `S2-R2 task scope requires a periodless short order task`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-count", scope = ProgressScope.Task)),
            context(),
        )
        assertTrue(violations.any { it.code == "TASK_SCOPE_REQUIRES_PERIODLESS_TASK" })
        assertTrue(violations.any { it.code == "SCOPE_NOT_ALLOWED" })
    }

    @Test
    fun `S2-R2 session scope requires a count based period`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Session("s9"))),
            context(),
        )
        assertTrue(violations.any { it.code == "SCOPE_SESSION_NOT_COUNT_BASED" })
    }

    @Test
    fun `S2-R2 session scope must belong to the task period`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-count", scope = ProgressScope.Session("s2"))),
            context(),
        )
        assertTrue(violations.isEmpty())

        // t-date2 属于 p3，却指向同一订单里另一个期次的轮次 s9。
        val mismatched = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date2", scope = ProgressScope.Session("s9"))),
            context(),
        )
        assertTrue(mismatched.any { it.code == "SCOPE_SESSION_PERIOD_MISMATCH" })
    }

    @Test
    fun `S2-R2 period scope requires a date based period`() {
        val violations = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-count", scope = ProgressScope.Period("p1"))),
            context(),
        )
        assertTrue(violations.any { it.code == "SCOPE_PERIOD_NOT_DATE_BASED" })
    }

    @Test
    fun `S2-R2 event dates outside the service range are rejected`() {
        val beforeStart = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Period("p2"), gameDate = "2026-01-20")),
            context(),
        )
        assertTrue(beforeStart.any { it.code == "EVENT_DATE_OUT_OF_SERVICE_RANGE" })

        val afterEnd = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Period("p2"), gameDate = "2026-03-20")),
            context(),
        )
        assertTrue(afterEnd.any { it.code == "EVENT_DATE_OUT_OF_SERVICE_RANGE" })
    }

    @Test
    fun `S2-R2 valid historical records stay valid`() {
        // 计次轮次可以晚于名义结束日完成：上界只对按日期期次生效。
        val lateSession = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-count", scope = ProgressScope.Session("s1"), gameDate = "2026-04-10")),
            context(),
        )
        assertTrue(lateSession.isEmpty())

        val datePeriod = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-date", scope = ProgressScope.Period("p2"), gameDate = "2026-02-20")),
            context(),
        )
        assertTrue(datePeriod.isEmpty())

        val shortTask = ProgressEventValidator.validate(
            listOf(event("e1", taskId = "t-short", scope = ProgressScope.Task, gameDate = "2026-02-20")),
            context(),
        )
        assertTrue(shortTask.isEmpty())
    }
}
