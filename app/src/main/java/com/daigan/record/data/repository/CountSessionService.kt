package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.HostingSessionDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.dao.TaskProgressEventDao
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.HostingSessionEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressResolution
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.TaskProgressEvent
import com.daigan.record.data.model.parseMonthlyDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One round together with the period that owns it.
 *
 * [sessionId] is null while the round has only an allocated index
 * ([CountHosting.ActiveCountState.AwaitingSessionRow]): the row is created on the first
 * write, never for a read. [tasks] is the *period-local* roster of that session, which is
 * what makes "the first period is the only container" impossible.
 */
data class CountRound(
    val orderId: String,
    val period: HostingPeriodEntity,
    val sessionIndex: Int,
    val sessionId: String?,
    val attributedDate: LocalDate,
    /** 当前可操作的任务（未退休）：界面、批量完成与快照比较使用。 */
    val tasks: List<TaskDefinitionEntity>,
    /**
     * 该轮次**归属游戏日**的历史应做集：从这个期次的完整任务名单出发，按
     * [CountHosting.isDueInRound] 判定。之后才退休的任务仍然在这里出现——它们当时确实
     * 应做（F2）。完成判定只认这个集合，界面仍只列 [tasks]。
     */
    val dueTasks: List<TaskDefinitionEntity>,
    val awaitingRow: Boolean,
) {
    val key: String get() = CountHosting.occurrenceKey(sessionIndex)
}

/**
 * The single write/recompute boundary for 打卡式托管 rounds (schema 15 / G1-G3).
 *
 * Both the ordinary repository commands (`setOccurrenceCompleted`,
 * `completeHostingOrder`, ownership picks) and the cumulative progress commands
 * (`ensureCountSession`, `submitTotal`) go through here, so no path can keep an older
 * "one container period / order-wide target / order-wide roster" assumption.
 *
 * All methods run inside the caller's dataset write transaction; when called standalone
 * they open one. Nested `withTransaction`/`DatasetGate.withWrite` re-enters, so this does
 * not change the existing deadlock behaviour.
 */
interface CountSessionService {
    suspend fun resolveActive(orderId: String): CountHosting.ActiveCountState

    /** Read-only round lookup for an existing session row. Never creates anything. */
    suspend fun roundFor(orderId: String, sessionIndex: Int): CountRound?

    /**
     * Read-only lookup of every existing round of one order in a single pass. Same
     * attributed-date and historical due-set rules as [roundFor], but loads each task's
     * event history once and reuses it across all rounds. Never creates anything.
     */
    suspend fun roundsFor(orderId: String): List<CountRound>

    /** The round new work belongs to; may be an allocated-but-unwritten index. */
    suspend fun activeRound(orderId: String): CountRound?

    /** Round of one explicit period: its earliest open session, else its next index. */
    suspend fun roundForPeriod(orderId: String, periodId: String): CountRound?

    suspend fun ensure(
        orderId: String,
        requestedSessionIndex: Int? = null,
        expectedGeneration: Long? = null,
    ): EnsureSessionResult

    /** Re-derives every round's completion and completes/allows the next round. */
    suspend fun reconcile(orderId: String, now: Instant)

    /** Reopens one round when its own period-local due set is no longer complete. */
    suspend fun reopenIfIncomplete(orderId: String, sessionIndex: Int, now: Instant)
}

