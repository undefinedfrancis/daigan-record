package com.daigan.record.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Refresh rules that belong to one order/period snapshot.
 *
 * Three different date concepts are deliberately kept apart:
 *
 * - **Local calendar date** — the civil date in [zoneId]. This is what a device clock
 *   would show and what the date picker works with.
 * - **Game date** — a day of work in the game. It starts at [dailyRefresh] local time,
 *   not at midnight: before that time the previous local date is still the game date.
 *   Daily, weekday-selected and every-N-day tasks are attributed to this date.
 * - **Weekly occurrence window** — the half-open interval
 *   `[weekly refresh instant, +7 days)`. Weekly tasks get exactly one occurrence per
 *   window regardless of how many game days the window spans.
 *
 * The rules are user-configured provisional values in Phase 2. Verified game templates
 * arrive in Phase 4; nothing here claims an official rule for a named game.
 *
 * [monthlyRefreshDays] is the shared schema-15 field. When it is non-empty, **weekly-style
 * work** (recurrence WEEKLY) uses refresh-to-refresh windows anchored on those monthly
 * days instead of seven-day windows. A day number that does not exist in a month is
 * skipped, never clamped to the month's last day. Fixed-day monthly task recurrence
 * (`M:<date>` occurrences) is a different concept and is unaffected.
 */
data class GameRefreshRules(
    val zoneId: ZoneId,
    val dailyRefresh: LocalTime,
    val weeklyRefreshDay: DayOfWeek,
    val weeklyRefreshTime: LocalTime,
    val monthlyRefreshDays: Set<Int> = emptySet(),
) {
    init {
        require(!dailyRefresh.isBefore(LocalTime.MIDNIGHT)) { "每日刷新时间无效" }
        require(!weeklyRefreshTime.isBefore(LocalTime.MIDNIGHT)) { "每周刷新时间无效" }
        require(monthlyRefreshDays.all { it in 1..31 }) { "每月刷新日需为 1–31" }
    }

    val dailyRefreshMinutes: Int
        get() = dailyRefresh.hour * 60 + dailyRefresh.minute

    val weeklyRefreshDayIso: Int
        get() = weeklyRefreshDay.value

    val weeklyRefreshMinutes: Int
        get() = weeklyRefreshTime.hour * 60 + weeklyRefreshTime.minute

    /** True when WEEKLY work is windowed by monthly refresh days instead of weeks. */
    val monthly: Boolean get() = monthlyRefreshDays.isNotEmpty()

    companion object {
        fun fromMinutes(
            zoneId: ZoneId,
            dailyRefreshMinutes: Int,
            weeklyRefreshDayIso: Int,
            weeklyRefreshMinutes: Int,
            monthlyRefreshDays: Set<Int> = emptySet(),
        ): GameRefreshRules = GameRefreshRules(
            zoneId = zoneId,
            dailyRefresh = LocalTime.of(dailyRefreshMinutes / 60, dailyRefreshMinutes % 60),
            weeklyRefreshDay = DayOfWeek.of(weeklyRefreshDayIso.coerceIn(1, 7)),
            weeklyRefreshTime = LocalTime.of(weeklyRefreshMinutes / 60, weeklyRefreshMinutes % 60),
            monthlyRefreshDays = monthlyRefreshDays,
        )

        /** Provisional defaults; the UI marks them as user-configured values. */
        fun provisional(zoneId: ZoneId = ZoneId.systemDefault()): GameRefreshRules = GameRefreshRules(
            zoneId = zoneId,
            dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        )
    }
}

/** One weekly occurrence window. [endDate] is inclusive and is a display label only. */
data class GameWeekWindow(
    val startInstant: Instant,
    val endExclusiveInstant: Instant,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val gameDateAtStart: LocalDate,
)

/**
 * Pure calendar arithmetic. No Android types and no UI dependencies, so every rule can
 * be tested with a fixed clock and an explicit timezone.
 */
object GameCalendar {

    fun localDate(at: Instant, zoneId: ZoneId): LocalDate = at.atZone(zoneId).toLocalDate()

    /**
     * The game date for [at]: the local date, or the previous local date while the
     * local time is still before the daily refresh.
     */
    fun gameDate(at: Instant, rules: GameRefreshRules): LocalDate {
        val zoned = at.atZone(rules.zoneId)
        return if (zoned.toLocalTime().isBefore(rules.dailyRefresh)) {
            zoned.toLocalDate().minusDays(1)
        } else {
            zoned.toLocalDate()
        }
    }

    /** The first instant of [gameDate] in the configured zone. */
    fun gameDayStart(gameDate: LocalDate, rules: GameRefreshRules): Instant =
        ZonedDateTime.of(gameDate, rules.dailyRefresh, rules.zoneId).toInstant()

    /** The instant at which the next game date begins. */
    fun nextGameDayStart(gameDate: LocalDate, rules: GameRefreshRules): Instant =
        gameDayStart(gameDate.plusDays(1), rules)

    /** A stable instant representing [gameDate] for date-only lookups (midday). */
    fun representativeInstant(gameDate: LocalDate, rules: GameRefreshRules): Instant =
        gameDayStart(gameDate, rules).plusMillis(12 * 60 * 60 * 1000L)

