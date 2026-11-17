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
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.entity.TaskProgressEventEntity
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressEventContext
import com.daigan.record.data.model.ProgressEventKind
import com.daigan.record.data.model.ProgressEventValidator
import com.daigan.record.data.model.ProgressResolution
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressScopeType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskProgressEvent
import com.daigan.record.data.model.allowedScopeType
import com.daigan.record.data.model.parseMonthlyDates
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 契约拒绝（UF-038）：校验失败在写入前抛出；operationId 载荷冲突抛 `OPERATION_CONFLICT`。 */
class ProgressCommandException(val code: String, message: String = code) : IllegalStateException(message)

/**
 * 提交当前累计总值（幂等；scope 身份不可变，重试不会被重定向到别的轮次）。
 *
 * [expectedGeneration] is the dataset identity the form captured when it opened. It is
 * checked inside the write boundary before the operationId lookup, so a form opened
 * before a completed restore is stale even when every business id still exists.
 */
data class SubmitTotalCommand(
    val operationId: String,
    val taskId: String,
    val scope: ProgressScope,
    val attributedGameDate: LocalDate,
    val total: BigDecimal,
    val expectedGeneration: Long? = null,
)

/** 更正（[total] 非空）或撤销（[total] 为空）；目标事件的日期/任务/范围/序号不可变。 */
data class CorrectTotalCommand(
    val operationId: String,
    val taskId: String,
    val targetEventId: String,
    val total: BigDecimal?,
    val expectedGeneration: Long? = null,
)

data class ProgressSnapshot(
    val taskId: String,
    val scope: ProgressScope,
    val total: BigDecimal,
    val reachedTarget: Boolean,
    val effectiveEventId: String?,
    val taskCurrentValue: BigDecimal,
    val taskCompletedAt: Instant?,
)

/**
 * 一个已退休任务在**某个历史轮次**里的应做记录（H1）。
 *
 * 它只描述"这一轮当时确实应做这个任务、现在有没有留下观测"，供历史更正入口使用。
 * 当前打卡列表仍然只列未退休任务；这里出现不代表任务重新可用。
 */
data class RetiredRoundTask(
    val taskId: String,
    val title: String,
    val progressType: ProgressType,
    val numericMode: NumericMode?,
    /** 该轮次是否已经留下归属观测（勾选完成 / 任一累计事件）。 */
    val hasObservation: Boolean,
    /** 是否已满足该轮次对这一任务的完成条件（勾选完成 / 累计达标）。 */
    val completed: Boolean,
    val currentValue: BigDecimal,
    val targetValue: BigDecimal,
    val attributedDate: LocalDate,
)

/** 一个计次轮次的历史（含已完成），供只读历史/更正入口使用，绝不创建轮次。 */
data class CountSessionHistoryEntry(
    val session: HostingSession,
    val periodId: String,
    val periodIndex: Int,
    val taskRows: List<CountRoundRow>,
    /**
     * 该轮次的归属游戏日；历史更正（尤其是已退休任务的累计补记）必须用它作归属日，
     * 不能用"今天"——否则一次更正会被悄悄挪到退休之后。
     */
    val attributedDate: LocalDate? = null,
    /** 该轮次当时应做、但之后被移除的任务；界面在历史区单独标识。 */
    val retiredDueTasks: List<RetiredRoundTask> = emptyList(),
) {
    val completed: Boolean get() = session.completed
}

sealed interface ProgressCommandResult {
    val event: TaskProgressEvent

    data class Applied(
        override val event: TaskProgressEvent,
        val snapshot: ProgressSnapshot,
    ) : ProgressCommandResult

    data class IdempotentReplay(
        override val event: TaskProgressEvent,
        val snapshot: ProgressSnapshot,
    ) : ProgressCommandResult
}

/** 一轮的打卡任务行：周期任务走 occurrence，累计任务走事件解析。 */
data class CountRoundRow(
    val taskId: String,
    val title: String,
    val progressType: ProgressType,
    val numericMode: NumericMode?,
    val targetValue: BigDecimal,
    val startValue: BigDecimal,
    val unit: String?,
    val occurrenceId: String?,
    val occurrenceKey: String,
    val currentValue: BigDecimal,
    val completed: Boolean,
    val attributedDate: LocalDate,
    val effectiveEventId: String?,
)

data class EnsureSessionResult(
    val session: HostingSessionEntity,
    val created: Boolean,
)

/**
 * 累计数值命令/查询边界（UF-038）。ViewModel 只依赖这个接口，Room 实现与测试替身
 * 遵循同一契约，UI 不需要知道事件表或事务细节。
 */
interface ProgressCommandService {

    /** 提交当前累计总值（幂等：同一 operationId 重试只写一次）。 */
    suspend fun submitTotal(command: SubmitTotalCommand): ProgressCommandResult

    /** 更正（total 非空）或撤销（total 为空）一条历史事件。 */
    suspend fun correctTotal(command: CorrectTotalCommand): ProgressCommandResult

    /**
     * 确保计次订单某轮次行存在。缺省为派生出的活动轮次；显式 [requestedSessionIndex]
     * 只能指向已存在的轮次或已分配的活动索引。绝不把写入重定向到别的轮次。
     */
    suspend fun ensureCountSession(
        orderId: String,
        requestedSessionIndex: Int? = null,
        expectedGeneration: Long? = null,
    ): EnsureSessionResult

    /** 任务的全部事件（含被更正/撤销），按写入序号升序。 */
    suspend fun progressHistory(taskId: String): List<TaskProgressEvent>

