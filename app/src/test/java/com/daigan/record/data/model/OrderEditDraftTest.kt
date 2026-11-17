package com.daigan.record.data.model

import com.daigan.record.testing.account
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.owner
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editor-side rules of the unified order editor (UF-030).
 *
 * The repository re-validates inside its single transaction; this test pins the same
 * rules in the draft so the editor cannot offer a save the repository would reject.
 */
class OrderEditDraftTest {

    private val today = LocalDate.of(2026, 10, 21)

    private fun refresh() = GameRefreshRulesDomain(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    private fun hostingOrder(mode: HostingMode = HostingMode.DATE_RANGE, sessions: Int? = null) = Order(
        id = "order-1",
        accountId = "account-1",
        type = OrderType.HOSTING,
        title = "日常托管",
        priceCents = null,
        paymentMode = PaymentMode.ONE_TIME,
        deadlineDate = null,
        notes = "备注",
        refresh = refresh(),
        createdAt = fixedInstant,
        completedAt = null,
        hostingMode = mode,
        targetSessions = sessions,
    )

    private fun period(
        id: String = "period-1",
        index: Int = 0,
        priceCents: Long = 10000,
        days: Int? = null,
        weekdays: Set<DayOfWeek> = emptySet(),
        start: LocalDate = today,
        end: LocalDate = today.plusDays(9),
    ) = HostingPeriod(
        id = id,
        orderId = "order-1",
        periodIndex = index,
        startDate = start,
        endDate = end,
        priceCents = priceCents,
        refresh = refresh(),
        createdAt = fixedInstant,
        renewalOperationId = null,
        serviceDays = days,
        serviceWeekdays = weekdays,
    )

    private fun task(id: String = "task-1", periodId: String = "period-1") = OrderTask(
        id = id,
        orderId = "order-1",
        periodId = periodId,
        title = "每日签到",
        progressType = ProgressType.CHECKBOX,
        startValue = BigDecimal.ZERO,
        targetValue = BigDecimal.ONE,
        currentValue = BigDecimal.ZERO,
        unit = null,
        recurrence = RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
    )

    private fun detail(
        order: Order = hostingOrder(),
        periods: List<HostingPeriod> = listOf(period()),
        tasks: List<OrderTask> = listOf(task()),
        occurrences: List<TaskOccurrence> = emptyList(),
        sessions: List<HostingSession> = emptyList(),
        receipts: List<Receipt> = emptyList(),
    ): OrderDetail = OrderDetail(
        order = order,
        owner = owner(),
        account = account(),
        tasks = tasks,
        periods = periods,
        occurrences = occurrences,
        attachments = emptyList(),
        receipts = receipts,
        receiptCorrections = emptyList(),
        today = today,
        now = fixedInstant,
        sessions = sessions,
    )

    @Test
    fun `editor prefills from the period it is showing`() {
        val detail = detail()
        val draft = OrderEditDraft.of(detail)

        assertEquals("period-1", draft.periodId)
        assertEquals(today, draft.startDate)
        assertEquals(today.plusDays(9), draft.endDate)
        assertEquals("100.00", draft.priceYuan)
        assertEquals(OrderType.HOSTING, draft.type)
        assertEquals("备注", draft.notes)
        assertEquals("task-1", draft.tasks.single().id)
        assertNull(draft.serviceDays)
        assertNotNull(draft.refreshOrNull)
        assertTrue(draft.validate(detail).isEmpty())
    }

    @Test
    fun `blank title and invalid price are rejected before saving`() {
        val detail = detail()
        val errors = OrderEditDraft.of(detail).copy(title = "   ", priceYuan = "abc").validate(detail)

        assertEquals("请填写订单名称", errors["title"])
        assertNotNull(errors["price"])
    }

    @Test
    fun `service plan end date must be the derived date`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)
        val detail = detail(periods = listOf(period(days = 5, weekdays = weekdays)))
        val draft = OrderEditDraft.of(detail).copy(
            serviceDays = 5,
            serviceWeekdays = weekdays,
            endDate = today.plusDays(3),
        )

