package com.daigan.record.feature.orders

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import java.time.Instant

/**
 * Where a hosting period sits relative to the current game date.
 *
 * The unified editor (UF-030) lists **every** period, not only the current one: a user must
 * be able to select an ended period to correct its price or the dates the repository still
 * allows, exactly like the separate price/date dialogs did before this page replaced them.
 */
internal enum class PeriodPhase {
    HISTORICAL,
    CURRENT,
    FUTURE,
}

internal fun GameRefreshRulesDomain.toCoreRules(): GameRefreshRules = GameRefreshRules(
    zoneId = zoneId,
    dailyRefresh = dailyRefresh,
    weeklyRefreshDay = weeklyRefreshDay,
    weeklyRefreshTime = weeklyRefreshTime,
    monthlyRefreshDays = monthlyRefreshDays,
)

/**
 * Classification uses the period's own refresh rules, so a period ending on the local
 * "today" but before the game refresh time is still current, not historical.
 */
internal fun HostingPeriod.phaseAt(now: Instant): PeriodPhase {
    val today = GameCalendar.gameDate(now, refresh.toCoreRules())
    return when {
        endDate.isBefore(today) -> PeriodPhase.HISTORICAL
        startDate.isAfter(today) -> PeriodPhase.FUTURE
        else -> PeriodPhase.CURRENT
    }
}

/** Label of the editor's period picker: every period is selectable. */
internal fun HostingPeriod.pickerLabel(now: Instant): String {
    val phase = when (phaseAt(now)) {
        PeriodPhase.HISTORICAL -> "历史期"
        PeriodPhase.CURRENT -> "当前期"
        PeriodPhase.FUTURE -> "未来期"
    }
    return "$phase · 第 ${periodIndex + 1} 期"
}

/**
 * An ended period keeps its task definitions and service plan as history; only the price
 * and the dates the repository still accepts may be corrected.
 */
internal fun HostingPeriod.isHistoryLockedAt(now: Instant): Boolean = phaseAt(now) == PeriodPhase.HISTORICAL