    /**
     * The refresh-to-refresh window that contains [at].
     *
     * With no [GameRefreshRules.monthlyRefreshDays] the window is the shipped seven-day
     * one. With monthly refresh days it runs from the latest existing monthly refresh
     * instant at or before [at] to the next one. A monthly day that does not exist in a
     * short month is skipped (never clamped), so February simply has fewer boundaries.
     */
    fun refreshWindow(at: Instant, rules: GameRefreshRules): GameWeekWindow =
        windowFromStart(refreshWindowStart(at, rules), rules)

    /** Backward-compatible name for [refreshWindow]; monthly rules are honoured too. */
    fun weeklyWindow(at: Instant, rules: GameRefreshRules): GameWeekWindow =
        refreshWindow(at, rules)

    private fun refreshWindowStart(at: Instant, rules: GameRefreshRules): ZonedDateTime {
        if (!rules.monthly) {
            val zonedAt = at.atZone(rules.zoneId)
            val previousOrSame = zonedAt.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(rules.weeklyRefreshDay))
            var start = ZonedDateTime.of(previousOrSame, rules.weeklyRefreshTime, rules.zoneId)
            if (start.toInstant() > at) start = start.minusWeeks(1)
            return start
        }
        var month = YearMonth.from(at.atZone(rules.zoneId))
        // Three months is enough for every valid 1..31 set; the loop is bounded so a
        // malformed rule can never spin.
        repeat(3) {
            rules.monthlyRefreshDays.sortedDescending().forEach { day ->
                if (day <= month.lengthOfMonth()) {
                    val candidate = ZonedDateTime.of(
                        month.atDay(day),
                        rules.weeklyRefreshTime,
                        rules.zoneId,
                    )
                    if (!candidate.toInstant().isAfter(at)) return candidate
                }
            }
            month = month.minusMonths(1)
        }
        error("每月刷新日无法解析：${rules.monthlyRefreshDays}")
    }

    /**
     * The window whose refresh instant is exactly [windowStart]. For weekly rules the
     * date must be the configured weekday; for monthly rules its day number must be a
     * configured monthly day. Callers therefore cannot invent a window that never starts.
     */
    fun refreshWindowStartingOn(
        windowStart: LocalDate,
        rules: GameRefreshRules,
    ): GameWeekWindow? {
        if (rules.monthly) {
            if (windowStart.dayOfMonth !in rules.monthlyRefreshDays) return null
        } else if (windowStart.dayOfWeek != rules.weeklyRefreshDay) {
            return null
        }
        return windowFromStart(
            ZonedDateTime.of(windowStart, rules.weeklyRefreshTime, rules.zoneId),
            rules,
        )
    }

    /** Backward-compatible name for [refreshWindowStartingOn]. */
    fun weeklyWindowStartingOn(
        windowStart: LocalDate,
        rules: GameRefreshRules,
    ): GameWeekWindow? = refreshWindowStartingOn(windowStart, rules)

    /**
     * Every refresh window that intersects the game day [gameDate].
     *
     * A game day starts at the daily refresh. When the (weekly or monthly) refresh
     * instant happens later in the same civil day, that game day contains the boundary
     * and therefore touches two windows: the closing one and the opening one. Returning
     * both keeps historical corrections explicit instead of silently choosing one.
     */
    fun refreshWindowsForGameDate(
        gameDate: LocalDate,
        rules: GameRefreshRules,
    ): List<GameWeekWindow> {
        val dayStart = gameDayStart(gameDate, rules)
        val dayEndExclusive = nextGameDayStart(gameDate, rules)
        val first = refreshWindow(dayStart, rules)
        val lastInstant = dayEndExclusive.minusNanos(1)
        if (!lastInstant.isAfter(dayStart)) return listOf(first)
        val second = refreshWindow(lastInstant, rules)
        return if (second.startInstant == first.startInstant) {
            listOf(first)
        } else {
            listOf(first, second)
        }
    }

    /** Backward-compatible name for [refreshWindowsForGameDate]. */
    fun weeklyWindowsForGameDate(
        gameDate: LocalDate,
        rules: GameRefreshRules,
    ): List<GameWeekWindow> = refreshWindowsForGameDate(gameDate, rules)

    /**
     * The refresh window used when a historical *game date* is selected without an
     * explicit window. A refresh boundary may fall inside a game day; midday of that
     * game day picks the window that covers the rest of the day. Callers that must be
     * unambiguous pass an explicit window start.
     */
    fun refreshWindowForGameDate(gameDate: LocalDate, rules: GameRefreshRules): GameWeekWindow =
        refreshWindow(representativeInstant(gameDate, rules), rules)

    /** Backward-compatible name for [refreshWindowForGameDate]. */
    fun weeklyWindowForGameDate(gameDate: LocalDate, rules: GameRefreshRules): GameWeekWindow =
        refreshWindowForGameDate(gameDate, rules)

    /** First instant of the hosting period (its start game day). */
    fun periodStartInstant(startDate: LocalDate, rules: GameRefreshRules): Instant =
        gameDayStart(startDate, rules)

    /** Exclusive end of the hosting period, i.e. the start of the day after [endDate]. */
    fun periodEndExclusiveInstant(endDate: LocalDate, rules: GameRefreshRules): Instant =
        gameDayStart(endDate.plusDays(1), rules)

    /**
     * The next instant at which a date/window observation can change: the next daily
     * refresh or the next weekly refresh, whichever is earlier. Also used to schedule
     * UI refreshes without polling.
     */
    fun nextRefreshBoundary(at: Instant, rules: GameRefreshRules): Instant {
        val nextDaily = nextGameDayStart(gameDate(at, rules), rules)
        val nextWeekly = weeklyWindow(at, rules).endExclusiveInstant
        return when {
            nextDaily.isAfter(at) && nextDaily.isBefore(nextWeekly) -> nextDaily
            nextWeekly.isAfter(at) -> nextWeekly
            else -> nextDaily
        }
    }

    /** The window's inclusive display start/end, clamped to a hosting period. */
    fun clampWindowToPeriod(
        window: GameWeekWindow,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Pair<LocalDate, LocalDate> {
        val start = maxOf(window.startDate, periodStart)
        val end = minOf(window.endDate, periodEnd).coerceAtLeast(start)
        return start to end
    }

    private fun windowFromStart(start: ZonedDateTime, rules: GameRefreshRules): GameWeekWindow {
        val startInstant = start.toInstant()
        val end = if (rules.monthly) {
            nextMonthlyRefreshStart(start, rules)
        } else {
            start.plusWeeks(1)
        }
        // Weekly labels seven dates (a 7x24h interval ends at 05:00 on the day after the
        // last label). Monthly windows label every civil date the interval covers.
        val endDate = if (rules.monthly) {
            end.toLocalDate().minusDays(
                if (end.toLocalTime().isAfter(rules.dailyRefresh)) 1 else 0,
            ).coerceAtLeast(start.toLocalDate())
        } else {
            start.toLocalDate().plusDays(6)
        }
        return GameWeekWindow(
            startInstant = startInstant,
            endExclusiveInstant = end.toInstant(),
            startDate = start.toLocalDate(),
            endDate = endDate,
            gameDateAtStart = gameDate(startInstant, rules),
        )
    }

    /**
     * The next existing monthly refresh instant strictly after [start]. Absent days
     * (29–31 in short months) are skipped, never clamped to the month end.
     */
    private fun nextMonthlyRefreshStart(
        start: ZonedDateTime,
        rules: GameRefreshRules,
    ): ZonedDateTime {
        var date = start.toLocalDate().plusDays(1)
        val limit = date.plusDays(70)
        while (!date.isAfter(limit)) {
            if (date.dayOfMonth in rules.monthlyRefreshDays) {
                val candidate = ZonedDateTime.of(date, rules.weeklyRefreshTime, rules.zoneId)
                if (candidate.toInstant().isAfter(start.toInstant())) return candidate
            }
            date = date.plusDays(1)
        }
        error("每月刷新日无法解析：${rules.monthlyRefreshDays}")
    }

    /** Inclusive hosting dates: the first day counts as day 1. */
    fun hostingEndDate(startDate: LocalDate, durationDays: Int): LocalDate {
        require(durationDays > 0) { "托管天数必须大于 0" }
        return startDate.plusDays(durationDays.toLong() - 1)
    }

    /** Returns the date on which [serviceDays] selected-weekday services finish. */
    fun hostingEndDateForWeekdays(
        startDate: LocalDate,
        serviceDays: Int,
        weekdays: Set<DayOfWeek>,
    ): LocalDate {
        require(serviceDays > 0) { "托管天数必须大于 0" }
        require(weekdays.isNotEmpty()) { "工作日托管至少选择一个星期" }
        var date = startDate
        var count = 0
        while (true) {
            if (date.dayOfWeek in weekdays) {
                count++
                if (count == serviceDays) return date
            }
            date = date.plusDays(1)
        }
    }

    fun inclusiveDurationDays(startDate: LocalDate, endDate: LocalDate): Int {
        require(!endDate.isBefore(startDate)) { "到期日期不能早于开始日期" }
        return (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
    }

    fun isWithinPeriod(date: LocalDate, startDate: LocalDate, endDate: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)

    fun hostingStatus(today: LocalDate, startDate: LocalDate, endDate: LocalDate): HostingStatus =
        when {
            today.isBefore(startDate) -> HostingStatus.NOT_STARTED
            today.isAfter(endDate) -> HostingStatus.EXPIRED
            else -> HostingStatus.ACTIVE
        }

    /** Text label for a window, e.g. "10.12–10.18". */
    fun windowLabel(windowStart: LocalDate, windowEnd: LocalDate): String =
        "%02d.%02d–%02d.%02d".format(
            windowStart.monthValue,
            windowStart.dayOfMonth,
            windowEnd.monthValue,
            windowEnd.dayOfMonth,
        )
}

enum class HostingStatus { NOT_STARTED, ACTIVE, EXPIRED }
