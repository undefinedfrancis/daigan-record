package com.daigan.record.feature.orders

import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.testing.fixedInstant
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified editor lists every hosting period (UF-030 follow-up), so the phase shown on
 * each chip — and whether its tasks/service plan are locked — must come from that period's
 * own dates and refresh rules.
 */
class HostingPeriodSelectionTest {

    private val rules = GameRefreshRulesDomain(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    /** 12:00 in Shanghai, so the game date is the local date. */
    private val midday = Instant.parse("2026-10-17T04:00:00Z")

    /** 03:00 in Shanghai on 10-18: before the 04:00 refresh, so still game date 10-17. */
    private val beforeRefresh = Instant.parse("2026-10-17T19:00:00Z")

    private fun period(
        start: LocalDate,
        end: LocalDate,
        index: Int = 0,
    ) = HostingPeriod(
        id = "period-$index",
        orderId = "order-1",
        periodIndex = index,
        startDate = start,
        endDate = end,
        priceCents = 10000,
        refresh = rules,
        createdAt = fixedInstant,
        renewalOperationId = null,
        serviceDays = null,
        serviceWeekdays = emptySet(),
    )

    @Test
    fun `historical current and future periods are all selectable and labelled`() {
        val periods = listOf(
            period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), index = 0),
            period(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20), index = 1),
            period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10), index = 2),
        )

        assertEquals(
            listOf(PeriodPhase.HISTORICAL, PeriodPhase.CURRENT, PeriodPhase.FUTURE),
            periods.map { it.phaseAt(midday) },
        )
        assertEquals(
            listOf("历史期 · 第 1 期", "当前期 · 第 2 期", "未来期 · 第 3 期"),
            periods.map { it.pickerLabel(midday) },
        )
        assertTrue(periods[0].isHistoryLockedAt(midday))
        assertFalse(periods[1].isHistoryLockedAt(midday))
        assertFalse(periods[2].isHistoryLockedAt(midday))
    }

    @Test
    fun `phase uses the period refresh rules not the local date`() {
        val endingToday = period(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 17))
        val endedYesterday = period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16), index = 1)

        // Late on 10-18 locally but still game date 10-17: the period ending 10-17 is current.
        assertEquals(PeriodPhase.CURRENT, endingToday.phaseAt(beforeRefresh))
        assertEquals(PeriodPhase.HISTORICAL, endedYesterday.phaseAt(beforeRefresh))
    }

    @Test
    fun `an order whose periods all ended keeps every period offerable`() {
        val periods = listOf(
            period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10), index = 0),
            period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 10), index = 1),
        )

        assertTrue(periods.all { it.phaseAt(midday) == PeriodPhase.HISTORICAL })
        assertTrue(periods.all { it.isHistoryLockedAt(midday) })
        assertEquals(
            listOf("历史期 · 第 1 期", "历史期 · 第 2 期"),
            periods.map { it.pickerLabel(midday) },
        )
    }
}