@Singleton
class DefaultCountSessionService @Inject constructor(
    private val db: RecordDatabase,
    private val orderDao: OrderDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val eventDao: TaskProgressEventDao,
    private val sessionDao: HostingSessionDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
    private val timeProvider: TimeProvider,
) : CountSessionService {

    override suspend fun resolveActive(orderId: String): CountHosting.ActiveCountState {
        val periods = orderDao.findPeriods(orderId).map { it.toModel() }
        val sessions = sessionDao.findForOrder(orderId).map { it.toModel() }
        return CountHosting.resolveActiveSession(orderId, periods, sessions)
    }

    override suspend fun roundFor(orderId: String, sessionIndex: Int): CountRound? {
        val session = sessionDao.findByIndex(orderId, sessionIndex) ?: return null
        val period = session.periodId?.let { orderDao.findPeriod(it) } ?: return null
        if (period.orderId != orderId) return null
        val attributed = attributedDate(orderId, session)
        val roster = taskDao.findForPeriod(period.id)
        return CountRound(
            orderId = orderId,
            period = period,
            sessionIndex = sessionIndex,
            sessionId = session.id,
            attributedDate = attributed,
            tasks = roster.filter { it.retiredAt == null },
            dueTasks = dueTasks(roster, attributed, refreshOf(period)),
            awaitingRow = false,
        )
    }

    /**
     * Bulk read-only counterpart of [roundFor] for one whole history request: sessions,
     * periods, rosters, occurrences and each task's event history are loaded once and
     * indexed in memory, so many rounds sharing the same tasks never reload the same
     * event history. Attributed date and due set reuse the same private rules as
     * [roundFor] ([attributedDateFor], [dueTasks]) — no second rule implementation.
     * Never creates anything.
     */
    override suspend fun roundsFor(orderId: String): List<CountRound> {
        val sessions = sessionDao.findForOrder(orderId)
        if (sessions.isEmpty()) return emptyList()
        val periods = orderDao.findPeriods(orderId).associateBy { it.id }
        val rosterByPeriod = taskDao.findForOrder(orderId).groupBy { it.periodId }
        val occurrences = occurrenceDao.findForOrder(orderId)
        val taskIds = rosterByPeriod.values.flatten().map { it.id }.distinct()
        val eventsByTask: Map<String, List<TaskProgressEvent>> =
            if (taskIds.isEmpty()) {
                emptyMap()
            } else {
                eventDao.findForTasks(taskIds).map { it.toEventModel() }.groupBy { it.taskDefinitionId }
            }
        return sessions.mapNotNull { session ->
            val period = session.periodId?.let { periods[it] } ?: return@mapNotNull null
            if (period.orderId != orderId) return@mapNotNull null
            val key = CountHosting.occurrenceKey(session.sessionIndex)
            val roster = rosterByPeriod[period.id].orEmpty()
            val attributed = attributedDateFor(
                key = key,
                sessionId = session.id,
                createdAt = session.createdAt,
                tasks = roster,
                eventsByTask = eventsByTask,
                occurrences = occurrences,
                rules = refreshOf(period),
            ) ?: GameCalendar.gameDate(session.createdAt, refreshOf(period))
            CountRound(
                orderId = orderId,
                period = period,
                sessionIndex = session.sessionIndex,
                sessionId = session.id,
                attributedDate = attributed,
                tasks = roster.filter { it.retiredAt == null },
                dueTasks = dueTasks(roster, attributed, refreshOf(period)),
                awaitingRow = false,
            )
        }
    }

    override suspend fun activeRound(orderId: String): CountRound? {
        val order = orderDao.findById(orderId) ?: return null
        if (!isCountOrder(order)) return null
        return when (val state = resolveActive(orderId)) {
            is CountHosting.ActiveCountState.ActiveSession ->
                roundFor(orderId, state.sessionIndex)

            is CountHosting.ActiveCountState.AwaitingSessionRow -> {
                val period = orderDao.findPeriod(state.periodId) ?: return null
                val attributed = GameCalendar.gameDate(timeProvider.now(), refreshOf(period))
                val roster = taskDao.findForPeriod(period.id)
                CountRound(
                    orderId = orderId,
                    period = period,
                    sessionIndex = state.sessionIndex,
                    sessionId = null,
                    attributedDate = attributed,
                    tasks = roster.filter { it.retiredAt == null },
                    dueTasks = dueTasks(roster, attributed, refreshOf(period)),
                    awaitingRow = true,
                )
            }

            CountHosting.ActiveCountState.AllPeriodsComplete,
            is CountHosting.ActiveCountState.IndexSpaceExhausted,
            -> null
        }
    }

    /**
     * The round a specific period should show: its earliest open session (by order-global
     * index), else its next allocated index. A finished period returns its last session
     * so history stays reachable. Never creates a row.
     */
    override suspend fun roundForPeriod(orderId: String, periodId: String): CountRound? {
        val period = orderDao.findPeriod(periodId) ?: return null
        if (period.orderId != orderId) return null
        val sessions = sessionDao.findForOrder(orderId).map { it.toModel() }
        val periodSessions = sessions.filter { it.periodId == periodId }
        val open = periodSessions.filter { !it.completed }.minByOrNull { it.sessionIndex }
        if (open != null) return roundFor(orderId, open.sessionIndex)
        val next = CountHosting.selectActiveSession(
            orderSessions = sessions,
            periodId = periodId,
            periodServiceCount = period.serviceCount,
        )
        return when (next) {
            is CountHosting.ActiveSessionChoice.ReuseExisting -> roundFor(orderId, next.sessionIndex)
            is CountHosting.ActiveSessionChoice.AllocateNew -> {
                val attributed = GameCalendar.gameDate(timeProvider.now(), refreshOf(period))
                val roster = taskDao.findForPeriod(period.id)
                CountRound(
                    orderId = orderId,
                    period = period,
                    sessionIndex = next.sessionIndex,
                    sessionId = null,
                    attributedDate = attributed,
                    tasks = roster.filter { it.retiredAt == null },
                    dueTasks = dueTasks(roster, attributed, refreshOf(period)),
                    awaitingRow = true,
                )
            }

            CountHosting.ActiveSessionChoice.PeriodFull ->
                periodSessions.maxByOrNull { it.sessionIndex }?.sessionIndex?.let {
                    roundFor(orderId, it)
                }

            CountHosting.ActiveSessionChoice.IndexSpaceExhausted ->
                periodSessions.maxByOrNull { it.sessionIndex }?.sessionIndex?.let {
                    roundFor(orderId, it)
                }
        }
    }

    override suspend fun ensure(
        orderId: String,
        requestedSessionIndex: Int?,
        expectedGeneration: Long?,
    ): EnsureSessionResult = db.withTransaction {
        // Ordering matters (F4): identity and safety guards first, then an **existing**
        // round may be returned unchanged, and only a genuinely new round is subject to
        // the completed/full rejection. Replaying a successful ensure after the work (or
        // the whole order) finished must not fail.
        DatasetExpectation.requireCurrent(expectedGeneration)
        val order = orderDao.findById(orderId)
            ?: throw ProgressCommandException("ORDER_NOT_FOUND", "订单不存在")
        requireWritableCountOrder(order)

        if (requestedSessionIndex != null) {
            sessionDao.findByIndex(orderId, requestedSessionIndex)?.let { row ->
                val period = orderDao.findPeriod(row.periodId)
                if (period == null || period.orderId != orderId || period.serviceCount == null) {
                    throw ProgressCommandException(
                        "SCOPE_OWNERSHIP_INVALID",
                        "请求的轮次不属于该订单的计次期次",
                    )
                }
                return@withTransaction EnsureSessionResult(row, created = false)
            }
        }

        // A completed count order never receives a new round row; corrections go through
        // the existing event history instead.
        if (order.completedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单已完成")
        }
        val state = resolveActive(orderId)

        if (requestedSessionIndex != null) {
            val allocatedIndex = when (state) {
                is CountHosting.ActiveCountState.ActiveSession -> state.sessionIndex
                is CountHosting.ActiveCountState.AwaitingSessionRow -> state.sessionIndex
                else -> null
            }
            if (allocatedIndex != requestedSessionIndex) {
                throw ProgressCommandException(
                    "SCOPE_OWNERSHIP_INVALID",
                    "请求的轮次不存在或不再属于活动范围",
                )
            }
        }

        when (state) {
            is CountHosting.ActiveCountState.ActiveSession -> {
                val existing = sessionDao.findByIndex(orderId, state.sessionIndex)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "活动轮次行缺失")
                EnsureSessionResult(existing, created = false)
            }

            CountHosting.ActiveCountState.AllPeriodsComplete ->
                throw ProgressCommandException("PERIOD_FULL", "当前期次已完成，请续期或新建订单")

            is CountHosting.ActiveCountState.IndexSpaceExhausted ->
                throw ProgressCommandException("SESSION_INDEX_EXHAUSTED", "轮次编号已用尽")

            is CountHosting.ActiveCountState.AwaitingSessionRow -> {
                val period = orderDao.findPeriod(state.periodId)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次不存在")
                if (period.orderId != orderId || period.serviceCount == null) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次期次归属无效")
                }
                val now = timeProvider.now()
                val inserted = sessionDao.insertIgnore(
                    HostingSessionEntity(
                        id = UUID.randomUUID().toString(),
                        orderId = orderId,
                        sessionIndex = state.sessionIndex,
                        createdAt = now,
                        completedAt = null,
                        completionBatchId = null,
                        periodId = state.periodId,
                    ),
                )
                val stored = sessionDao.findByIndex(orderId, state.sessionIndex)
                    ?: throw ProgressCommandException("SESSION_INDEX_EXHAUSTED", "轮次编号已用尽")
                val created = inserted != -1L
                if (created) reconcile(orderId, now)
                EnsureSessionResult(stored, created = created)
            }
        }
    }

    /**
     * Completes sessions whose own due set is satisfied and advances the allocation.
     *
     * Rules:
     * - a session's due set comes from **its own period** on **its own attributed date**
     *   (activeFrom/activeUntil/retiredAt respected); an empty due set never completes;
     * - existing open rounds are reused before a new index is allocated;
     * - a newly allocated index is only completed when work for that exact `N:<index>`
     *   already exists (several rounds on one game day);
     * - periods are processed in `periodIndex` order, so the first period no longer acts
     *   as the container for every round;
     * - the order is complete only when every count period reaches its own target.
     */
    override suspend fun reconcile(orderId: String, now: Instant) {
        val order = orderDao.findById(orderId) ?: return
        if (!isCountOrder(order)) return
        val countPeriods = orderDao.findPeriods(orderId)
            .filter { it.serviceCount != null }
            .sortedWith(compareBy({ it.periodIndex }, { it.id }))
        if (countPeriods.isEmpty()) return

        // The **complete** roster of every count period: a task retired today may still have
        // been due in an earlier round, so historical resolution must not pre-filter it.
        // Eligibility is decided per round/attributed date by [CountHosting.isDueInRound].
        val rosterByPeriod = countPeriods.associate { period ->
            period.id to taskDao.findForPeriod(period.id)
        }
        val eventsByTask = taskDao.findForOrder(orderId).associate { task ->
            task.id to eventDao.findForTask(task.id).map { it.toEventModel() }
        }
        val occurrences = occurrenceDao.findForOrder(orderId)

        // 1. Re-derive completion of every existing round from its own period's due set.
        sessionDao.findForOrder(orderId).map { it.toModel() }.forEach { session ->
            val period = countPeriods.firstOrNull { it.id == session.periodId } ?: return@forEach
            val derived = sessionCompletedAt(
                session = session,
                period = period,
                roster = rosterByPeriod[period.id].orEmpty(),
                eventsByTask = eventsByTask,
                occurrences = occurrences,
                rules = refreshOf(period),
            )
            when {
                derived == null && session.completedAt != null ->
                    sessionDao.reopen(orderId, session.sessionIndex)

                derived != null && session.completedAt == null ->
                    sessionDao.markCompleted(orderId, session.sessionIndex, derived, null)

                derived != null && session.completedAt != derived ->
                    sessionDao.updateSessionCompletedAt(orderId, session.sessionIndex, derived)
            }
        }

        // 2. Complete/allocate rounds, period by period.
        countPeriods.forEach { period ->
            var guard = 0
            while (guard++ <= (period.serviceCount ?: 0) + 1) {
                val sessions = sessionDao.findForOrder(orderId).map { it.toModel() }
                val choice = CountHosting.selectActiveSession(
                    orderSessions = sessions,
                    periodId = period.id,
                    periodServiceCount = period.serviceCount,
                )
                when (choice) {
                    is CountHosting.ActiveSessionChoice.ReuseExisting -> {
                        val session = sessions.firstOrNull {
                            it.periodId == period.id && it.sessionIndex == choice.sessionIndex
                        } ?: break
                        if (session.completedAt != null) break
                        val derived = sessionCompletedAt(
                            session = session,
                            period = period,
                            roster = rosterByPeriod[period.id].orEmpty(),
                            eventsByTask = eventsByTask,
                            occurrences = occurrences,
                            rules = refreshOf(period),
                        ) ?: break
                        if (sessionDao.markCompleted(orderId, session.sessionIndex, derived, null) == 0) break
                    }

                    is CountHosting.ActiveSessionChoice.AllocateNew -> {
                        val key = CountHosting.occurrenceKey(choice.sessionIndex)
                        val derived = completionForAllocatedIndex(
                            period = period,
                            sessionIndex = choice.sessionIndex,
                            roster = rosterByPeriod[period.id].orEmpty(),
                            eventsByTask = eventsByTask,
                            occurrences = occurrences,
                        ) ?: break
                        sessionDao.insertIgnore(
                            HostingSessionEntity(
                                id = UUID.randomUUID().toString(),
                                orderId = orderId,
                                sessionIndex = choice.sessionIndex,
                                createdAt = now,
                                completedAt = null,
                                completionBatchId = null,
                                periodId = period.id,
                            ),
                        )
                        val row = sessionDao.findByIndex(orderId, choice.sessionIndex) ?: break
                        if (row.periodId != period.id) break
                        if (sessionDao.markCompleted(orderId, choice.sessionIndex, derived, null) == 0) break
                        check(key.isNotEmpty())
                    }

                    CountHosting.ActiveSessionChoice.PeriodFull,
                    CountHosting.ActiveSessionChoice.IndexSpaceExhausted,
                    -> break
                }
            }
        }

        // 3. Order completion: every count period must reach its own target.
        val finalSessions = sessionDao.findForOrder(orderId).map { it.toModel() }
        val everyPeriodMet = countPeriods.all { period ->
            CountHosting.periodCompletedCount(finalSessions, period.id) >=
                checkNotNull(period.serviceCount)
        }
        val latest = finalSessions.mapNotNull { it.completedAt }.maxOrNull()
        val derivedOrder = if (everyPeriodMet) latest else null
        if (order.completedAt != derivedOrder) {
            orderDao.updateCompletedAt(orderId, derivedOrder, now)
        }

        // 4. Target mirror stays derived: `orders.targetSessions` follows the latest count
        // period, so an incremental write and a from-scratch reconstruction agree even for
        // data written by an older build (F3). The mirror never terminates a period.
        val mirror = countPeriods
            .maxWithOrNull(compareBy({ it.periodIndex }, { it.id }))
            ?.serviceCount
        if (mirror != null && order.targetSessions != mirror) {
            orderDao.updateTargetSessions(orderId, mirror, now)
        }
    }

    override suspend fun reopenIfIncomplete(orderId: String, sessionIndex: Int, now: Instant) {
        val session = sessionDao.findByIndex(orderId, sessionIndex) ?: return
        if (session.completedAt == null) return
        val period = session.periodId?.let { orderDao.findPeriod(it) } ?: return
        val roster = taskDao.findForPeriod(period.id)
        val derived = sessionCompletedAt(
            session = session.toModel(),
            period = period,
            roster = roster,
            eventsByTask = roster.associate { task ->
                task.id to eventDao.findForTask(task.id).map { it.toEventModel() }
            },
            occurrences = occurrenceDao.findForOrder(orderId),
            rules = refreshOf(period),
        )
        if (derived != null) return
        sessionDao.reopen(orderId, sessionIndex)
        val order = orderDao.findById(orderId) ?: return
        if (order.completedAt != null) orderDao.updateCompletedAt(orderId, null, now)
    }

    // ---------------------------------------------------------------- pure-ish helpers

    /**
     * Completion instant of one existing session, or null when it does not count.
     *
     * [roster] is the session period's **complete** task list (retired rows included); the
     * due set is resolved at the round's own attributed date by [CountHosting.isDueInRound].
     * A cumulative task requires an attributed event in that session scope that reaches its
     * target; a periodic task requires its `N:<index>` occurrence to be completed. At least
     * one attributed observation is required, and a non-positive timestamp is never used as
     * evidence.
     */
    private fun sessionCompletedAt(
        session: HostingSession,
        period: HostingPeriodEntity,
        roster: List<TaskDefinitionEntity>,
        eventsByTask: Map<String, List<TaskProgressEvent>>,
        occurrences: List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>,
        rules: GameRefreshRules,
    ): Instant? {
        val key = CountHosting.occurrenceKey(session.sessionIndex)
        val sessionId = session.id
        val attributed = attributedDateFor(
            key = key,
            sessionId = sessionId,
            createdAt = session.createdAt,
            tasks = roster,
            eventsByTask = eventsByTask,
            occurrences = occurrences,
            rules = rules,
        ) ?: return null
        val due = dueTasks(roster, attributed, rules)
        if (due.isEmpty()) return null
        return completionOf(due, key, sessionId, eventsByTask, occurrences)
    }

    private fun completionForAllocatedIndex(
        period: HostingPeriodEntity,
        sessionIndex: Int,
        roster: List<TaskDefinitionEntity>,
        eventsByTask: Map<String, List<TaskProgressEvent>>,
        occurrences: List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>,
    ): Instant? {
        val key = CountHosting.occurrenceKey(sessionIndex)
        // The attributed date of an unwritten index is the date its work was recorded on.
        val dates = LinkedHashSet<LocalDate>()
        occurrences.filter { it.occurrenceKey == key }.forEach { dates += it.gameDate }
        // A session row does not exist yet, so events cannot reference its id. Their
        // presence under the key is impossible before the row exists; daily work that
        // belongs to this index is therefore the occurrence rows above.
        if (dates.isEmpty()) return null
        val attributed = dates.minOrNull() ?: return null
        val due = dueTasks(roster, attributed, refreshOf(period))
        if (due.isEmpty()) return null
        return completionOf(due, key, null, eventsByTask, occurrences)
    }

    private fun completionOf(
        due: List<TaskDefinitionEntity>,
        key: String,
        sessionId: String?,
        eventsByTask: Map<String, List<TaskProgressEvent>>,
        occurrences: List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>,
    ): Instant? {
        val instants = ArrayList<Instant>()
        due.forEach { task ->
            if (task.isCumulative()) {
                if (sessionId == null) return null
                val events = eventsByTask[task.id].orEmpty()
                val scope = ProgressScope.Session(sessionId)
                val total = ProgressResolution.resolveScopeTotal(
                    events,
                    task.id,
                    scope,
                    task.startValue,
                )
                if (!ProgressResolution.hasObservationInScope(events, task.id, scope)) return null
                if (!ProgressResolution.reachedTarget(total, task.targetValue)) return null
                ProgressResolution.resolveEffectiveEvent(events, task.id, scope)?.let { winner ->
                    if (winner.recordedAt.toEpochMilli() > 0) instants += winner.recordedAt
                }
                return@forEach
            }
            val occurrence = occurrences.firstOrNull {
                it.occurrenceKey == key && it.taskDefinitionId == task.id
            } ?: return null
            val completedAt = occurrence.completedAt ?: return null
            instants += completedAt
        }
        return instants.maxOrNull()
    }

    /**
     * Due set of one round: the period's complete roster resolved at [date] by the single
     * shared rule [CountHosting.isDueInRound] (the restore reconstructor uses the same one).
     */
    private fun dueTasks(
        tasks: List<TaskDefinitionEntity>,
        date: LocalDate,
        rules: GameRefreshRules,
    ): List<TaskDefinitionEntity> = tasks.filter { task ->
        CountHosting.isDueInRound(
            date = date,
            retirementGameDate = task.retiredAt?.let { GameCalendar.gameDate(it, rules) },
            activeFrom = task.activeFrom,
            activeUntil = task.activeUntil,
            recurrence = task.recurrence,
            cumulative = task.isCumulative(),
        )
    }

    private fun attributedDateFor(
        key: String,
        sessionId: String,
        createdAt: Instant,
        tasks: List<TaskDefinitionEntity>,
        eventsByTask: Map<String, List<TaskProgressEvent>>,
        occurrences: List<com.daigan.record.data.local.entity.TaskOccurrenceEntity>,
        rules: GameRefreshRules,
    ): LocalDate? {
        val dates = LinkedHashSet<LocalDate>()
        occurrences.filter { it.occurrenceKey == key }.forEach { dates += it.gameDate }
        tasks.forEach { task ->
            eventsByTask[task.id].orEmpty().forEach { event ->
                if (event.scope == ProgressScope.Session(sessionId)) dates += event.gameDate
            }
        }
        return dates.minOrNull() ?: GameCalendar.gameDate(createdAt, rules)
    }

    private suspend fun attributedDate(orderId: String, session: HostingSessionEntity): LocalDate {
        val period = orderDao.findPeriod(session.periodId)
            ?: return GameCalendar.gameDate(timeProvider.now(), GameRefreshRules.provisional())
        val key = CountHosting.occurrenceKey(session.sessionIndex)
        val occurrences = occurrenceDao.findForOrder(orderId).filter { it.occurrenceKey == key }
        val tasks = taskDao.findForPeriod(period.id)
        // One grouped read for the whole task set instead of one full-history query per task.
        val events = if (tasks.isEmpty()) {
            emptyMap<String, List<TaskProgressEvent>>()
        } else {
            eventDao.findForTasks(tasks.map { it.id }).map { it.toEventModel() }
                .groupBy { it.taskDefinitionId }
        }
        return attributedDateFor(
            key = key,
            sessionId = session.id,
            createdAt = session.createdAt,
            tasks = tasks,
            eventsByTask = events,
            occurrences = occurrences,
            rules = refreshOf(period),
        ) ?: GameCalendar.gameDate(session.createdAt, refreshOf(period))
    }

    private fun isCountOrder(order: OrderEntity): Boolean =
        order.type == OrderType.HOSTING && order.hostingMode == HostingMode.COUNT_BASED

    private suspend fun requireWritableCountOrder(order: OrderEntity) {
        if (order.cancelledAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "该订单已作废，不能再修改工作记录")
        }
        if (order.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单已归档，请先恢复订单")
        }
        if (!isCountOrder(order)) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "该订单不是打卡式托管")
        }
        val account = accountDao.findById(order.accountId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单归属账号已不存在")
        if (account.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "游戏账号已归档")
        }
        val owner = ownerDao.findById(account.ownerId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单归属老板已不存在")
        if (owner.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "老板已归档")
        }
    }

    private fun refreshOf(period: HostingPeriodEntity): GameRefreshRules =
        GameRefreshRules.fromMinutes(
            zoneId = ZoneId.of(period.gameZoneId),
            dailyRefreshMinutes = period.dailyRefreshMinutes,
            weeklyRefreshDayIso = period.weeklyRefreshDay,
            weeklyRefreshMinutes = period.weeklyRefreshMinutes,
            monthlyRefreshDays = parseMonthlyDates(period.monthlyRefreshDays) ?: emptySet(),
        )

    private fun TaskDefinitionEntity.isCumulative(): Boolean =
        progressType == ProgressType.NUMERIC && numericMode == NumericMode.CUMULATIVE
}

