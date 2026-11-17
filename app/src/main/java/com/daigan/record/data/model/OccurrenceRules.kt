package com.daigan.record.data.model

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.GameWeekWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Stable occurrence keys and date attribution.
 *
 * Keys are scoped by the task definition id (which is itself period-scoped), so the
 * database unique index `(taskDefinitionId, occurrenceKey)` is the idempotency guard:
 *
 * - `D:<gameDate>`   daily task on that game day
 * - `S:<gameDate>`   weekday-selected task on that game day
 * - `E:<gameDate>`   every-N-days task on that game day
 * - `M:<gameDate>`   monthly task on its fixed day (missing calendar days are skipped)
 * - `W:<windowStartLocalDate>` weekly task for that whole configured window
 *
 * Weekly work is never copied to each day of the week. One window produces one row.
 *
 * Availability is decided by real temporal window membership and by the intersection
 * of the window with the hosting period. The display labels (`startDate..startDate+6`)
 * are labels only: the live window can extend past its last label, so a live weekly
 * occurrence must not disappear merely because the game date moved to the next label.
 */
object OccurrenceRules {

    fun appliesOn(
        recurrence: RecurrenceType,
        weekdays: Set<DayOfWeek>,
        everyNDays: Int?,
        periodStart: LocalDate,
        date: LocalDate,
        monthDay: Int? = null,
        monthDays: Set<Int> = setOfNotNull(monthDay),
        serviceWeekdays: Set<DayOfWeek> = emptySet(),
    ): Boolean {
        if (date.isBefore(periodStart) || (serviceWeekdays.isNotEmpty() && date.dayOfWeek !in serviceWeekdays)) return false
        return when (recurrence) {
            // One-time work is not recurrence: it never owns a game-day slot.
            RecurrenceType.ONE_TIME -> false
            RecurrenceType.MONTHLY -> date.dayOfMonth in monthDays.ifEmpty { setOfNotNull(monthDay) }
            RecurrenceType.DAILY, RecurrenceType.WEEKLY -> true
            RecurrenceType.SELECTED_WEEKDAYS -> date.dayOfWeek in weekdays
            RecurrenceType.EVERY_N_DAYS -> {
                val n = everyNDays ?: return false
                if (n < 1) return false
                val days = ChronoUnit.DAYS.between(periodStart, date)
                days >= 0 && days % n == 0L
            }
        }
    }

    /**
     * Slot for "today". A weekly task is available whenever the current instant is
     * inside its window and the window intersects the hosting period, even if the
     * game date has already crossed the window's last display label.
     */
    fun slotForNow(
        recurrence: RecurrenceType,
        weekdays: Set<DayOfWeek>,
        everyNDays: Int?,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        today: LocalDate,
        rules: GameRefreshRules,
        now: Instant,
        monthDay: Int? = null,
        monthDays: Set<Int> = setOfNotNull(monthDay),
        serviceWeekdays: Set<DayOfWeek> = emptySet(),
        activeFrom: LocalDate? = null,
        activeUntil: LocalDate? = null,
        retiredAt: Instant? = null,
    ): OccurrenceSlot? {
        if (activeFrom != null && today.isBefore(activeFrom)) return null
        // 停止生效日之后不再产生任何打卡槽；用户必须先调整生效日期才能继续。
        if (activeUntil != null && today.isAfter(activeUntil)) return null
        if (retiredAt != null && today.isAfter(GameCalendar.gameDate(retiredAt, rules))) return null
        if (!GameCalendar.isWithinPeriod(today, periodStart, periodEnd) ||
            (serviceWeekdays.isNotEmpty() && today.dayOfWeek !in serviceWeekdays)) return null
        if (recurrence == RecurrenceType.WEEKLY) {
            return weeklySlot(
                window = GameCalendar.refreshWindow(now, rules),
                periodStart = periodStart,
                periodEnd = periodEnd,
                rules = rules,
                serviceWeekdays = serviceWeekdays,
                activeFrom = activeFrom,
                retiredAt = retiredAt,
            )
        }
        if (!appliesOn(recurrence, weekdays, everyNDays, periodStart, today, monthDay, monthDays, serviceWeekdays)) return null
        return dailySlot(recurrence, today)
    }

