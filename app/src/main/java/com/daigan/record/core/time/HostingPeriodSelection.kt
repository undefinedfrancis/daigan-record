package com.daigan.record.core.time

import java.time.LocalDate

/**
 * Which hosting period a screen is talking about.
 *
 * Three different questions must not be answered by one "latest period" rule (UF-020):
 *
 * - **effective**: the period whose inclusive `[startDate, endDate]` contains that
 *   period's own game date. This is what today's work, backfill defaults, reminders and
 *   the detail header belong to.
 * - **latest**: the highest `periodIndex`. Renewals are appended after it, never after
 *   the effective period, so an early renewal cannot overlap an already scheduled one.
 * - **display**: what a "today" surface shows when the effective period does not exist —
 *   the next scheduled period while the order has not started, or the most recently
 *   ended period once every period has expired (so the order still reads "已到期" instead
 *   of silently disappearing).
 *
 * **Each period is judged by its own stored refresh snapshot**, never by the order row's
 * current rules. A period's snapshot is the record of how its service days and occurrence
 * keys were computed; the order row is only the *intent for the next* renewal. Using the
 * order rules for selection and the snapshot for scheduling (or the reverse) made the
 * Today list and the write path disagree on the game date around a daily-refresh
 * boundary, so every consumer goes through [HostingPeriodClock] instead.
 *
 * This file is deliberately clock-free: callers pass a per-item game date in.
 */
object HostingPeriodSelection {

    /** Periods whose own game date falls inside their inclusive window; lowest index wins. */
    fun <T> effectiveFor(
        items: List<T>,
        gameDateOf: (T) -> LocalDate,
        startDate: (T) -> LocalDate,
        endDate: (T) -> LocalDate,
        periodIndex: (T) -> Int,
    ): T? = items
        .filter { item -> contains(startDate(item), endDate(item), gameDateOf(item)) }
        .minByOrNull(periodIndex)

    /** The last scheduled period: renewals continue from here to avoid overlaps. */
    fun <T> latest(items: List<T>, periodIndex: (T) -> Int): T? =
        items.maxByOrNull(periodIndex)

    /**
     * Effective period, else the next one to start, else the latest one that ended.
     * All three comparisons use the same per-period game date as [effectiveFor].
     */
    fun <T> displayFor(
        items: List<T>,
        gameDateOf: (T) -> LocalDate,
        startDate: (T) -> LocalDate,
        endDate: (T) -> LocalDate,
        periodIndex: (T) -> Int,
    ): T? = effectiveFor(items, gameDateOf, startDate, endDate, periodIndex)
        ?: items
            .filter { startDate(it).isAfter(gameDateOf(it)) }
            .minByOrNull { startDate(it) }
        ?: items
            .filter { endDate(it).isBefore(gameDateOf(it)) }
            .maxByOrNull { endDate(it) }

    /** Inclusive period membership: both the first and the last service day count. */
    fun contains(startDate: LocalDate, endDate: LocalDate, date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)

    /**
     * First day a new period may start when the user renews at [today]:
     * the day after the last scheduled period while it is still running or scheduled,
     * or the current game date once every period has already expired.
     */
    fun nextStartDate(lastScheduledEnd: LocalDate, today: LocalDate): LocalDate =
        if (today.isAfter(lastScheduledEnd)) today else lastScheduledEnd.plusDays(1)
}
