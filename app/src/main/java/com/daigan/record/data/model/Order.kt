package com.daigan.record.data.model

import com.daigan.record.core.time.HostingPeriodClock
import com.daigan.record.core.time.HostingPeriodSelection
import com.daigan.record.core.time.ResolvedHostingPeriod
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class OrderType { SHORT_TASK, HOSTING }

/**
 * How a hosting order measures its agreed work.
 *
 * [DATE_RANGE] is the shipped behaviour: each period has a start/end date and work is
 * resolved from the game calendar. [COUNT_BASED] (打卡式托管, UF-033) has no business
 * start/end date and no fixed weekdays: the user agrees on a positive number of
 * check-in rounds ("次"), and one round counts only when every task of that round is
 * done. The mode is persisted explicitly; it is never inferred from dates, and no
 * count is ever derived from how many calendar days have passed.
 */
enum class HostingMode { DATE_RANGE, COUNT_BASED }

/**
 * 数值任务的累计口径（schema 15，UF-038）。
 *
 * [PER_OCCURRENCE] 是既有语义：每个发生槽（每天/每周…）独立目标，值落在 occurrence 上。
 * [CUMULATIVE] 表示"输入即当前累计总值"，观测以 append-only 事件保存。
 * 持久值 NULL 一律读作 PER_OCCURRENCE —— 普通周期任务永不被改写。
 */
enum class NumericMode { PER_OCCURRENCE, CUMULATIVE }

/**
 * Captured at creation. Phase 2 never records receipts; the value is shown so the
 * settlement choice survives. Payment recording is explicitly pending Phase 3.
 */
enum class PaymentMode { ONE_TIME, INSTALLMENTS }

enum class ProgressType { CHECKBOX, NUMERIC }

/**
 * Occurrence recurrence of one task.
 *
 * [ONE_TIME] was added in Phase 4 for template tasks that only make sense inside a
 * short order: the work is done once and has no game-day or game-week occurrence.
 * It is **not** a daily task: [com.daigan.record.data.model.OccurrenceRules] produces
 * no slot for it, and a hosting order rejects it instead of silently treating it as
 * `DAILY`. Short orders never generate occurrences for any recurrence, so the value is
 * an explicit "one-off" marker there rather than an inferred default.
 */
enum class RecurrenceType { ONE_TIME, DAILY, WEEKLY, SELECTED_WEEKDAYS, EVERY_N_DAYS, MONTHLY }

enum class BatchScope { HOSTING_ORDER, HOSTING_ALL, SHORT_ORDER }

enum class BatchItemType { OCCURRENCE, SHORT_TASK }

data class Order(
    val id: String,
    val accountId: String,
    val type: OrderType,
    val title: String,
    /** Negotiated total for a short order. Hosting prices live on each period. */
    val priceCents: Long?,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String?,
    val refresh: GameRefreshRulesDomain,
    val createdAt: Instant,
    val completedAt: Instant?,
    /** Truthful template provenance captured at creation; null for legacy/no-template. */
    val sourceTemplateId: String? = null,
    val sourceTemplateName: String? = null,
    val sourceTemplateVersion: String? = null,
    val cancelledAt: Instant? = null,
    val archivedAt: Instant? = null,
    /** Persisted hosting mode; null on legacy rows reads as [HostingMode.DATE_RANGE]. */
    val hostingMode: HostingMode = HostingMode.DATE_RANGE,
    /** Agreed number of check-in rounds; only meaningful for count-based hosting. */
    val targetSessions: Int? = null,
    /**
     * Optional packed ARGB colour of this order (UF-028). Order-scoped on purpose:
     * the same account may host orders in different colours. Null = not coloured.
     */
    val colorArgb: Int? = null,
    /** 每月刷新日（shared schema 15）；空集合表示按周刷新。 */
    val monthlyRefreshDays: Set<Int> = emptySet(),
) {
    val archived: Boolean get() = archivedAt != null
    val cancelled: Boolean get() = cancelledAt != null
    val completed: Boolean get() = completedAt != null
    val isHosting: Boolean get() = type == OrderType.HOSTING
    val isShort: Boolean get() = type == OrderType.SHORT_TASK

    /** 打卡式托管: counted by completed rounds, not by elapsed calendar days. */
    val countBased: Boolean
        get() = type == OrderType.HOSTING && hostingMode == HostingMode.COUNT_BASED
}

