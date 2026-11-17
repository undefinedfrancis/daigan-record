package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Pure counting rules of 打卡式托管 (UF-033).
 *
 * Confirmed rules implemented here, with no database or UI dependency so they can be
 * verified by fast JVM tests:
 *
 * 1. Several rounds may be completed on the same day. A round, not a date, is the
 *    identity, so the same day never blocks a second count.
 * 2. A round contains its own task progress. The agreed total only moves when *every*
 *    task of the round is complete; an individual task never counts on its own.
 * 3. The next round starts with fresh progress; previous rounds stay as history.
 * 4. Repeated taps/retries/concurrent completion target the same round key and are
 *    therefore counted once. Counting also uses distinct round indexes, so a duplicated
 *    row can never inflate the total.
 * 5. Undo/correction reopens the affected round instead of deleting it.
 * 6. A day without activity consumes nothing and never expires.
 * 7. Reaching the agreed total completes the order.
 */
object CountHosting {

    /** Occurrence key prefix of one round: `N:<sessionIndex>`. */
    const val OCCURRENCE_PREFIX = "N:"

    const val MAX_TARGET_SESSIONS = 3650
    const val DEFAULT_TARGET_SESSIONS = 30

    /** Stable identity of the occurrences that belong to [sessionIndex]. */
    fun occurrenceKey(sessionIndex: Int): String = "$OCCURRENCE_PREFIX$sessionIndex"

    fun sessionIndexOrNull(occurrenceKey: String): Int? {
        if (!occurrenceKey.startsWith(OCCURRENCE_PREFIX)) return null
        return occurrenceKey.removePrefix(OCCURRENCE_PREFIX).toIntOrNull()?.takeIf { it >= 1 }
    }

    /**
     * Completed rounds, counted by distinct index. Two rows that somehow describe the
     * same round cannot double the total.
     */
    fun completedCount(sessions: List<HostingSession>): Int =
        sessions.asSequence().filter { it.completed }.map { it.sessionIndex }.distinct().count()

    /** Rounds completed at or before [date], for optional day-based history views. */
    fun completedCountOn(sessions: List<HostingSession>, date: LocalDate, zone: java.time.ZoneId): Int =
        sessions.asSequence()
            .filter { it.completed && it.completedAt != null }
            .filter { it.completedAt!!.atZone(zone).toLocalDate() == date }
            .map { it.sessionIndex }
            .distinct()
            .count()

    /** Lowest round row that still needs work; null when every existing round is done. */
    fun activeSession(sessions: List<HostingSession>): HostingSession? =
        sessions.asSequence().filter { !it.completed }.minByOrNull { it.sessionIndex }

    /**
     * Index of the round the user is working on, or null when the target is reached.
     *
     * A reopened earlier round wins over later rounds so a correction is finished before
     * new work starts. When every existing row is complete the next index is used; its
     * row is created lazily by the first write.
     */
    fun activeSessionIndex(sessions: List<HostingSession>, target: Int?): Int? {
        val max = target ?: return null
        if (max < 1) return null
        if (completedCount(sessions) >= max) return null
        activeSession(sessions)?.let { return it.sessionIndex }
        val highest = sessions.maxOfOrNull { it.sessionIndex } ?: 0
        return (highest + 1).takeIf { it <= max }
    }

    /**
     * Index of the round that must exist next, or null when no further round may start.
     * [sessions] is the persisted set; [target] the agreed total.
     */
    fun nextSessionIndex(sessions: List<HostingSession>, target: Int?): Int? =
        activeSessionIndex(sessions, target)

    fun hasSession(sessions: List<HostingSession>, index: Int): Boolean =
        sessions.any { it.sessionIndex == index }

    fun isComplete(sessions: List<HostingSession>, target: Int?): Boolean {
        val max = target ?: return false
        return max >= 1 && completedCount(sessions) >= max
    }

    fun remaining(sessions: List<HostingSession>, target: Int?): Int? =
        target?.let { (it - completedCount(sessions)).coerceAtLeast(0) }

    /**
     * A round is complete when it has at least one task and every active task has a
     * completed occurrence under that round's key. An empty task list never counts.
     */
    fun roundCompleted(activeTaskIds: Collection<String>, completedTaskIds: Collection<String>): Boolean =
        activeTaskIds.isNotEmpty() && completedTaskIds.containsAll(activeTaskIds)

    // ---------------------------------------------------------------------
    // Historical due set (one pure rule for writes and restore)
    // ---------------------------------------------------------------------