    /**
     * Slot when a historical game date is selected without an explicit window.
     *
     * For weekly tasks this keeps the documented midday rule. When the game day owns
     * two windows use [slotsForDate] / [slotForWeeklyWindow] so the caller chooses.
     */
    fun slotForDate(
        recurrence: RecurrenceType,
        weekdays: Set<DayOfWeek>,
        everyNDays: Int?,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        date: LocalDate,
        rules: GameRefreshRules,
        monthDay: Int? = null,
        monthDays: Set<Int> = setOfNotNull(monthDay),
        serviceWeekdays: Set<DayOfWeek> = emptySet(),
        activeFrom: LocalDate? = null,
        activeUntil: LocalDate? = null,
        retiredAt: Instant? = null,
    ): OccurrenceSlot? {
        if (activeFrom != null && date.isBefore(activeFrom)) return null
        if (activeUntil != null && date.isAfter(activeUntil)) return null
        if (retiredAt != null && date.isAfter(GameCalendar.gameDate(retiredAt, rules))) return null
        if (!GameCalendar.isWithinPeriod(date, periodStart, periodEnd) ||
            (serviceWeekdays.isNotEmpty() && date.dayOfWeek !in serviceWeekdays)) return null
        if (recurrence == RecurrenceType.WEEKLY) {
            return weeklySlot(
                window = GameCalendar.refreshWindowForGameDate(date, rules),
                periodStart = periodStart,
                periodEnd = periodEnd,
                rules = rules,
                serviceWeekdays = serviceWeekdays,
                activeFrom = activeFrom,
                retiredAt = retiredAt,
            )
        }
        if (!appliesOn(recurrence, weekdays, everyNDays, periodStart, date, monthDay, monthDays, serviceWeekdays)) return null
        return dailySlot(recurrence, date)
    }

    /**
     * Every slot a selected game date can refer to. Daily-style tasks yield at most
     * one slot; a weekly task yields one slot per window that the game day touches
     * (usually one, two when the weekly refresh falls inside the game day).
     */
    fun slotsForDate(
        recurrence: RecurrenceType,
        weekdays: Set<DayOfWeek>,
        everyNDays: Int?,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        date: LocalDate,
        rules: GameRefreshRules,
        monthDay: Int? = null,
        monthDays: Set<Int> = setOfNotNull(monthDay),
        serviceWeekdays: Set<DayOfWeek> = emptySet(),
        activeFrom: LocalDate? = null,
        activeUntil: LocalDate? = null,
        retiredAt: Instant? = null,
    ): List<OccurrenceSlot> {
        if (activeFrom != null && date.isBefore(activeFrom)) return emptyList()
        if (activeUntil != null && date.isAfter(activeUntil)) return emptyList()
        if (retiredAt != null && date.isAfter(GameCalendar.gameDate(retiredAt, rules))) return emptyList()
        if (!GameCalendar.isWithinPeriod(date, periodStart, periodEnd) ||
            (serviceWeekdays.isNotEmpty() && date.dayOfWeek !in serviceWeekdays)) return emptyList()
        if (recurrence == RecurrenceType.WEEKLY) {
            return GameCalendar.refreshWindowsForGameDate(date, rules).mapNotNull { window ->
                weeklySlot(window, periodStart, periodEnd, rules, serviceWeekdays, activeFrom, retiredAt)
            }
        }
        if (!appliesOn(recurrence, weekdays, everyNDays, periodStart, date, monthDay, monthDays, serviceWeekdays)) return emptyList()
        return listOf(dailySlot(recurrence, date))
    }