    /** 某 scope 的当前累计进度；任务或 scope 不合法时为 null/抛出契约拒绝。 */
    suspend fun cumulativeStatus(taskId: String, scope: ProgressScope): ProgressSnapshot?

    /** 计次订单全部轮次（含已完成）的只读历史；不创建会话、不写事件。 */
    suspend fun countSessionHistory(orderId: String): List<CountSessionHistoryEntry> = emptyList()

    /** 派生出的活动轮次状态（只读）；非计次订单返回 [CountHosting.ActiveCountState.AllPeriodsComplete]。 */
    suspend fun resolveActiveCountSession(orderId: String): CountHosting.ActiveCountState =
        CountHosting.ActiveCountState.AllPeriodsComplete
}

/**
 * UF-038 累计数值命令/查询。派生状态统一走 [recomputeOrderProgress]，与
 * [com.daigan.record.backup.DerivedStateReconstructor] 同纯函数同规则，
 * 保证「增量结果 = 全量重建结果」。计次轮次的写入与完成推进统一委托给
 * [CountSessionService]，不再在本文件保留第二套轮次语义。
 */
@Singleton
class CumulativeProgressRepository @Inject constructor(
    private val db: RecordDatabase,
    private val orderDao: OrderDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val eventDao: TaskProgressEventDao,
    private val hostingSessionDao: HostingSessionDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
    private val timeProvider: TimeProvider,
    private val countSessions: CountSessionService,
) : ProgressCommandService {
    // ---------------------------------------------------------------- 命令

    override suspend fun submitTotal(command: SubmitTotalCommand): ProgressCommandResult = applyEvent(
        operationId = command.operationId,
        taskId = command.taskId,
        scope = command.scope,
        gameDate = command.attributedGameDate,
        total = command.total,
        targetEventId = null,
        kind = ProgressEventKind.SUBMIT,
        expectedGeneration = command.expectedGeneration,
    )

    override suspend fun correctTotal(command: CorrectTotalCommand): ProgressCommandResult = applyEvent(
        operationId = command.operationId,
        taskId = command.taskId,
        scope = null,
        gameDate = null,
        total = command.total,
        targetEventId = command.targetEventId,
        kind = if (command.total == null) ProgressEventKind.RETRACT else ProgressEventKind.CORRECT,
        expectedGeneration = command.expectedGeneration,
    )

    override suspend fun ensureCountSession(
        orderId: String,
        requestedSessionIndex: Int?,
        expectedGeneration: Long?,
    ): EnsureSessionResult = gatedWrite {
        db.withTransaction {
            val result = countSessions.ensure(orderId, requestedSessionIndex, expectedGeneration)
            // A newly written row can make a round eligible for completion only after
            // its projections are recomputed; ensure already reconciles on creation.
            if (result.created) recomputeOrderProgress(orderId)
            result
        }
    }

    /** 派生活动轮次（只读；不创建轮次行、不写事件）。 */
    override suspend fun resolveActiveCountSession(orderId: String): CountHosting.ActiveCountState =
        countSessions.resolveActive(orderId)

    /** 计次目标调整（期次级 serviceCount，1–3650），随后重建派生态。 */
    suspend fun updateCountTarget(
        orderId: String,
        periodId: String,
        serviceCount: Int,
        expectedGeneration: Long? = null,
    ): Unit = gatedWrite {
        db.withTransaction {
            DatasetExpectation.requireCurrent(expectedGeneration)
            val order = orderDao.findById(orderId)
                ?: throw ProgressCommandException("ORDER_NOT_FOUND", "订单不存在")
            requireCountOrderWritable(order)
            val period = orderDao.findPeriods(orderId).firstOrNull { it.id == periodId }
                ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次不存在")
            if (period.serviceCount == null) {
                throw ProgressCommandException("SCOPE_NOT_ALLOWED", "该期次不是计次期次")
            }
            if (serviceCount < 1 || serviceCount > CountHosting.MAX_TARGET_SESSIONS) {
                throw ProgressCommandException("TARGET_OUT_OF_RANGE", "服务次数需为 1–3650")
            }
            orderDao.updatePeriodServiceCount(periodId, serviceCount)
            // `orders.targetSessions` 是**最新计次期次**（最高 periodIndex）目标的镜像（F3）：
            // 它只服务单期次工具与备份形状，其他期次的完成判定只看自己的 serviceCount。
            // 因此改最新期次就同步镜像，改更早期次则镜像保持最新期次的值。
            latestCountPeriod(orderId)?.serviceCount?.let { mirror ->
                orderDao.updateTargetSessions(orderId, mirror, timeProvider.now())
            }
            countSessions.reconcile(orderId, timeProvider.now())
            recomputeOrderProgress(orderId)
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 事件历史（含被更正/撤销的事件；升序）。 */
    override suspend fun progressHistory(taskId: String): List<TaskProgressEvent> {
        val task = taskDao.findById(taskId)
            ?: throw ProgressCommandException("TASK_NOT_FOUND", "任务不存在")
        val model = task.toModel()
        if (!isCumulative(model)) {
            throw ProgressCommandException("NOT_CUMULATIVE_TASK", "任务不是累计数值口径")
        }
        return eventDao.findForTask(taskId).map { it.toTaskProgressEvent() }
    }

    /** 某 scope 的当前累计进度（更正后的历史真相）。 */
    override suspend fun cumulativeStatus(taskId: String, scope: ProgressScope): ProgressSnapshot? {
        val taskEntity = taskDao.findById(taskId) ?: return null
        val task = taskEntity.toModel()
        if (!isCumulative(task)) {
            throw ProgressCommandException("NOT_CUMULATIVE_TASK", "任务不是累计数值口径")
        }
        validateScope(task, scope)
        val events = eventDao.findForTask(taskId).map { it.toTaskProgressEvent() }
        val winner = ProgressResolution.resolveEffectiveEvent(events, taskId, scope)
        val total = winner?.totalValue ?: task.startValue
        return ProgressSnapshot(
            taskId = taskId,
            scope = scope,
            total = total,
            reachedTarget = ProgressResolution.reachedTarget(total, task.targetValue),
            effectiveEventId = winner?.id,
            taskCurrentValue = task.currentValue,
            taskCompletedAt = task.completedAt,
        )
    }

    override suspend fun countSessionHistory(orderId: String): List<CountSessionHistoryEntry> {
        val order = orderDao.findById(orderId) ?: return emptyList()
        if (order.type != OrderType.HOSTING || order.hostingMode != HostingMode.COUNT_BASED) {
            return emptyList()
        }
        // One request-local snapshot plus one bulk round pass: rounds that share tasks
        // reuse the same task event histories instead of reloading them per round.
        val snapshot = historySnapshot(orderId)
        val roundsByIndex = countSessions.roundsFor(orderId).associateBy { it.sessionIndex }
        return snapshot.sessions.mapNotNull { entity ->
            val session = entity.toModel()
            val period = snapshot.periodsById[session.periodId] ?: return@mapNotNull null
            // One round per session: it is the only place that knows the round's
            // attributed game date and its historical due set (retired tasks included).
            val round = roundsByIndex[session.sessionIndex]
            CountSessionHistoryEntry(
                session = session,
                periodId = period.id,
                periodIndex = period.periodIndex,
                taskRows = roundRows(orderId, period, session, round, snapshot),
                attributedDate = round?.attributedDate,
                retiredDueTasks = round?.let { retiredDueRows(it, session, snapshot) }.orEmpty(),
            )
        }
    }

    /** 一轮的任务行（周期任务 + 累计任务混合）。 */
    suspend fun resolveCountRoundProgress(
        orderId: String,
        periodId: String,
        sessionIndex: Int,
    ): List<CountRoundRow> {
        val entity = hostingSessionDao.findByIndex(orderId, sessionIndex) ?: return emptyList()
        if (entity.periodId != periodId) return emptyList()
        val period = orderDao.findPeriod(periodId) ?: return emptyList()
        if (period.orderId != orderId) return emptyList()
        return roundRows(
            orderId = orderId,
            period = period,
            session = entity.toModel(),
            round = countSessions.roundFor(orderId, sessionIndex),
            snapshot = historySnapshot(orderId),
        )
    }

    /**
     * 一次历史/轮次读取的只读快照：按任务分组的事件链、按 (任务, occurrenceKey) 索引的
     * occurrence，以及期次与任务名单索引。同一请求内的多轮共用它，因此共享任务的轮次不会
     * 重复加载同一份事件历史。快照随请求结束丢弃（没有跨请求缓存，也就没有新的失效风险），
     * 且只读：不创建轮次、occurrence 或事件。
     */
    private class HistorySnapshot(
        val sessions: List<HostingSessionEntity>,
        val periodsById: Map<String, HostingPeriodEntity>,
        val tasksByPeriod: Map<String?, List<TaskDefinitionEntity>>,
        private val occurrenceByKey: Map<Pair<String, String>, TaskOccurrenceEntity>,
        private val eventsByTask: Map<String, List<TaskProgressEvent>>,
    ) {
        fun occurrence(taskId: String, key: String): TaskOccurrenceEntity? =
            occurrenceByKey[taskId to key]

        fun events(taskId: String): List<TaskProgressEvent> = eventsByTask[taskId].orEmpty()
    }

    /** 一次请求加载订单范围的会话、期次、任务、occurrence 与任务事件链，并建索引。 */
    private suspend fun historySnapshot(orderId: String): HistorySnapshot {
        val sessions = hostingSessionDao.findForOrder(orderId)
        val periodsById = orderDao.findPeriods(orderId).associateBy { it.id }
        val tasksByPeriod = taskDao.findForOrder(orderId).groupBy { it.periodId }
        val taskIds = tasksByPeriod.values.flatten().map { it.id }
        val occurrenceByKey = occurrenceDao.findForOrder(orderId)
            .associateBy { it.taskDefinitionId to it.occurrenceKey }
        val eventsByTask = if (taskIds.isEmpty()) {
            emptyMap()
        } else {
            // One grouped read for every task of the order, reused by all rounds.
            eventDao.findForTasks(taskIds).map { it.toTaskProgressEvent() }
                .groupBy { it.taskDefinitionId }
        }
        return HistorySnapshot(sessions, periodsById, tasksByPeriod, occurrenceByKey, eventsByTask)
    }

    /**
     * Historical rows of the tasks that were due in one round but have since been
     * removed. Read-only: no occurrence and no event is written to build this view.
     */
    private fun retiredDueRows(
        round: CountRound,
        session: HostingSession,
        snapshot: HistorySnapshot,
    ): List<RetiredRoundTask> {
        val retired = round.dueTasks.filter { it.retiredAt != null }
        if (retired.isEmpty()) return emptyList()
        val key = CountHosting.occurrenceKey(session.sessionIndex)
        val scope = ProgressScope.Session(session.id)
        return retired.map { entity ->
            val task = entity.toModel()
            val cumulative = isCumulative(task)
            // Request-local snapshot: no per-round, per-task history reload.
            val occurrence = snapshot.occurrence(task.id, key)
            val events = if (cumulative) snapshot.events(task.id) else emptyList()
            val winner = if (cumulative) {
                ProgressResolution.resolveEffectiveEvent(events, task.id, scope)
            } else {
                null
            }
            val current = if (cumulative) {
                winner?.totalValue ?: task.startValue
            } else {
                occurrence?.value ?: task.startValue
            }
            val completed = if (cumulative) {
                winner != null && ProgressResolution.reachedTarget(current, task.targetValue)
            } else {
                occurrence?.completedAt != null
            }
            RetiredRoundTask(
                taskId = task.id,
                title = task.title,
                progressType = task.progressType,
                numericMode = task.numericMode,
                hasObservation = if (cumulative) {
                    ProgressResolution.hasObservationInScope(events, task.id, scope)
                } else {
                    occurrence?.completedAt != null
                },
                completed = completed,
                currentValue = current,
                targetValue = task.targetValue,
                attributedDate = round.attributedDate,
            )
        }
    }

    private suspend fun roundRows(
        orderId: String,
        period: HostingPeriodEntity,
        session: HostingSession?,
        round: CountRound?,
        snapshot: HistorySnapshot,
    ): List<CountRoundRow> {
        if (period.orderId != orderId) return emptyList()
        val tasks = snapshot.tasksByPeriod[period.id].orEmpty()
            .filter { it.retiredAt == null }
            .map { it.toModel() }
        val sessionIndex = session?.sessionIndex ?: round?.sessionIndex ?: return emptyList()
        val key = CountHosting.occurrenceKey(sessionIndex)
        val attributedDate = (round ?: session?.let { countSessions.roundFor(orderId, sessionIndex) })
            ?.attributedDate
            ?: GameCalendar.gameDate(timeProvider.now(), refreshOf(period))
        return tasks.mapNotNull { task ->
            val occurrence = snapshot.occurrence(task.id, key)
            val events = snapshot.events(task.id)
            val scope = session?.let { ProgressScope.Session(it.id) }
            val winner = scope?.let { ProgressResolution.resolveEffectiveEvent(events, task.id, it) }
            val isCumulative = isCumulative(task)
            val resolvedTotal = winner?.totalValue ?: task.startValue
            val completed = if (isCumulative) {
                winner != null && ProgressResolution.reachedTarget(resolvedTotal, task.targetValue)
            } else {
                occurrence?.completedAt != null
            }
            if (scope == null && isCumulative) {
                // A round without a row has no cumulative scope yet; the row is created by
                // the first write, never to satisfy a read. The task is still listed.
                return@mapNotNull CountRoundRow(
                    taskId = task.id,
                    title = task.title,
                    progressType = task.progressType,
                    numericMode = task.numericMode,
                    targetValue = task.targetValue,
                    startValue = task.startValue,
                    unit = task.unit,
                    occurrenceId = occurrence?.id,
                    occurrenceKey = key,
                    currentValue = task.startValue,
                    completed = false,
                    attributedDate = attributedDate,
                    effectiveEventId = null,
                )
            }
            CountRoundRow(
                taskId = task.id,
                title = task.title,
                progressType = task.progressType,
                numericMode = task.numericMode,
                targetValue = task.targetValue,
                startValue = task.startValue,
                unit = task.unit,
                occurrenceId = occurrence?.id,
                occurrenceKey = key,
                currentValue = if (isCumulative) resolvedTotal else (occurrence?.value ?: resolvedTotal),
                completed = completed,
                attributedDate = attributedDate,
                effectiveEventId = winner?.id,
            )
        }
    }

    // ---------------------------------------------------------------- 事件写入

    private suspend fun applyEvent(
        operationId: String,
        taskId: String,
        scope: ProgressScope?,
        gameDate: LocalDate?,
        total: BigDecimal?,
        targetEventId: String?,
        kind: ProgressEventKind,
        expectedGeneration: Long?,
    ): ProgressCommandResult = gatedWrite {
        db.withTransaction {
            // ⓪ 数据集代际优先：表单打开后被一次完成的恢复替换，即使业务 id 仍在
            // 也必须拒绝；这一步早于 operationId 查询与任何业务读取。
            DatasetExpectation.requireCurrent(expectedGeneration)

            // ① 幂等优先：先查 operationId，再评估当前范围与完成资格。
            eventDao.findByOperationId(operationId)?.let { stored ->
                val storedModel = stored.toTaskProgressEvent()
                val isSubmit = targetEventId == null
                val same = if (isSubmit) {
                    storedModel.taskDefinitionId == taskId &&
                        storedModel.scope == scope &&
                        storedModel.gameDate == gameDate &&
                        storedModel.kind == ProgressEventKind.SUBMIT &&
                        storedModel.correctsEventId == null &&
                        canonicallyEqual(storedModel.totalValue, total)
                } else {
                    storedModel.taskDefinitionId == taskId &&
                        storedModel.correctsEventId == targetEventId &&
                        storedModel.kind == kind &&
                        canonicallyEqual(storedModel.totalValue, total)
                }
                if (!same) throw ProgressCommandException("OPERATION_CONFLICT", "operationId 已用于不同载荷")
                val replayTask = taskDao.findById(taskId)?.toModel()
                    ?: throw ProgressCommandException("TASK_NOT_FOUND", "任务不存在")
                return@withTransaction ProgressCommandResult.IdempotentReplay(
                    storedModel,
                    snapshotOf(replayTask, storedModel.scope),
                )
            }

            // ② 目标事件先行：更正/撤销的 scope 与日期继承自被更正事件。
            var resolvedScope = scope
            var resolvedDate = gameDate
            if (targetEventId != null) {
                val targetRow = eventDao.findForTask(taskId).firstOrNull { it.id == targetEventId }
                    ?: throw ProgressCommandException("TARGET_NOT_FOUND", "目标事件不存在")
                val target = targetRow.toTaskProgressEvent()
                resolvedScope = target.scope
                resolvedDate = target.gameDate
            }

            // ③ 资格校验（任何写入之前）。已完成订单仍允许更正/撤销：契约要求
            // 达标后保留历史与更正入口，更正到目标以下可重新打开任务；只有新的
            // 「提交」被拒绝。计次轮次同理：新提交不能写入已完成轮次，更正则允许，
            // 因为它会让该轮次重新打开。
            val isSubmit = kind == ProgressEventKind.SUBMIT
            val ctx = prepare(
                taskId = taskId,
                scope = resolvedScope ?: throw ProgressCommandException("SCOPE_NOT_ALLOWED", "缺少范围"),
                gameDate = resolvedDate ?: throw ProgressCommandException("OUT_OF_DATE_WINDOW", "缺少日期"),
                total = total,
                isSubmit = isSubmit,
            )

            // ④ 组装候选事件并跑纯校验器（单继承、序号、被撤销值不复活等）。
            val maxSequence = eventDao.maxSequence(ctx.task.id) ?: 0
            val now = timeProvider.now()
            val candidate = TaskProgressEvent(
                id = UUID.randomUUID().toString(),
                taskDefinitionId = ctx.task.id,
                operationId = operationId,
                kind = kind,
                scope = ctx.scope,
                gameDate = ctx.gameDate,
                totalValue = total,
                sequence = maxSequence + 1,
                correctsEventId = targetEventId,
                recordedAt = now,
                createdAt = now,
            )
            val events = eventDao.findForTask(ctx.task.id).map { it.toTaskProgressEvent() }
            val context = buildEventContext(ctx.order)
            val violation = ProgressEventValidator.validate(events + candidate, context)
                .firstOrNull { it.eventId == candidate.id }
            if (violation != null) {
                throw ProgressCommandException(violation.code, violation.reason)
            }

            // ⑤ 追加（序号在事务内分配；唯一索引兜底竞争）。
            eventDao.insert(candidate.toTaskProgressEventEntity(scopeTypeOf(ctx.scope)))
            recomputeOrderProgress(ctx.order.id)
            ProgressCommandResult.Applied(candidate, snapshotOf(ctx.task, ctx.scope))
        }
    }

    private data class Context(
        val order: OrderEntity,
        val task: OrderTask,
        val scope: ProgressScope,
        val gameDate: LocalDate,
    )

    /**
     * 写前资格校验：任务/口径/归档/所有权/生效日期/日期窗口/数值。
     *
     * 资格窗口按 scope 分开（R3-1 / F1）：
     * - `PERIOD`（按日期托管）：期次 `[startDate, endDate]` + 服务星期；
     * - `SESSION`（计次托管）：下界是期次的**实际服务起点**
     *   （[com.daigan.record.data.model.HostingPeriod.serviceStartDate]），`startDate/endDate`
     *   只是内部锚点，`endDate` **不是**服务截止日；再加上任务生效区间与服务日规则；
     * - `TASK`（短期单）：短期单没有服务期次，只按任务生效区间与不晚于今日判断。
     *
     * 未来游戏日、订单作废/归档、老板/账号归档在所有 scope 上一致拒绝。任务退休不是
     * 一刀切的只读：边界是**退休游戏日**（[CountHosting.acceptsAttribution]），因此历史
     * 更正/撤销（日期继承自被更正事件）仍可用，退休日之后的写入一律拒绝。
     */
    private suspend fun prepare(
        taskId: String,
        scope: ProgressScope,
        gameDate: LocalDate,
        total: BigDecimal?,
        isSubmit: Boolean,
    ): Context {
        val entity = taskDao.findById(taskId) ?: throw ProgressCommandException("TASK_NOT_FOUND", "任务不存在")
        val task = entity.toModel()
        val order = orderDao.findById(task.orderId) ?: throw ProgressCommandException("TASK_NOT_FOUND", "订单不存在")
        if (order.cancelledAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "该订单已作废，不能再修改工作记录")
        }
        if (order.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单已归档，请先恢复订单")
        }
        val account = accountDao.findById(order.accountId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单归属已不存在")
        if (account.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "游戏账号已归档")
        }
        val owner = ownerDao.findById(account.ownerId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "归属老板已不存在")
        if (owner.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "归属老板已归档")
        }
        if (order.completedAt != null && isSubmit) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单已完成")
        }
        if (!isCumulative(task)) {
            throw ProgressCommandException("NOT_CUMULATIVE_TASK", "任务不是累计数值口径")
        }
        // A retired task is not "read-only forever" (H1): its write boundary is the
        // retirement **game date**. Corrections/retractions inherit the target event's
        // date, so they are always inside the boundary when the observation predates the
        // retirement; anything dated later would be new progress and is rejected below,
        // once the scope's calendar (and therefore the retirement game date) is known.
        val expected = allowedScopeType(order.type, order.hostingMode)
        if (scopeTypeOf(scope) != expected) {
            throw ProgressCommandException("SCOPE_NOT_ALLOWED", "范围类型与订单不匹配")
        }

        var period: HostingPeriodEntity? = null
        when (scope) {
            is ProgressScope.Task -> {
                if (order.type != OrderType.SHORT_TASK || task.periodId != null) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "短期单任务不属于任何期次")
                }
            }

            is ProgressScope.Period -> {
                period = orderDao.findPeriod(scope.periodId)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次不存在")
                if (period.orderId != order.id) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次不属于该订单")
                }
                if (period.serviceCount != null) {
                    throw ProgressCommandException("SCOPE_NOT_ALLOWED", "计次期次必须使用轮次范围")
                }
                if (task.periodId != scope.periodId) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "任务不属于该期次")
                }
                if (gameDate.isBefore(period.startDate) || gameDate.isAfter(period.endDate)) {
                    throw ProgressCommandException("OUT_OF_DATE_WINDOW", "服务日期超出期次")
                }
            }

            is ProgressScope.Session -> {
                if (order.hostingMode != HostingMode.COUNT_BASED) {
                    throw ProgressCommandException("SCOPE_NOT_ALLOWED", "只有打卡式托管使用轮次范围")
                }
                val session = hostingSessionDao.findForOrder(order.id)
                    .firstOrNull { it.id == scope.sessionId }
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次不存在")
                period = orderDao.findPeriod(session.periodId)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次期次不存在")
                if (period.orderId != order.id || period.serviceCount == null) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次归属无效")
                }
                if (task.periodId != period.id) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "任务不属于该轮次的期次")
                }
                // 计次期次没有日历期限：`startDate`/`endDate` 只是满足 `(orderId, startDate)`
                // 唯一约束的内部锚点，同日续期会把它推到下一天。下界取**实际服务起点**
                // （协议生效的游戏日），上界开放；既不会因为存储约束推迟服务，也不会放过
                // 协议之前的历史。
                if (gameDate.isBefore(period.toModel().serviceStartDate)) {
                    throw ProgressCommandException("OUT_OF_DATE_WINDOW", "服务日期早于该期次开始服务")
                }
                if (isSubmit && session.completedAt != null) {
                    // Never silently redirect to another round. The caller must reopen
                    // this exact round or submit into an open one.
                    throw ProgressCommandException("SESSION_COMPLETED", "该轮次已完成，请先撤销或选择其他轮次")
                }
            }
        }

        if (task.activeFrom != null && gameDate.isBefore(task.activeFrom)) {
            throw ProgressCommandException("OUT_OF_DATE_WINDOW", "服务日期早于任务生效日")
        }
        if (task.activeUntil != null && gameDate.isAfter(task.activeUntil)) {
            throw ProgressCommandException("OUT_OF_DATE_WINDOW", "服务日期晚于任务停止生效日")
        }
        val rules = period?.let { refreshOf(it) } ?: refreshOf(order)
        // Retirement boundary (H1), evaluated with the scope's own refresh snapshot: the
        // retirement day itself stays eligible (same rule as `CountHosting.isDueInRound`),
        // every later date is a new observation and is refused.
        val retirementDate = task.retiredAt?.let { GameCalendar.gameDate(it, rules) }
        if (!CountHosting.acceptsAttribution(gameDate, retirementDate)) {
            throw ProgressCommandException(
                "ORDER_NOT_WRITABLE",
                "任务已于 $retirementDate 移除，只能更正该日及以前的记录",
            )
        }
        if (period != null) {
            val serviceWeekdays = com.daigan.record.data.local.parseWeekdays(period.serviceWeekdays)
            if (serviceWeekdays.isNotEmpty() && gameDate.dayOfWeek !in serviceWeekdays) {
                throw ProgressCommandException("OUT_OF_DATE_WINDOW", "服务日期不在服务日")
            }
        }
        val today = GameCalendar.gameDate(timeProvider.now(), rules)
        if (gameDate.isAfter(today)) {
            throw ProgressCommandException("FUTURE_GAME_DATE", "服务日期不能晚于今日")
        }
        if (total != null) {
            if (total.stripTrailingZeros().scale() > 3) {
                throw ProgressCommandException("VALUE_SCALE", "最多三位小数")
            }
            if (total < BigDecimal.ZERO || total > MAX_TOTAL) {
                throw ProgressCommandException("VALUE_OUT_OF_RANGE", "数值超出 0..1000000000000")
            }
        }
        return Context(order, task, scope, gameDate)
    }

    /** Scope ownership + mode matrix, used by read queries so they are not a loophole. */
    private suspend fun validateScope(task: OrderTask, scope: ProgressScope) {
        val order = orderDao.findById(task.orderId)
            ?: throw ProgressCommandException("TASK_NOT_FOUND", "订单不存在")
        val expected = allowedScopeType(order.type, order.hostingMode)
        if (scopeTypeOf(scope) != expected) {
            throw ProgressCommandException("SCOPE_NOT_ALLOWED", "范围类型与订单不匹配")
        }
        when (scope) {
            is ProgressScope.Task -> if (task.periodId != null) {
                throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "任务不属于任务级范围")
            }

            is ProgressScope.Period -> {
                val period = orderDao.findPeriod(scope.periodId)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次不存在")
                if (period.orderId != order.id || task.periodId != scope.periodId ||
                    period.serviceCount != null
                ) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "期次范围归属无效")
                }
            }

            is ProgressScope.Session -> {
                val session = hostingSessionDao.findForOrder(order.id)
                    .firstOrNull { it.id == scope.sessionId }
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次不存在")
                val period = orderDao.findPeriod(session.periodId)
                    ?: throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次期次不存在")
                if (period.serviceCount == null || task.periodId != period.id) {
                    throw ProgressCommandException("SCOPE_OWNERSHIP_INVALID", "轮次范围归属无效")
                }
            }
        }
    }

    private suspend fun buildEventContext(order: OrderEntity): ProgressEventContext {
        val tasks = taskDao.findForOrder(order.id).map { it.toModel() }
        val periods = orderDao.findPeriods(order.id).map { it.toModel() }
        val sessions = hostingSessionDao.findForOrder(order.id).map { it.toModel() }
        val allowed = allowedScopeType(order.type, order.hostingMode)
        return ProgressEventContext(
            scopeTypeByTask = tasks.associate { it.id to allowed },
            sessionOrder = sessions.associate { it.id to it.orderId },
            sessionPeriod = sessions.mapNotNull { s -> s.periodId?.let { s.id to it } }.toMap(),
            periodOrder = periods.associate { it.id to it.orderId },
            taskOrder = tasks.associate { it.id to it.orderId },
            taskPeriod = tasks.associate { it.id to it.periodId },
            cumulativeTaskIds = tasks.filter { isCumulative(it) }.map { it.id }.toSet(),
            countPeriodIds = periods.filter { it.serviceCount != null }.map { it.id }.toSet(),
            periodStart = periods.associate { it.id to it.startDate },
            // 计次期次的下界是实际服务起点，不是存储锚点（F1）。
            countPeriodServiceStart = periods.associate { it.id to it.serviceStartDate },
            periodEnd = periods.associate { it.id to it.endDate },
            taskActiveFrom = tasks.associate { it.id to it.activeFrom },
            taskActiveUntil = tasks.associate { it.id to it.activeUntil },
        )
    }

    private suspend fun snapshotOf(task: OrderTask, scope: ProgressScope): ProgressSnapshot {
        val events = eventDao.findForTask(task.id).map { it.toTaskProgressEvent() }
        val winner = ProgressResolution.resolveEffectiveEvent(events, task.id, scope)
        val total = winner?.totalValue ?: task.startValue
        return ProgressSnapshot(
            taskId = task.id,
            scope = scope,
            total = total,
            reachedTarget = ProgressResolution.reachedTarget(total, task.targetValue),
            effectiveEventId = winner?.id,
            taskCurrentValue = task.currentValue,
            taskCompletedAt = task.completedAt,
        )
    }

    /** 资料替换互斥门 + 契约 `DATASET_CHANGED`。 */
    private suspend fun <T> gatedWrite(block: suspend () -> T): T = try {
        DatasetGate.withWrite(block)
    } catch (e: ProgressCommandException) {
        throw e
    } catch (e: IllegalStateException) {
        val message = e.message.orEmpty()
        if (message.contains("资料")) throw ProgressCommandException("DATASET_CHANGED", message)
        throw e
    }

    // ---------------------------------------------------------------- 派生态重建

    /**
     * 计次/累计派生态重建（契约「增量必须等于重建」）：
     * 轮次完成 → 活动状态 → 缓存 → 订单完成（非计次单：TASK / PERIOD 投影）。
     */
    suspend fun recomputeOrderProgress(orderId: String) {
        val order = orderDao.findById(orderId)?.toModel() ?: return
        val orderEntity = orderDao.findById(orderId) ?: return
        val tasks = taskDao.findForOrder(orderId).map { it.toModel() }
        val eventsByTask: Map<String, List<TaskProgressEvent>> =
            tasks.associate { it.id to eventDao.findForTask(it.id).map { row -> row.toTaskProgressEvent() } }
        val isCountOrder = order.type == OrderType.HOSTING &&
            order.hostingMode == HostingMode.COUNT_BASED

        if (isCountOrder) {
            val now = timeProvider.now()
            countSessions.reconcile(orderId, now)
            val periodEntities = orderDao.findPeriods(orderId).filter { it.serviceCount != null }
            if (periodEntities.isEmpty()) return
            val periods = periodEntities.map { it.toModel() }
            val sessions = hostingSessionDao.findForOrder(orderId).map { it.toModel() }
            val active = CountHosting.resolveActiveSession(orderId, periods, sessions)
            tasks.filter { isCumulative(it) }.forEach { task ->
                val cache = CountHosting.taskScopeCache(
                    task = task,
                    state = active,
                    periods = periods,
                    sessions = sessions,
                    events = eventsByTask[task.id].orEmpty(),
                )
                val value = when (cache) {
                    is CountHosting.TaskScopeCache.ActiveSession -> cache.currentValue
                    is CountHosting.TaskScopeCache.AwaitingSessionRow -> cache.currentValue
                    is CountHosting.TaskScopeCache.TaskPeriodTerminal -> cache.currentValue
                    is CountHosting.TaskScopeCache.OrderTerminal -> cache.currentValue
                    is CountHosting.TaskScopeCache.IndexSpaceExhausted -> cache.currentValue
                }
                // 托管任务的完成时刻由轮次承载；存储规则要求它保持 null。
                taskDao.updateProgressKeepingBatch(task.id, value, null, now)
            }
            return
        }

        // ---- 非计次订单的累计派生值（TASK / PERIOD） ----------------------
        tasks.filter { isCumulative(it) }.forEach { task ->
            val scope = if (task.periodId == null) {
                ProgressScope.Task
            } else {
                ProgressScope.Period(task.periodId)
            }
            val events = eventsByTask[task.id].orEmpty()
            val total = ProgressResolution.resolveScopeTotal(events, task.id, scope, task.startValue)
            val winner = ProgressResolution.resolveEffectiveEvent(events, task.id, scope)
            val stamp = winner?.recordedAt?.takeIf { it.toEpochMilli() > 0 }
            // 完成投影：只有短期单（TASK scope）持久化任务级完成时刻，取有效观测赢家的
            // recordedAt；零观测时即使 startValue 已达标也保持 null。按日期托管（PERIOD）
            // 任务级完成时间保持 null，完成度由期次总值与目标派生。
            val derivedCompletedAt =
                if (task.periodId == null && stamp != null &&
                    ProgressResolution.reachedTarget(total, task.targetValue)
                ) {
                    stamp
                } else {
                    null
                }
            taskDao.updateProgressKeepingBatch(task.id, total, derivedCompletedAt, timeProvider.now())
        }

        // ---- 短期单整体完成标志 ----------------------------------------
        if (order.type == OrderType.SHORT_TASK) {
            val live = taskDao.findForOrder(orderId).map { it.toModel() }.filter { it.retiredAt == null }
            if (live.isNotEmpty()) {
                val derived = if (live.all { it.completedAt != null }) {
                    live.mapNotNull { it.completedAt }.maxOrNull()
                } else {
                    null
                }
                if (orderEntity.completedAt != derived) {
                    orderDao.updateCompletedAt(orderId, derived, timeProvider.now())
                }
            }
        }
    }

    private suspend fun requireCountOrderWritable(order: OrderEntity) {
        if (order.cancelledAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "该订单已作废，不能再修改工作记录")
        }
        if (order.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单已归档，请先恢复订单")
        }
        if (order.type != OrderType.HOSTING || order.hostingMode != HostingMode.COUNT_BASED) {
            throw ProgressCommandException("SCOPE_NOT_ALLOWED", "该订单不是打卡式托管")
        }
        val account = accountDao.findById(order.accountId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "订单归属已不存在")
        if (account.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "游戏账号已归档")
        }
        val owner = ownerDao.findById(account.ownerId)
            ?: throw ProgressCommandException("ORDER_NOT_WRITABLE", "归属老板已不存在")
        if (owner.archivedAt != null) {
            throw ProgressCommandException("ORDER_NOT_WRITABLE", "归属老板已归档")
        }
    }

    private fun isCumulative(task: OrderTask): Boolean =
        task.progressType == ProgressType.NUMERIC && task.numericMode == NumericMode.CUMULATIVE

    /** 最高 periodIndex 的计次期次；没有计次期次时为 null。 */
    private suspend fun latestCountPeriod(orderId: String): HostingPeriodEntity? =
        orderDao.findPeriods(orderId)
            .filter { it.serviceCount != null }
            .maxWithOrNull(compareBy({ it.periodIndex }, { it.id }))

    private fun refreshOf(order: OrderEntity): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = ZoneId.of(order.gameZoneId),
        dailyRefreshMinutes = order.dailyRefreshMinutes,
        weeklyRefreshDayIso = order.weeklyRefreshDay,
        weeklyRefreshMinutes = order.weeklyRefreshMinutes,
        monthlyRefreshDays = parseMonthlyDates(order.monthlyRefreshDays) ?: emptySet(),
    )

    private fun refreshOf(period: HostingPeriodEntity): GameRefreshRules =
        GameRefreshRules.fromMinutes(
            zoneId = ZoneId.of(period.gameZoneId),
            dailyRefreshMinutes = period.dailyRefreshMinutes,
            weeklyRefreshDayIso = period.weeklyRefreshDay,
            weeklyRefreshMinutes = period.weeklyRefreshMinutes,
            monthlyRefreshDays = parseMonthlyDates(period.monthlyRefreshDays) ?: emptySet(),
        )
}