/** Domain copy of [com.daigan.record.core.time.GameRefreshRules] without a UI dependency. */
data class GameRefreshRulesDomain(
    val zoneId: ZoneId,
    val dailyRefresh: LocalTime,
    val weeklyRefreshDay: DayOfWeek,
    val weeklyRefreshTime: LocalTime,
    /** 每月刷新日；非空时 WEEKLY 使用刷新到刷新的月度窗口。 */
    val monthlyRefreshDays: Set<Int> = emptySet(),
) {
    val dailyRefreshMinutes: Int get() = dailyRefresh.hour * 60 + dailyRefresh.minute
    val weeklyRefreshDayIso: Int get() = weeklyRefreshDay.value
    val weeklyRefreshMinutes: Int get() = weeklyRefreshTime.hour * 60 + weeklyRefreshTime.minute
}

data class HostingPeriod(
    val id: String,
    val orderId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val priceCents: Long,
    val refresh: GameRefreshRulesDomain,
    val createdAt: Instant,
    val renewalOperationId: String?,
    val serviceDays: Int? = null,
    val serviceWeekdays: Set<DayOfWeek> = emptySet(),
    /** 按次数模式：本**期次**的目标次数（schema 15）；null = 按日期/工作日。 */
    val serviceCount: Int? = null,
    /** 每月刷新日（schema 15）；空集合表示按周刷新。 */
    val monthlyRefreshDays: Set<Int> = emptySet(),
) {
    val durationDays: Int
        get() = (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 1

    /**
     * 计次期次的实际服务起点（F1）。
     *
     * 计次期次的 `startDate`/`endDate` 只是一对**内部锚点**：它们存在是为了满足
     * `(orderId, startDate)` 唯一约束，同日续期会把它推到下一天，因此**不能**当作服务下界
     * （那样会把今天的合法进度判成"早于期次开始"）。协议真正生效的游戏日，是期次创建时刻
     * 按该期次自己的刷新规则换算出的游戏日；它只会早于或等于锚点，所以既不会推迟服务，
     * 也不会放过协议之前的历史。
     *
     * 按日期期次没有这个区分，直接就是 [startDate]。
     */
    val serviceStartDate: LocalDate
        get() {
            if (serviceCount == null) return startDate
            // 时刻不可用时退回锚点：宁可严格，也不凭空放宽历史。
            if (createdAt.toEpochMilli() <= 0L) return startDate
            val created = com.daigan.record.core.time.GameCalendar.gameDate(createdAt, refresh.toCoreForRules())
            return if (created.isBefore(startDate)) created else startDate
        }

    fun remainingServiceDays(today: LocalDate): Int {
        val from = maxOf(startDate, today)
        if (from.isAfter(endDate)) return 0
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }
            .count { serviceDays == null || it.dayOfWeek in serviceWeekdays }
    }
}

/**
 * One check-in round of a count-based hosting order (UF-033).
 *
 * The row is the persistent identity of a round, not a date: several rounds may be
 * completed on the same day, and a day without activity creates nothing. `sessionIndex`
 * starts at 1 and is unique per order, which is what makes repeated taps, retries and
 * concurrent completion count the same round exactly once.
 *
 * Rows are never deleted when the total is corrected: a reopened round keeps its row and
 * merely loses [completedAt], so previous rounds stay available as history.
 */
data class HostingSession(
    val id: String,
    val orderId: String,
    val sessionIndex: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
    val completionBatchId: String? = null,
    /**
     * 归属期次（schema 15）。数据库列是 NOT NULL；领域模型保留可空是为了让"尚未
     * 归属"的历史快照仍能被读取，写入路径必须给出真实期次。
     */
    val periodId: String? = null,
) {
    val completed: Boolean get() = completedAt != null
}

data class OrderTask(
    val id: String,
    val orderId: String,
    /** Null for short-order tasks; hosting tasks always belong to one period snapshot. */
    val periodId: String?,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val currentValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    val weekdays: Set<DayOfWeek>,
    val everyNDays: Int?,
    val sortOrder: Int,
    val completedAt: Instant?,
    val monthDay: Int? = null,
    val monthDays: Set<Int> = setOfNotNull(monthDay),
    val retiredAt: Instant? = null,
    val activeFrom: LocalDate? = null,
    /** 任务生效截止日（schema 15）。 */
    val activeUntil: LocalDate? = null,
    /** 数值口径（schema 15）；null 读作 PER_OCCURRENCE。 */
    val numericMode: NumericMode? = null,
    /** 任务自身颜色（不透明 ARGB，schema 15）；null = 继承订单色。 */
    val colorArgb: Int? = null,
) {
    val completed: Boolean get() = completedAt != null

    /** Numeric tasks complete when the recorded value reaches the target. */
    val reachedTarget: Boolean get() = currentValue >= targetValue
}