    /**
     * 一轮次在**它自己的归属游戏日**是否应做该任务（F2）。
     *
     * 这是写入路径（[com.daigan.record.data.repository.CountSessionService]）与恢复重建
     * （[com.daigan.record.backup.DerivedStateReconstructor]）共用的唯一规则，避免出现第二套
     * 应做集实现：
     * - `retirementGameDate` 是 `task_definitions.retiredAt` 按该期次刷新规则换算的游戏日；
     *   **退休当天仍应做**，晚于退休日不再应做（历史轮次不会因为今天移除任务而被判完成）；
     * - `activeFrom`/`activeUntil` 是任务自己的生效区间（含端点）；
     * - 累计任务忽略重复触发日（UF-038 的核心），普通一次性任务不属于任何轮次。
     *
     * 这里只回答"应做"，不回答"今天是否显示"：当前界面仍然只列出未退休任务。
     */
    fun isDueInRound(
        date: LocalDate,
        retirementGameDate: LocalDate?,
        activeFrom: LocalDate?,
        activeUntil: LocalDate?,
        recurrence: RecurrenceType,
        cumulative: Boolean,
    ): Boolean {
        if (retirementGameDate != null && date.isAfter(retirementGameDate)) return false
        if (activeFrom != null && date.isBefore(activeFrom)) return false
        if (activeUntil != null && date.isAfter(activeUntil)) return false
        if (cumulative) return true
        return recurrence != RecurrenceType.ONE_TIME
    }

    /**
     * 退休任务的**写入边界是日期**，不是"整条任务从此只读"（H1）。
     *
     * - [retirementGameDate] 为 null：任务仍在用，不做额外限制；
     * - `gameDate <= retirementGameDate`：属于历史更正/撤销（或补记该轮次当时应做的记录），
     *   与 [isDueInRound] 的"退休当天仍应做"边界完全一致；
     * - `gameDate > retirementGameDate`：这是退休之后的新进度，一律拒绝。
     *
     * 写入与恢复重建共用这一条规则，避免出现"仓库拒绝、界面却提供入口"的第二套判断。
     */
    fun acceptsAttribution(gameDate: LocalDate, retirementGameDate: LocalDate?): Boolean =
        retirementGameDate == null || !gameDate.isAfter(retirementGameDate)

    fun targetSessionError(raw: String?): String? {
        val value = raw?.trim()?.toIntOrNull()
        return when {
            raw.isNullOrBlank() -> "请填写打卡次数"
            value == null -> "打卡次数需为整数"
            value < 1 -> "打卡次数需大于 0"
            value > MAX_TARGET_SESSIONS -> "打卡次数最多 $MAX_TARGET_SESSIONS 次"
            else -> null
        }
    }
    // ---------------------------------------------------------------------
    // Schema 15: selection vs allocation, four active states, five cache states
    // ---------------------------------------------------------------------

    /** 上限是 Kotlin Int 上界，与"期次目标 1..3650"是两个不同的界。 */
    const val MAX_SESSION_INDEX = Int.MAX_VALUE

    /** 分配新轮次号的结果；选择（复用）与分配（新号）永远不混在一起。 */
    sealed interface ActiveSessionChoice {
        data class ReuseExisting(val sessionIndex: Int) : ActiveSessionChoice

        data class AllocateNew(val sessionIndex: Int) : ActiveSessionChoice

        data object PeriodFull : ActiveSessionChoice

        data object IndexSpaceExhausted : ActiveSessionChoice
    }

    /**
     * 分配新轮次号：**全订单** `max(sessionIndex) + 1`（F1 / D-26）。
     *
     * 只接受整单 session 列表。任何"传入期次内列表再取最大值"的实现都是契约违背：
     * 空期次会重新分配 1，与订单内已用号冲突。
     */
    fun nextOrderGlobalSessionIndex(orderSessions: List<HostingSession>): Int? {
        val highest = orderSessions.maxOfOrNull { it.sessionIndex } ?: 0
        if (highest >= MAX_SESSION_INDEX) return null
        val next = highest + 1
        return next.takeIf { it <= MAX_SESSION_INDEX }
    }

    fun periodCompletedCount(sessions: List<HostingSession>, periodId: String): Int =
        sessions.asSequence()
            .filter { it.periodId == periodId && it.completed }
            .map { it.sessionIndex }
            .distinct()
            .count()