private val MAX_TOTAL = BigDecimal("1000000000000")

private fun scopeTypeOf(scope: ProgressScope): ProgressScopeType = when (scope) {
    is ProgressScope.Task -> ProgressScopeType.TASK
    is ProgressScope.Period -> ProgressScopeType.PERIOD
    is ProgressScope.Session -> ProgressScopeType.SESSION
}

private fun canonicallyEqual(left: BigDecimal?, right: BigDecimal?): Boolean {
    if (left == null || right == null) return left == right
    return left.stripTrailingZeros() == right.stripTrailingZeros()
}

private fun TaskProgressEventEntity.toTaskProgressEvent(): TaskProgressEvent = toEventModel()

private fun TaskProgressEvent.toTaskProgressEventEntity(scopeType: ProgressScopeType): TaskProgressEventEntity =
    TaskProgressEventEntity(
        id = id,
        taskDefinitionId = taskDefinitionId,
        operationId = operationId,
        kind = kind,
        scopeType = scopeType,
        scopePeriodId = (scope as? ProgressScope.Period)?.periodId,
        scopeSessionId = (scope as? ProgressScope.Session)?.sessionId,
        gameDate = gameDate,
        totalValue = totalValue,
        sequence = sequence,
        correctsEventId = correctsEventId,
        recordedAt = recordedAt,
        createdAt = createdAt,
    )