data class TaskOccurrence(
    val id: String,
    val taskDefinitionId: String,
    val occurrenceKey: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant?,
    val recordedAt: Instant?,
    val backfilled: Boolean,
    val completionBatchId: String?,
    val value: BigDecimal?,
    val createdAt: Instant,
) {
    val completed: Boolean get() = completedAt != null
}

/** Snapshot of the currently actionable slot for a task. */
data class OccurrenceSlot(
    val key: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    /**
     * Unclamped weekly window identity. Weekly windows are identified by their real
     * refresh date; display dates may be clamped to the hosting period.
     */
    val weeklyWindowStart: LocalDate? = null,
)

data class ShortWorkTask(
    val task: OrderTask,
    val completed: Boolean,
    val currentValue: BigDecimal,
)

data class ShortWorkOrder(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val tasks: List<ShortWorkTask>,
    val today: LocalDate,
) {
    val overdue: Boolean
        get() = order.deadlineDate?.isBefore(today) == true

    val allCompleted: Boolean get() = tasks.isNotEmpty() && tasks.all { it.completed }
}

data class TodayHostingTask(
    val task: OrderTask,
    val occurrenceId: String?,
    val slot: OccurrenceSlot,
    val completed: Boolean,
    val value: BigDecimal?,
    val windowLabel: String?,
)

data class TodayHostingOrder(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val period: HostingPeriod,
    val today: LocalDate,
    val status: com.daigan.record.core.time.HostingStatus,
    val tasks: List<TodayHostingTask>,
    /** True for 打卡式托管; its [status] is never EXPIRED/NOT_STARTED. */
    val countBased: Boolean = false,
    val completedSessions: Int = 0,
    val targetSessions: Int? = null,
    /** 1-based round this card's tasks belong to; null once the target is reached. */
    val activeSessionIndex: Int? = null,
) {
    val allTasksCompleted: Boolean get() = tasks.isNotEmpty() && tasks.all { it.completed }
    val hasActionableTasks: Boolean get() = tasks.isNotEmpty()
    val expired: Boolean
        get() = !countBased && status == com.daigan.record.core.time.HostingStatus.EXPIRED

    /** Target reached: the agreed work is done even though no date has passed. */
    val sessionsComplete: Boolean
        get() = countBased && targetSessions != null && completedSessions >= checkNotNull(targetSessions)

    val remainingSessions: Int?
        get() = targetSessions?.let { (it - completedSessions).coerceAtLeast(0) }
}

data class OrderSummary(
    val order: Order,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    /**
     * The period "today" refers to (see [OrderDetail.currentPeriod]); null for short
     * orders. Counts below belong to this same period so an early renewal never makes a
     * running order look like an unstarted one.
     */
    val currentPeriod: HostingPeriod?,
    val taskCount: Int,
    val completedTaskCount: Int,
) {
    val accountLabel: String get() = "$gameName · $server"
}

data class OrderHistoryEntry(
    val periodIndex: Int?,
    val periodId: String?,
    val taskId: String,
    val occurrenceId: String?,
    val taskTitle: String,
    val attributedDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant,
    val recordedAt: Instant,
    val backfilled: Boolean,
    val value: BigDecimal?,
    val attachments: List<Attachment>,
) {
    /**
     * Stable list identity.
     *
     * `taskId + attributedDate` is **not** unique: a weekly task whose previous window
     * still overlaps the first game day of a period (weekly refresh later in the day than
     * the daily refresh, e.g. the default 04:00 / 05:00) produces two occurrences for the
     * same task that are both clamped to the period's first date, so two rows share a
     * date. LazyColumn rejects a repeated key, which surfaced as a detail-page crash.
     * The occurrence row is the real identity; short-order tasks have none yet.
     */
    val stableKey: String
        get() = occurrenceId?.let { "occurrence-$it" } ?: "task-$taskId"
}

