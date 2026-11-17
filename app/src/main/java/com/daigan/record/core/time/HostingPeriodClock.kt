package com.daigan.record.core.time

import java.time.Instant
import java.time.LocalDate

/**
 * A period plus the game date it is being evaluated at, resolved consistently.
 *
 * [gameDate] is computed from [period]'s own stored refresh snapshot, so the pair can be
 * handed to scheduling code (`OccurrenceRules.slotForNow`) without recomputing "today"
 * under a different rule set.
 */
data class ResolvedHostingPeriod<T>(
    val period: T,
    val gameDate: LocalDate,
)

/**
 * The single place that decides *which* hosting period "now" belongs to.
 *
 * Every hosting consumer (today list, owner/order summaries, detail, calendar, backfill,
 * reminders, bulk completion) must resolve through here. The rules are:
 *
 * 1. Each period's game date is computed with **its own** persisted refresh snapshot.
 *    A rule correction after a period ended therefore cannot retroactively move that
 *    period's dates, and the write path (`activePeriodForToday`) sees the same result.
 * 2. The effective period is the one whose inclusive window contains its own game date;
 *    with overlapping legacy rows the lowest `periodIndex` wins, deterministically.
 * 3. With no effective period the next scheduled one is shown, else the most recently
 *    ended one, so an order never disappears from "today" surfaces.
 *
 * `gameDate` in the result is the game date *of the chosen period*, which is also what
 * its tasks, reminders and expiry status must use.
 */
object HostingPeriodClock {

    fun <T> resolve(
        periods: List<T>,
        now: Instant,
        rulesOf: (T) -> GameRefreshRules,
        startDate: (T) -> LocalDate,
        endDate: (T) -> LocalDate,
        periodIndex: (T) -> Int,
    ): ResolvedHostingPeriod<T>? {
        if (periods.isEmpty()) return null
        // One computation per period: selection, status and slots all read this map, so a
        // period cannot be judged by one rule set and scheduled by another.
        val gameDates = periods.associateWith { period -> GameCalendar.gameDate(now, rulesOf(period)) }
        val chosen = HostingPeriodSelection.displayFor(
            items = periods,
            gameDateOf = gameDates::getValue,
            startDate = startDate,
            endDate = endDate,
            periodIndex = periodIndex,
        ) ?: return null
        return ResolvedHostingPeriod(chosen, gameDates.getValue(chosen))
    }

    /** The period that owns work at [now], or null when no period is currently running. */
    fun <T> active(
        periods: List<T>,
        now: Instant,
        rulesOf: (T) -> GameRefreshRules,
        startDate: (T) -> LocalDate,
        endDate: (T) -> LocalDate,
        periodIndex: (T) -> Int,
    ): ResolvedHostingPeriod<T>? = periods
        .map { period -> ResolvedHostingPeriod(period, GameCalendar.gameDate(now, rulesOf(period))) }
        .filter { resolved ->
            GameCalendar.hostingStatus(
                resolved.gameDate,
                startDate(resolved.period),
                endDate(resolved.period),
            ) == HostingStatus.ACTIVE
        }
        .minByOrNull { periodIndex(it.period) }
}