        val errors = draft.validate(detail)
        assertTrue(errors.getValue("dates").contains("推算"))
    }

    @Test
    fun `refresh text must parse`() {
        val detail = detail()
        val errors = OrderEditDraft.of(detail).copy(dailyRefreshText = "25:99").validate(detail)

        assertEquals("每日刷新时间需为 HH:MM", errors["daily"])
    }

    @Test
    fun `count based target cannot be lower than completed rounds`() {
        val countBased = detail(
            order = hostingOrder(HostingMode.COUNT_BASED, sessions = 5),
            periods = listOf(period(start = today, end = today)),
            sessions = listOf(
                HostingSession("s1", "order-1", 1, fixedInstant, fixedInstant),
                HostingSession("s2", "order-1", 2, fixedInstant, fixedInstant),
            ),
        )
        val draft = OrderEditDraft.of(countBased).copy(targetSessions = 1)

        val errors = draft.validate(countBased)
        assertTrue(errors.getValue("targetSessions").contains("已完成 2 次"))
        // Raising the agreed count stays allowed: completed rounds are never removed.
        assertTrue(draft.copy(targetSessions = 5).validate(countBased).isEmpty())
    }

    @Test
    fun `ended period still allows a basic correction`() {
        val expired = detail(
            periods = listOf(
                period(start = LocalDate.of(2020, 1, 1), end = LocalDate.of(2020, 1, 3)),
            ),
        )
        val draft = OrderEditDraft.of(expired).copy(title = "历史名称纠正")

        // Nothing but the title changed: the ended period's plan and tasks are untouched,
        // so they must not block the correction.
        assertTrue(draft.validate(expired).isEmpty())
        assertEquals("历史名称纠正", draft.title)
    }

    @Test
    fun `edited task is still validated`() {
        val detail = detail()
        val draft = OrderEditDraft.of(detail).copy(
            tasks = listOf(TaskDraft(title = "", periodId = "period-1")),
        )

        assertTrue(draft.validate(detail).isNotEmpty())
    }

    @Test
    fun `selecting a historical period loads its own price dates and tasks`() {
        val historical = period(
            id = "p1", index = 0, priceCents = 10000,
            start = LocalDate.of(2026, 9, 1), end = LocalDate.of(2026, 9, 10),
        )
        val current = period(id = "p2", index = 1, priceCents = 20000)
        val future = period(
            id = "p3", index = 2, priceCents = 30000,
            start = today.plusDays(20), end = today.plusDays(30),
        )
        val detail = detail(
            periods = listOf(historical, current, future),
            tasks = listOf(
                task(id = "t1", periodId = "p1"),
                task(id = "t2", periodId = "p2"),
                task(id = "t3", periodId = "p3"),
            ),
        )
        // The editor opens on the current period, but any period is selectable.
        assertEquals("p2", OrderEditDraft.of(detail).periodId)

        val selected = OrderEditDraft.of(detail).copy(title = "改名").withPeriod(detail, "p1")

        assertEquals("p1", selected.periodId)
        assertEquals(historical.startDate, selected.startDate)
        assertEquals(historical.endDate, selected.endDate)
        assertEquals(10000L, selected.priceCentsOrNull)
        assertEquals(listOf("t1"), selected.tasks.mapNotNull { it.id })
        assertEquals("改名", selected.title)
        assertTrue(selected.validate(detail).isEmpty())
    }

    @Test
    fun `all periods ended still allows a price correction on any period`() {
        val first = period(
            id = "p1", index = 0, priceCents = 10000,
            start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 1, 10),
        )
        val second = period(
            id = "p2", index = 1, priceCents = 20000,
            start = LocalDate.of(2026, 2, 1), end = LocalDate.of(2026, 2, 10),
        )
        val detail = detail(
            periods = listOf(first, second),
            tasks = listOf(task(id = "t1", periodId = "p1"), task(id = "t2", periodId = "p2")),
        )

        val draft = OrderEditDraft.of(detail).withPeriod(detail, "p2").copy(priceYuan = "150.00")

        assertEquals("p2", draft.periodId)
        assertEquals(15000L, draft.priceCentsOrNull)
        assertTrue(draft.validate(detail).isEmpty())
    }

    @Test
    fun `short order keeps an optional deadline and the whole-order price`() {
        val shortOrder = hostingOrder().copy(
            type = OrderType.SHORT_TASK,
            priceCents = 5000,
            deadlineDate = null,
        )
        val shortDetail = detail(order = shortOrder, periods = emptyList(), tasks = listOf(task().copy(periodId = null)))
        val draft = OrderEditDraft.of(shortDetail)

        assertNull(draft.periodId)
        assertNull(draft.deadlineDate)
        assertEquals("50.00", draft.priceYuan)
        assertTrue(draft.validate(shortDetail).isEmpty())
    }
}
