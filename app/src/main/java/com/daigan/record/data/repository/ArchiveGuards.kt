package com.daigan.record.data.repository

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.toModel
import java.time.Instant
import java.time.ZoneId

internal const val ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE =
    "该老板还有未完成的短期订单或未到期的托管期，请先完成订单或等待托管到期后再归档"

internal const val ARCHIVED_ACCOUNT_HAS_ACTIVE_WORK_MESSAGE =
    "该游戏账号还有未完成的短期订单或未到期的托管期，请先完成订单或等待托管到期后再归档"

internal const val ARCHIVED_OWNER_HAS_UNFINISHED_COUNT_MESSAGE =
    "该老板还有未完成的打卡式托管，请先完成约定次数或作废订单后再归档"

internal const val ARCHIVED_ACCOUNT_HAS_UNFINISHED_COUNT_MESSAGE =
    "该游戏账号还有未完成的打卡式托管，请先完成约定次数或作废订单后再归档"

/**
 * 打卡式托管 never expires by date, so the date-based guard above cannot see it. An
 * unfinished count-based order must still block archiving, otherwise the remaining rounds
 * would silently disappear from the active lists.
 */
internal suspend fun assertNoUnfinishedCountHosting(
    orders: List<com.daigan.record.data.local.entity.OrderEntity>,
    sessionsOf: suspend (String) -> List<com.daigan.record.data.local.entity.HostingSessionEntity>,
    periodsOf: suspend (String) -> List<HostingPeriodEntity>,
    message: String,
) {
    for (order in orders) {
        if (order.type != com.daigan.record.data.model.OrderType.HOSTING) continue
        if (order.hostingMode != com.daigan.record.data.model.HostingMode.COUNT_BASED) continue
        if (order.cancelledAt != null || order.archivedAt != null) continue
        val sessions = sessionsOf(order.id).map { it.toModel() }
        // Every count period carries its own target: completion is per period, never the
        // order-level mirror (which cannot describe several agreements).
        val countPeriods = periodsOf(order.id).filter { it.serviceCount != null }.map { it.toModel() }
        val complete = if (countPeriods.isEmpty()) {
            com.daigan.record.data.model.CountHosting.isComplete(sessions, order.targetSessions)
        } else {
            countPeriods.all { period ->
                com.daigan.record.data.model.CountHosting.periodCompletedCount(
                    sessions,
                    period.id,
                ) >= (period.serviceCount ?: 0)
            }
        }
        require(complete) { message }
    }
}

/**
 * Archiving hides records from the active lists, so it must not silently hide work.
 * Only the newest period per order decides "unexpired": an old period that was already
 * renewed cannot block archiving on its own.
 */
internal fun assertNoUnexpiredPeriod(
    periods: List<HostingPeriodEntity>,
    now: Instant,
    message: String,
    /**
     * Orders whose periods carry no business date range (count-based hosting). Their
     * container row must not be judged by the calendar; [assertNoUnfinishedCountHosting]
     * decides instead.
     */
    skipOrderIds: Set<String> = emptySet(),
) {
    periods.groupBy { it.orderId }.values
        .mapNotNull { byOrder -> byOrder.maxByOrNull { it.periodIndex } }
        .filter { period -> period.orderId !in skipOrderIds }
        .forEach { period ->
            val rules = GameRefreshRules.fromMinutes(
                zoneId = ZoneId.of(period.gameZoneId),
                dailyRefreshMinutes = period.dailyRefreshMinutes,
                weeklyRefreshDayIso = period.weeklyRefreshDay,
                weeklyRefreshMinutes = period.weeklyRefreshMinutes,
            )
            val today = GameCalendar.gameDate(now, rules)
            require(today.isAfter(period.endDate)) { message }
        }
}
