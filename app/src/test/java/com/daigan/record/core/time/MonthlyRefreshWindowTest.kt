package com.daigan.record.core.time

import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-5: monthlyRefreshDays must drive real refresh-to-refresh windows. A day that does
 * not exist in a month is skipped, never clamped; the *fixed-day monthly task* recurrence
 * (`M:<date>`) is a different concept and stays untouched.
 */
class MonthlyRefreshWindowTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun rules(days: Set<Int>) = GameRefreshRules(
        zoneId = shanghai,
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
        monthlyRefreshDays = days,
    )

    private fun at(date: String, time: String): Instant =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(shanghai).toInstant()

    @Test
    fun monthlyWindowRunsRefreshToRefresh() {
        val window = GameCalendar.refreshWindow(at("2026-01-20", "12:00"), rules(setOf(1, 15)))

        assertEquals(LocalDate.parse("2026-01-15"), window.startDate)
        assertEquals(LocalDate.parse("2026-01-31"), window.endDate)
        assertEquals(at("2026-02-01", "05:00"), window.endExclusiveInstant)
    }

    @Test
    fun beforeTheMonthlyRefreshInstantThePreviousWindowStillApplies() {
        val window = GameCalendar.refreshWindow(at("2026-02-01", "04:30"), rules(setOf(1, 15)))

        assertEquals(LocalDate.parse("2026-01-15"), window.startDate)
        assertEquals(at("2026-02-01", "05:00"), window.endExclusiveInstant)
    }

    @Test
    fun multipleRefreshDaysProduceOneWindowPerBoundary() {
        val first = GameCalendar.refreshWindow(at("2026-03-10", "12:00"), rules(setOf(1, 10, 20)))
        val second = GameCalendar.refreshWindow(at("2026-03-21", "12:00"), rules(setOf(1, 10, 20)))

        assertEquals(LocalDate.parse("2026-03-10"), first.startDate)
        assertEquals(LocalDate.parse("2026-03-19"), first.endDate)
        assertEquals(LocalDate.parse("2026-03-20"), second.startDate)
        assertEquals(LocalDate.parse("2026-03-31"), second.endDate)
    }

    @Test
    fun missingFebruaryDayIsSkippedNotClampedToMonthEnd() {
        val window = GameCalendar.refreshWindow(at("2026-02-15", "12:00"), rules(setOf(30)))

        // Jan 30 -> (February has no 30th) -> Mar 30. Never Feb 28.
        assertEquals(LocalDate.parse("2026-01-30"), window.startDate)
        assertEquals(at("2026-03-30", "05:00"), window.endExclusiveInstant)
        assertNotEquals(LocalDate.parse("2026-03-01"), window.endExclusiveInstant.atZone(shanghai).toLocalDate())
    }

    @Test
    fun gameDayTouchingAMonthlyBoundaryOwnsTwoWindows() {
        val windows = GameCalendar.refreshWindowsForGameDate(
            LocalDate.parse("2026-02-01"),
            rules(setOf(1, 15)),
        )

        assertEquals(2, windows.size)
        assertEquals(LocalDate.parse("2026-01-15"), windows.first().startDate)
        assertEquals(LocalDate.parse("2026-02-01"), windows.last().startDate)
    }

    @Test
    fun nextRefreshBoundaryIsTheEarlierOfDailyAndMonthly() {
        val boundary = GameCalendar.nextRefreshBoundary(at("2026-02-01", "03:00"), rules(setOf(1, 15)))

        // Daily refresh (04:00) comes before the monthly refresh (05:00).
        assertEquals(at("2026-02-01", "04:00"), boundary)
    }

    @Test
    fun weeklyRulesAreUnchangedWithoutMonthlyDays() {
        val window = GameCalendar.refreshWindow(at("2026-02-04", "12:00"), rules(emptySet()))

        // 2026-02-02 is a Monday; the weekly window starts there.
        assertEquals(LocalDate.parse("2026-02-02"), window.startDate)
        assertEquals(LocalDate.parse("2026-02-08"), window.endDate)
    }

    @Test
    fun monthlyFixedDayTaskIsNotTheRefreshWindow() {
        val monthlyTask = OccurrenceRules.slotsForDate(
            recurrence = RecurrenceType.MONTHLY,
            weekdays = emptySet(),
            everyNDays = null,
            periodStart = LocalDate.parse("2026-01-01"),
            periodEnd = LocalDate.parse("2026-03-31"),
            date = LocalDate.parse("2026-02-28"),
            rules = rules(setOf(15)),
            monthDays = setOf(31),
        )
        assertTrue("a 31st task has no slot in February", monthlyTask.isEmpty())

        val slots = OccurrenceRules.slotsForDate(
            recurrence = RecurrenceType.WEEKLY,
            weekdays = emptySet(),
            everyNDays = null,
            periodStart = LocalDate.parse("2026-01-01"),
            periodEnd = LocalDate.parse("2026-03-31"),
            date = LocalDate.parse("2026-01-20"),
            rules = rules(setOf(1, 15)),
        )
        assertEquals("W:2026-01-15", slots.single().key)
    }
}
