package com.daigan.record.data.model

import com.daigan.record.core.time.GameRefreshRules
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SchedulingContractTest {
    private val rules = GameRefreshRules(ZoneId.of("Asia/Shanghai"),LocalTime.of(4,0),DayOfWeek.MONDAY,LocalTime.of(5,0))
    @Test fun monthDatesAreSortedDeduplicatedAndNeverSilentlyDropBadTokens() {
        assertEquals(setOf(1,15,31), parseMonthlyDates("31，1,15,15"))
        for (bad in listOf("", "0,15", "1,32", "abc,15", "1.5")) assertNull(bad,parseMonthlyDates(bad))
    }
    @Test fun multipleMonthDaysRespectLeapYearsAndServiceBoundaries() {
        fun slot(day:String) = OccurrenceRules.slotForDate(RecurrenceType.MONTHLY, emptySet(),null,
            LocalDate.of(2028,1,1),LocalDate.of(2028,12,31),LocalDate.parse(day),rules,monthDays=setOf(1,15,29,31))
        assertEquals("M:2028-02-29",slot("2028-02-29")!!.key)
        assertNull(slot("2028-02-28"))
        assertEquals("M:2028-12-31",slot("2028-12-31")!!.key)
        assertNull(slot("2029-01-01"))
    }
    @Test fun serviceWeekdaysFilterDailyMonthlyAndWeeklySlots() {
        for (type in listOf(RecurrenceType.DAILY,RecurrenceType.WEEKLY,RecurrenceType.MONTHLY)) {
            assertNull(OccurrenceRules.slotForDate(type,emptySet(),null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),
                LocalDate.of(2026,10,17),rules,monthDays=setOf(17),serviceWeekdays=setOf(DayOfWeek.MONDAY)))
        }
    }
    @Test fun correctedDefinitionsDoNotRewriteEarlierCalendarDates() {
        assertTrue(OccurrenceRules.slotsForDate(RecurrenceType.DAILY,emptySet(),null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),
            LocalDate.of(2026,10,19),rules,activeFrom=LocalDate.of(2026,10,20)).isEmpty())
        assertTrue(OccurrenceRules.slotsForDate(RecurrenceType.DAILY,emptySet(),null,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),
            LocalDate.of(2026,10,21),rules,retiredAt=Instant.parse("2026-10-20T02:00:00Z")).isEmpty())
    }
}