    /**
     * 选择 vs 分配分离（F1）。
     *
     * ①该期有不完整轮次 → 复用**最小的订单全局号**（先于判满）；
     * ②否则 `completed < serviceCount` → 分配全局新号；③否则 `PERIOD_FULL`；
     * ④全局已达 [MAX_SESSION_INDEX] → `IndexSpaceExhausted`（不重编号）。
     */
    fun selectActiveSession(
        orderSessions: List<HostingSession>,
        periodId: String,
        periodServiceCount: Int?,
    ): ActiveSessionChoice {
        val periodSessions = orderSessions.filter { it.periodId == periodId }
        val incomplete = periodSessions.filter { !it.completed }.map { it.sessionIndex }.minOrNull()
        if (incomplete != null) return ActiveSessionChoice.ReuseExisting(incomplete)
        if (periodServiceCount == null) return ActiveSessionChoice.PeriodFull
        if (periodCompletedCount(orderSessions, periodId) >= periodServiceCount) {
            return ActiveSessionChoice.PeriodFull
        }
        val next = nextOrderGlobalSessionIndex(orderSessions) ?: return ActiveSessionChoice.IndexSpaceExhausted
        return ActiveSessionChoice.AllocateNew(next)
    }

    /** 派生的活动轮次状态（G1）；"没有活动范围"不再与"待建行"共用一个 null。 */
    sealed interface ActiveCountState {
        data class ActiveSession(
            val periodId: String,
            val sessionIndex: Int,
            val sessionId: String,
        ) : ActiveCountState

        data class AwaitingSessionRow(val periodId: String, val sessionIndex: Int) : ActiveCountState

        data object AllPeriodsComplete : ActiveCountState

        data class IndexSpaceExhausted(val periodId: String) : ActiveCountState
    }

    /**
     * 派生活动轮次（G1）。
     *
     * 期次按 `(periodIndex, id)` 升序；第一个"未满且有轮次"的期次胜出。行尚未创建时返回
     * [ActiveCountState.AwaitingSessionRow]：索引已按订单全局分配，但**不建行**。
     */
    fun resolveActiveSession(
        orderId: String,
        periods: List<HostingPeriod>,
        sessions: List<HostingSession>,
    ): ActiveCountState {
        val orderSessions = sessions.filter { it.orderId == orderId }
        val countPeriods = periods
            .filter { it.orderId == orderId && it.serviceCount != null }
            .sortedWith(compareBy({ it.periodIndex }, { it.id }))
        if (countPeriods.isEmpty()) return ActiveCountState.AllPeriodsComplete
        countPeriods.forEach { period ->
            when (val choice = selectActiveSession(orderSessions, period.id, period.serviceCount)) {
                is ActiveSessionChoice.IndexSpaceExhausted ->
                    return ActiveCountState.IndexSpaceExhausted(period.id)

                is ActiveSessionChoice.PeriodFull -> Unit

                is ActiveSessionChoice.ReuseExisting -> {
                    val session = orderSessions.firstOrNull {
                        it.periodId == period.id && it.sessionIndex == choice.sessionIndex
                    }
                    if (session != null) {
                        return ActiveCountState.ActiveSession(
                            periodId = period.id,
                            sessionIndex = session.sessionIndex,
                            sessionId = session.id,
                        )
                    }
                    return ActiveCountState.AwaitingSessionRow(period.id, choice.sessionIndex)
                }

                is ActiveSessionChoice.AllocateNew ->
                    return ActiveCountState.AwaitingSessionRow(period.id, choice.sessionIndex)
            }
        }
        return ActiveCountState.AllPeriodsComplete
    }

    /** 任务缓存（G1 五种状态）。缓存只镜像派生状态，从不代表"正在看哪一轮"。 */
    sealed interface TaskScopeCache {
        data class LastScope(
            val sessionId: String,
            val sessionIndex: Int,
            val completedAt: Instant?,
        )

        data class ActiveSession(
            val periodId: String,
            val sessionId: String,
            val sessionIndex: Int,
            val currentValue: BigDecimal,
            val completedAt: Instant?,
            val reachedTarget: Boolean,
        ) : TaskScopeCache

        data class AwaitingSessionRow(
            val periodId: String,
            val sessionIndex: Int,
            val currentValue: BigDecimal,
            val completedAt: Instant?,
            val reachedTarget: Boolean,
        ) : TaskScopeCache

        data class TaskPeriodTerminal(
            val periodId: String,
            val lastScope: LastScope?,
            val currentValue: BigDecimal,
            val completedAt: Instant?,
            val reachedTarget: Boolean,
        ) : TaskScopeCache

        data class OrderTerminal(
            val periodId: String,
            val lastScope: LastScope?,
            val currentValue: BigDecimal,
            val completedAt: Instant?,
            val reachedTarget: Boolean,
        ) : TaskScopeCache

        data class IndexSpaceExhausted(
            val periodId: String,
            val lastScope: LastScope?,
            val currentValue: BigDecimal,
            val completedAt: Instant?,
            val reachedTarget: Boolean,
            val error: String = "SESSION_INDEX_EXHAUSTED",
        ) : TaskScopeCache
    }