    /**
     * Explicit weekly occurrence: the window identified by [windowStart], for the
     * selected game [date]. Both the window and the date must exist.
     */
    fun slotForWeeklyWindow(
        windowStart: LocalDate,
        date: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        rules: GameRefreshRules,
        serviceWeekdays: Set<DayOfWeek> = emptySet(),
        activeFrom: LocalDate? = null,
        activeUntil: LocalDate? = null,
        retiredAt: Instant? = null,
    ): OccurrenceSlot? {
        if (activeFrom != null && date.isBefore(activeFrom)) return null
        if (activeUntil != null && date.isAfter(activeUntil)) return null
        if (retiredAt != null && date.isAfter(GameCalendar.gameDate(retiredAt, rules))) return null
        if (!GameCalendar.isWithinPeriod(date, periodStart, periodEnd) ||
            (serviceWeekdays.isNotEmpty() && date.dayOfWeek !in serviceWeekdays)) return null
        val window = GameCalendar.refreshWindowStartingOn(windowStart, rules) ?: return null
        val dayStart = GameCalendar.gameDayStart(date, rules)
        val dayEndExclusive = GameCalendar.nextGameDayStart(date, rules)
        val overlapsDay = window.startInstant < dayEndExclusive &&
            window.endExclusiveInstant > dayStart
        if (!overlapsDay) return null
        return weeklySlot(window, periodStart, periodEnd, rules, serviceWeekdays, activeFrom, retiredAt)
    }

    private fun dailySlot(recurrence: RecurrenceType, date: LocalDate): OccurrenceSlot {
        val prefix = when (recurrence) {
            RecurrenceType.MONTHLY -> "M"
            RecurrenceType.DAILY -> "D"
            RecurrenceType.SELECTED_WEEKDAYS -> "S"
            RecurrenceType.EVERY_N_DAYS -> "E"
            RecurrenceType.WEEKLY -> "W"
            RecurrenceType.ONE_TIME -> error("一次性任务没有发生记录")
        }
        return OccurrenceSlot(
            key = "$prefix:$date",
            attributedDate = date,
            windowStart = date,
            windowEnd = date,
            weeklyWindowStart = null,
        )
    }

    /**
     * The weekly occurrence exists only when its real time interval overlaps the
     * hosting period's real time interval. Its key uses the unclamped window start so
     * the same occurrence stays stable at both ends of a period; display dates are
     * clamped to the hosting dates.
     */
    private fun weeklySlot(
        window: GameWeekWindow,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        rules: GameRefreshRules,
        serviceWeekdays: Set<DayOfWeek>,
        activeFrom: LocalDate?,
        retiredAt: Instant?,
    ): OccurrenceSlot? {
        val periodStartInstant = GameCalendar.periodStartInstant(periodStart, rules)
        val periodEndExclusive = GameCalendar.periodEndExclusiveInstant(periodEnd, rules)
        val intersects = window.startInstant < periodEndExclusive &&
            window.endExclusiveInstant > periodStartInstant
        if (!intersects) return null
        val (displayStart, displayEnd) =
            GameCalendar.clampWindowToPeriod(window, periodStart, periodEnd)
        // Keep the refresh-window key stable, but give the row to the first game
        // date on which this definition can actually be performed. A refresh day
        // need not be a service day, and a task can be introduced mid-window.
        val firstDate = maxOf(displayStart, activeFrom ?: periodStart)
        val lastDate = minOf(periodEnd,
            GameCalendar.gameDate(window.endExclusiveInstant.minusNanos(1), rules),
            retiredAt?.let { GameCalendar.gameDate(it, rules) } ?: periodEnd)
        val attribution = generateSequence(firstDate) { it.plusDays(1) }
            .takeWhile { it <= lastDate }
            .firstOrNull { date ->
                (serviceWeekdays.isEmpty() || date.dayOfWeek in serviceWeekdays) &&
                    GameCalendar.gameDayStart(date, rules) < window.endExclusiveInstant &&
                    GameCalendar.nextGameDayStart(date, rules) > window.startInstant
            } ?: return null
        return OccurrenceSlot(
            key = "W:${window.startDate}",
            attributedDate = attribution,
            windowStart = displayStart,
            windowEnd = displayEnd,
            weeklyWindowStart = window.startDate,
        )
    }

    fun windowLabel(slot: OccurrenceSlot): String? =
        if (slot.key.startsWith("W:")) {
            GameCalendar.windowLabel(slot.windowStart, slot.windowEnd)
        } else {
            null
        }
}