data class Attachment(
    val id: String,
    val taskDefinitionId: String?,
    val occurrenceId: String?,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

data class OrderDetail(
    val order: Order,
    val owner: Owner,
    val account: GameAccount,
    /** Short tasks (periodId null) and every host period's task snapshots. */
    val tasks: List<OrderTask>,
    /** Hosting periods, newest first. Empty for short orders. */
    val periods: List<HostingPeriod>,
    /** Every persisted occurrence for this order. */
    val occurrences: List<TaskOccurrence>,
    val attachments: List<Attachment>,
    /** Every receipt, including voided and superseded rows, newest actual time first. */
    val receipts: List<Receipt>,
    val receiptCorrections: List<ReceiptCorrectionRecord>,
    /** Agreed-price corrections, oldest first; receipts themselves are never rewritten. */
    val priceChanges: List<OrderPriceChangeRecord> = emptyList(),
    val today: LocalDate,
    /** Clock instant used to resolve the current weekly window. */
    val now: Instant,
    /** Check-in rounds of a count-based order, oldest first. Empty for every other mode. */
    val sessions: List<HostingSession> = emptyList(),
) {
    /**
     * The period plus the game date it is evaluated at, both resolved from each period's
     * own refresh snapshot. [today] above is the same date for hosting orders, so screens
     * that read [today] and screens that read [currentPeriod] cannot disagree.
     */
    val resolvedPeriod: ResolvedHostingPeriod<HostingPeriod>?
        get() = HostingPeriodClock.resolve(
            periods = periods,
            now = now,
            rulesOf = { it.refresh.toCoreForRules() },
            startDate = { it.startDate },
            endDate = { it.endDate },
            periodIndex = { it.periodIndex },
        )

    /** The period whose inclusive service window contains its own game date, if any. */
    val effectivePeriod: HostingPeriod?
        get() = HostingPeriodClock.active(
            periods = periods,
            now = now,
            rulesOf = { it.refresh.toCoreForRules() },
            startDate = { it.startDate },
            endDate = { it.endDate },
            periodIndex = { it.periodIndex },
        )?.period

    /** Last scheduled period (highest index). Renewals are appended after this one. */
    val latestPeriod: HostingPeriod?
        get() = HostingPeriodSelection.latest(periods) { it.periodIndex }

    /**
     * The period a "today" surface is about: the effective one, otherwise the next one
     * to start, otherwise the most recently ended one. Never simply "the newest row":
     * that would point today's work at a future, already renewed period.
     */
    val currentPeriod: HostingPeriod?
        get() = resolvedPeriod?.period

    /** Periods that ended before the displayed period, newest first (history). */
    val previousPeriods: List<HostingPeriod>
        get() {
            val current = currentPeriod ?: return emptyList()
            return periods
                .filter { it.endDate.isBefore(current.startDate) }
                .sortedByDescending { it.periodIndex }
        }

    /** Already renewed but not started yet, oldest first. */
    val futurePeriods: List<HostingPeriod>
        get() {
            val current = currentPeriod ?: return emptyList()
            return periods
                .filter { it.startDate.isAfter(current.endDate) }
                .sortedBy { it.periodIndex }
        }

    val isHosting: Boolean get() = order.isHosting

    /** 打卡式托管: progress is measured in completed rounds, never in dates. */
    val countBased: Boolean get() = order.countBased

    /**
     * Count periods, each with its own target. `orders.targetSessions` is only a mirror of
     * the **latest** one (highest periodIndex); it never terminates another period.
     */
    val countPeriodsWithTargets: List<HostingPeriod>
        get() = periods.filter { it.serviceCount != null }

    val targetSessionCount: Int?
        get() {
            val perPeriod = countPeriodsWithTargets.sumOf { it.serviceCount ?: 0 }
            return perPeriod.takeIf { it > 0 }
                ?: order.targetSessions?.takeIf { it > 0 }
        }

    /** Distinct completed rounds; duplicate rows can never inflate the total. */
    val completedSessionCount: Int get() = CountHosting.completedCount(sessions)

    val remainingSessionCount: Int?
        get() = targetSessionCount?.let { (it - completedSessionCount).coerceAtLeast(0) }

    val countSessionsComplete: Boolean
        get() {
            if (!countBased) return false
            val countPeriods = countPeriodsWithTargets
            return if (countPeriods.isEmpty()) {
                CountHosting.isComplete(sessions, order.targetSessions)
            } else {
                countPeriods.all { period ->
                    CountHosting.periodCompletedCount(sessions, period.id) >=
                        (period.serviceCount ?: 0)
                }
            }
        }

    /** The lowest round that is not complete yet, or null when every period is met. */
    val activeSession: HostingSession? get() = CountHosting.activeSession(sessions)

    /**
     * The round index the next write will materialise (G1), or null when no further round
     * may start. Rows are created lazily, so after finishing round 1 there is no round-2
     * row yet: [sessions] stays the persisted set and this is the allocated index.
     */
    val awaitingSessionIndex: Int?
        get() = when (val state = CountHosting.resolveActiveSession(order.id, periods, sessions)) {
            is CountHosting.ActiveCountState.AwaitingSessionRow -> state.sessionIndex
            else -> null
        }

    /**
     * The round the user is working on: the lowest open persisted row, otherwise the
     * allocated-but-unwritten index. Read-only derivation — never creates a row.
     */
    val activeSessionIndex: Int? get() = activeSession?.sessionIndex ?: awaitingSessionIndex

    /** Paid and outstanding are always derived from prices and receipts that already arrived. */
    fun balance(): OrderBalance = Balances.order(order, periods, receipts, today, now)

    fun receiptsForPeriod(periodId: String?): List<Receipt> =
        receipts.filter { it.periodId == periodId }

    fun correctionFor(receiptId: String): ReceiptCorrectionRecord? =
        receiptCorrections.filter { it.receiptId == receiptId }
            .maxByOrNull { it.createdAt }

    fun tasksForPeriod(periodId: String?): List<OrderTask> =
        tasks.filter { it.periodId == periodId && it.retiredAt == null }.sortedBy { it.sortOrder }

    fun occurrencesForTask(taskId: String): List<TaskOccurrence> =
        occurrences.filter { it.taskDefinitionId == taskId }

    fun attachmentsForTask(taskId: String): List<Attachment> =
        attachments.filter { it.taskDefinitionId == taskId }

    fun attachmentsForOccurrence(occurrenceId: String): List<Attachment> =
        attachments.filter { it.occurrenceId == occurrenceId }

    fun currentStatus(): com.daigan.record.core.time.HostingStatus? {
        if (order.cancelled) return null
        // Count-based hosting never expires or starts late: it is either still in
        // progress or finished because the agreed number of rounds was reached.
        if (countBased) {
            return if (countSessionsComplete) null else com.daigan.record.core.time.HostingStatus.ACTIVE
        }
        val period = currentPeriod ?: return null
        return com.daigan.record.core.time.GameCalendar.hostingStatus(today, period.startDate, period.endDate)
    }

    /** Current actionable hosting tasks, resolved with the same rules as today's page. */
    fun currentHostingTasks(): List<TodayHostingTask> {
        if (order.cancelled) return emptyList()
        if (countBased) return countSessionTasks()
        // Only the period containing today can produce actionable work; a future period
        // created by an early renewal must not surface tasks before it starts.
        val period = effectivePeriod ?: return emptyList()
        if (com.daigan.record.core.time.GameCalendar.hostingStatus(today, period.startDate, period.endDate) !=
            com.daigan.record.core.time.HostingStatus.ACTIVE
        ) {
            return emptyList()
        }
        val rules = period.refresh.toCoreForRules()
        val occurrencesByKey = occurrences.associateBy { it.taskDefinitionId to it.occurrenceKey }
        return tasksForPeriod(period.id).mapNotNull { task ->
            val slot = OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                monthDay = task.monthDay,
                monthDays = task.monthDays,
                activeFrom = task.activeFrom,
                activeUntil = task.activeUntil,
                retiredAt = task.retiredAt,
                serviceWeekdays = period.serviceWeekdays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: return@mapNotNull null
            val occurrence = occurrencesByKey[task.id to slot.key]
            TodayHostingTask(
                task = task,
                occurrenceId = occurrence?.id,
                slot = slot,
                completed = occurrence?.completedAt != null,
                value = occurrence?.value,
                windowLabel = OccurrenceRules.windowLabel(slot),
            )
        }
    }

    /**
     * Tasks of the round in progress. Every active task is part of the round regardless
     * of its recurrence: in count-based hosting a round is a fresh copy of the agreed
     * task list, and completing each task once completes the round.
     */
    private fun countSessionTasks(): List<TodayHostingTask> {
        // The active state names the owning period, so a renewed agreement's rounds are
        // never rendered from (or judged by) the first period.
        val state = CountHosting.resolveActiveSession(order.id, periods, sessions)
        val sessionIndex = when (state) {
            is CountHosting.ActiveCountState.ActiveSession -> state.sessionIndex
            is CountHosting.ActiveCountState.AwaitingSessionRow -> state.sessionIndex
            else -> return emptyList()
        }
        val periodId = when (state) {
            is CountHosting.ActiveCountState.ActiveSession -> state.periodId
            is CountHosting.ActiveCountState.AwaitingSessionRow -> state.periodId
            else -> return emptyList()
        }
        val period = periods.firstOrNull { it.id == periodId } ?: return emptyList()
        val key = CountHosting.occurrenceKey(sessionIndex)
        val slot = OccurrenceSlot(
            key = key,
            attributedDate = today,
            windowStart = today,
            windowEnd = today,
        )
        val occurrencesByKey = occurrences.associateBy { it.taskDefinitionId to it.occurrenceKey }
        return tasksForPeriod(period.id).map { task ->
            val occurrence = occurrencesByKey[task.id to key]
            TodayHostingTask(
                task = task,
                occurrenceId = occurrence?.id,
                slot = slot,
                completed = occurrence?.completedAt != null,
                value = occurrence?.value,
                windowLabel = null,
            )
        }
    }

    /** Completion history, newest first. Weekly entries keep their window label. */
    fun historyEntries(): List<OrderHistoryEntry> {
        val tasksById = tasks.associateBy { it.id }
        val periodsById = periods.associateBy { it.id }
        val occurrenceEntries = occurrences.mapNotNull { occurrence ->
            if (occurrence.completedAt == null) return@mapNotNull null
            val task = tasksById[occurrence.taskDefinitionId] ?: return@mapNotNull null
            val period = task.periodId?.let { periodsById[it] }
            OrderHistoryEntry(
                periodIndex = period?.periodIndex,
                periodId = period?.id,
                taskId = task.id,
                occurrenceId = occurrence.id,
                taskTitle = task.title,
                attributedDate = occurrence.attributedDate,
                windowStart = occurrence.windowStart,
                windowEnd = occurrence.windowEnd,
                completedAt = occurrence.completedAt,
                recordedAt = occurrence.recordedAt ?: occurrence.completedAt,
                backfilled = occurrence.backfilled,
                value = occurrence.value,
                attachments = attachments.filter { it.occurrenceId == occurrence.id },
            )
        }
        val shortEntries = tasks.mapNotNull { task ->
            val completedAt = task.completedAt ?: return@mapNotNull null
            OrderHistoryEntry(
                periodIndex = null,
                periodId = null,
                taskId = task.id,
                occurrenceId = null,
                taskTitle = task.title,
                attributedDate = today,
                windowStart = null,
                windowEnd = null,
                completedAt = completedAt,
                recordedAt = completedAt,
                backfilled = false,
                value = task.currentValue,
                attachments = attachments.filter { it.taskDefinitionId == task.id },
            )
        }
        return (occurrenceEntries + shortEntries).sortedByDescending { it.completedAt }
    }
}

private fun GameRefreshRulesDomain.toCoreForRules(): com.daigan.record.core.time.GameRefreshRules =
    com.daigan.record.core.time.GameRefreshRules(
        zoneId = zoneId,
        dailyRefresh = dailyRefresh,
        weeklyRefreshDay = weeklyRefreshDay,
        weeklyRefreshTime = weeklyRefreshTime,
        monthlyRefreshDays = monthlyRefreshDays,
    )

data class UndoConflict(
    val description: String,
    val reason: String,
)

/** One audited agreed-price correction (UF-012). */
data class OrderPriceChangeRecord(
    val id: String,
    val operationId: String,
    val orderId: String,
    /** Null for a short-order price; set for a hosting period price. */
    val periodId: String?,
    val previousPriceCents: Long,
    val newPriceCents: Long,
    val reason: String?,
    val createdAt: Instant,
)

/**
 * What a permanent deletion removed (UF-007/UF-009). The confirmation dialog shows
 * these counts first; the repository returns the same numbers after the transaction.
 */
data class OrderDeletionSummary(
    val orderId: String,
    val occurrenceCount: Int,
    val receiptCount: Int,
    val receiptSumCents: Long,
    val attachmentCount: Int,
    val periodCount: Int,
) {
    val isEmpty: Boolean
        get() = occurrenceCount == 0 && receiptCount == 0 && attachmentCount == 0 && periodCount <= 1
}

data class BulkCompletionResult(
    val batchId: String,
    val completedCount: Int,
    val skippedCount: Int,
    val alreadyApplied: Boolean,
)

data class UndoResult(
    val restoredCount: Int,
    val conflicts: List<UndoConflict>,
    val alreadyUndone: Boolean,
)

data class RenewalResult(
    val periodId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val alreadyApplied: Boolean,
    /** True for a count-period renewal, which has no calendar service deadline. */
    val countBased: Boolean = false,
    /** The new period's own agreed rounds (count renewal only). */
    val targetSessions: Int? = null,
)