/**
 * Explicit dataset identity check for commands that capture the generation when a form
 * was opened. Must run inside the write boundary **before** the operationId lookup.
 */
object DatasetExpectation {
    fun requireCurrent(expected: Long?) {
        if (expected == null) return
        val current = DatasetGate.currentGeneration()
        if (expected != current) {
            throw ProgressCommandException(
                "DATASET_CHANGED",
                "资料已被恢复替换，请重新打开页面后操作",
            )
        }
    }
}

/** Entity ↔ model for progress events, shared with the cumulative repository. */
internal fun com.daigan.record.data.local.entity.TaskProgressEventEntity.toEventModel(): TaskProgressEvent =
    TaskProgressEvent(
        id = id,
        taskDefinitionId = taskDefinitionId,
        operationId = operationId,
        kind = kind,
        scope = when (scopeType) {
            com.daigan.record.data.model.ProgressScopeType.TASK -> ProgressScope.Task
            com.daigan.record.data.model.ProgressScopeType.PERIOD ->
                ProgressScope.Period(scopePeriodId.orEmpty())

            com.daigan.record.data.model.ProgressScopeType.SESSION ->
                ProgressScope.Session(scopeSessionId.orEmpty())
        },
        gameDate = gameDate,
        totalValue = totalValue,
        sequence = sequence,
        correctsEventId = correctsEventId,
        recordedAt = recordedAt,
        createdAt = createdAt,
    )