    private fun lastScopeOf(
        periods: List<HostingPeriod>,
        sessions: List<HostingSession>,
        periodId: String,
    ): TaskScopeCache.LastScope? {
        val rows = sessions
            .filter { it.periodId == periodId }
            .sortedByDescending { it.sessionIndex }
        val last = rows.firstOrNull() ?: return null
        return TaskScopeCache.LastScope(last.id, last.sessionIndex, last.completedAt)
    }

    /**
     * 任务缓存（G1/F3）。
     *
     * 待建行取 `startValue`、`completedAt` 为 null：绝不继承上一轮的总值与完成时刻，
     * 也绝不为了填缓存而创建轮次行或写入事件。
     */
    fun taskScopeCache(
        task: OrderTask,
        state: ActiveCountState,
        periods: List<HostingPeriod>,
        sessions: List<HostingSession>,
        events: List<TaskProgressEvent>,
    ): TaskScopeCache {
        val startValue = task.startValue
        val targetValue = task.targetValue
        fun reached(current: BigDecimal) = current >= targetValue

        when (state) {
            is ActiveCountState.AllPeriodsComplete -> {
                val periodId = task.periodId.orEmpty()
                val lastScope = periodId.takeIf { it.isNotEmpty() }?.let { lastScopeOf(periods, sessions, it) }
                val current = resolveFor(task, lastScope, startValue, events)
                return TaskScopeCache.OrderTerminal(
                    periodId = periodId,
                    lastScope = lastScope,
                    currentValue = current,
                    completedAt = lastScope?.completedAt,
                    reachedTarget = reached(current),
                )
            }

            is ActiveCountState.IndexSpaceExhausted -> {
                val lastScope = lastScopeOf(periods, sessions, state.periodId)
                val current = resolveFor(task, lastScope, startValue, events, fallbackPeriodId = state.periodId)
                return TaskScopeCache.IndexSpaceExhausted(
                    periodId = state.periodId,
                    lastScope = lastScope,
                    currentValue = current,
                    completedAt = lastScope?.completedAt,
                    reachedTarget = reached(current),
                )
            }

            is ActiveCountState.ActiveSession, is ActiveCountState.AwaitingSessionRow -> Unit
        }

        // 任务所属期次不是活动期次 → 冻结在**该任务自己期次**的最后一个范围。
        val taskPeriodId = task.periodId
        if (taskPeriodId != null && taskPeriodId != state.periodId()) {
            val lastScope = lastScopeOf(periods, sessions, taskPeriodId)
            val current = resolveFor(task, lastScope, startValue, events, fallbackPeriodId = taskPeriodId)
            return TaskScopeCache.TaskPeriodTerminal(
                periodId = taskPeriodId,
                lastScope = lastScope,
                currentValue = current,
                completedAt = lastScope?.completedAt,
                reachedTarget = reached(current),
            )
        }

        if (state is ActiveCountState.AwaitingSessionRow) {
            return TaskScopeCache.AwaitingSessionRow(
                periodId = state.periodId,
                sessionIndex = state.sessionIndex,
                currentValue = startValue,
                completedAt = null,
                reachedTarget = reached(startValue),
            )
        }

        val active = state as ActiveCountState.ActiveSession
        val session = sessions.firstOrNull { it.id == active.sessionId }
        val current = ProgressResolution.resolveScopeTotal(
            events,
            task.id,
            ProgressScope.Session(active.sessionId),
            startValue,
        )
        return TaskScopeCache.ActiveSession(
            periodId = active.periodId,
            sessionId = active.sessionId,
            sessionIndex = active.sessionIndex,
            currentValue = current,
            completedAt = session?.completedAt,
            reachedTarget = reached(current),
        )
    }

    private fun ActiveCountState.periodId(): String = when (this) {
        is ActiveCountState.ActiveSession -> periodId
        is ActiveCountState.AwaitingSessionRow -> periodId
        is ActiveCountState.IndexSpaceExhausted -> periodId
        ActiveCountState.AllPeriodsComplete -> ""
    }

    private fun resolveFor(
        task: OrderTask,
        lastScope: TaskScopeCache.LastScope?,
        startValue: BigDecimal,
        events: List<TaskProgressEvent>,
        fallbackPeriodId: String = task.periodId.orEmpty(),
    ): BigDecimal {
        val scope = if (lastScope != null) {
            ProgressScope.Session(lastScope.sessionId)
        } else {
            ProgressScope.Period(fallbackPeriodId)
        }
        return ProgressResolution.resolveScopeTotal(events, task.id, scope, startValue)
    }

}
