package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 累计事件的类型（schema 15，append-only）。 */
enum class ProgressEventKind { SUBMIT, CORRECT, RETRACT }

/** 累计事件的不可变范围类型。 */
enum class ProgressScopeType { TASK, PERIOD, SESSION }

/**
 * 累计进度的不可变 scope。
 *
 * 一次写入后永不改变；更正必须与被更正事件同 scope、同任务、同游戏日。
 * 改日期只能 RETRACT + 新 SUBMIT。
 */
sealed interface ProgressScope {
    data object Task : ProgressScope

    data class Period(val periodId: String) : ProgressScope

    data class Session(val sessionId: String) : ProgressScope
}

fun ProgressScope.type(): ProgressScopeType = when (this) {
    ProgressScope.Task -> ProgressScopeType.TASK
    is ProgressScope.Period -> ProgressScopeType.PERIOD
    is ProgressScope.Session -> ProgressScopeType.SESSION
}

fun ProgressScope.periodIdOrNull(): String? = (this as? ProgressScope.Period)?.periodId

fun ProgressScope.sessionIdOrNull(): String? = (this as? ProgressScope.Session)?.sessionId

/**
 * 每种订单模式**只允许一种**累计 scope（F2 / D-35）。
 *
 * 短单 → TASK；按日期托管（`hostingMode` NULL 读作按日期）→ PERIOD；
 * 按次数托管 → SESSION。违规一律拒绝：例如"计次任务用 PERIOD"会让一个期次级总值
 * 绕过每轮独立目标。
 */
fun allowedScopeType(orderType: OrderType, hostingMode: HostingMode?): ProgressScopeType = when {
    orderType == OrderType.SHORT_TASK -> ProgressScopeType.TASK
    hostingMode == HostingMode.COUNT_BASED -> ProgressScopeType.SESSION
    else -> ProgressScopeType.PERIOD
}

/**
 * 解析**某个任务的某个 scope** 的权威总值。
 *
 * 语义（R4/R5、F4、D-29）：事件是权威。
 * - **先按任务过滤**：任务身份是解析边界的一部分，别的任务的序号/更正/撤回绝不参与；
 * - 被**任何**事件（含 RETRACT）指向者即被取代；
 * - 有效 = 未被取代且非 RETRACT；
 * - 给定 `asOfGameDate` 时丢弃晚于该日的观测（"更正后的历史真值"）；
 * - 取剩余中最大 `gameDate`，同日取最大 `sequence`；
 * - 无有效事件回落 `startValue`。
 *
 * `taskId` 是**显式参数**（S2-R1）：scope 不足以标识一份进度——同一个 `SESSION` 下
 * 可以有多个任务各自的观测，`TASK` scope 也不是数据集全局。
 */
object ProgressResolution {

    fun resolveScopeTotal(
        events: List<TaskProgressEvent>,
        taskId: String,
        scope: ProgressScope,
        startValue: BigDecimal,
        asOfGameDate: LocalDate? = null,
    ): BigDecimal {
        val winner = resolveEffectiveEvent(events, taskId, scope, asOfGameDate)
        return winner?.totalValue ?: startValue
    }

    /**
     * 该任务在该 scope 内胜出的有效观测（与 [resolveScopeTotal] 共用同一过滤顺序）。
     *
     * 恢复期重建需要它的 `recordedAt` 作为"确定性派生的完成时刻"，因此单独暴露；
     * 不可能给出与总值不一致的赢家。
     */
    fun resolveEffectiveEvent(
        events: List<TaskProgressEvent>,
        taskId: String,
        scope: ProgressScope,
        asOfGameDate: LocalDate? = null,
    ): TaskProgressEvent? {
        val taskEvents = events.filter { it.taskDefinitionId == taskId }
        val superseded = taskEvents.mapNotNullTo(HashSet()) { it.correctsEventId }
        var winner: TaskProgressEvent? = null
        taskEvents.forEach { event ->
            if (event.scope != scope) return@forEach
            if (event.kind == ProgressEventKind.RETRACT) return@forEach
            if (event.id in superseded) return@forEach
            if (asOfGameDate != null && event.gameDate > asOfGameDate) return@forEach
            val current = winner
            winner = when {
                current == null -> event
                event.gameDate > current.gameDate -> event
                event.gameDate == current.gameDate && event.sequence > current.sequence -> event
                else -> current
            }
        }
        return winner
    }

    /** 该任务在该 scope 内是否有**任何**归属观测（含被更正者）。 */
    fun hasObservationInScope(
        events: List<TaskProgressEvent>,
        taskId: String,
        scope: ProgressScope,
    ): Boolean = events.any { it.taskDefinitionId == taskId && it.scope == scope }

    /** 是否已达标（`current >= target`）。 */
    fun reachedTarget(current: BigDecimal, target: BigDecimal): Boolean = current >= target
}

/**
 * 一条已持久化的累计观测（纯领域形态；数据库实体见 `TaskProgressEventEntity`）。
 *
 * 事件是**权威**：`task_definitions.currentValue` 只是派生状态的缓存，绝不反向解释事件。
 */
data class TaskProgressEvent(
    val id: String,
    val taskDefinitionId: String,
    val operationId: String,
    val kind: ProgressEventKind,
    val scope: ProgressScope,
    val gameDate: LocalDate,
    val totalValue: BigDecimal?,
    val sequence: Int,
    val correctsEventId: String?,
    val recordedAt: Instant,
    val createdAt: Instant,
)

/** 事件链校验失败原因（机器码 + 受影响的持久 id）。 */
data class ProgressEventViolation(
    val code: String,
    val eventId: String,
    val reason: String,
    /** 受影响对象（event/task/order/period/session 的持久 id），用于对象级诊断。 */
    val objects: Map<String, String> = emptyMap(),
)

/** 校验所需的归属上下文；由调用方（恢复校验/写入命令）提供。 */
data class ProgressEventContext(
    /** taskDefinitionId → 该任务所属订单的累计 scope 类型。 */
    val scopeTypeByTask: Map<String, ProgressScopeType>,
    /** sessionId → 归属订单。 */
    val sessionOrder: Map<String, String>,
    /** sessionId → 归属期次。 */
    val sessionPeriod: Map<String, String>,
    /** periodId → 归属订单。 */
    val periodOrder: Map<String, String>,
    /** taskDefinitionId → 归属订单。 */
    val taskOrder: Map<String, String>,
    /** taskDefinitionId → 归属期次（短期单为 null）。 */
    val taskPeriod: Map<String, String?>,
    /** 允许累计事件的任务（progressType = NUMERIC 且 numericMode = CUMULATIVE）。 */
    val cumulativeTaskIds: Set<String>,
    /** 计次期次（serviceCount 非空）的 id 集合；其余期次按日期。 */
    val countPeriodIds: Set<String>,
    /** periodId → 名义开始日（归属日期的下界）。 */
    val periodStart: Map<String, LocalDate>,
    /**
     * periodId → 计次期次**实际**服务下界（`HostingPeriod.serviceStartDate`，F1）。
     *
     * 计次期次的 `startDate` 只是满足 `(orderId, startDate)` 唯一约束的内部锚点：同日续期
     * 会把它推到下一天。真正的下界是协议生效的游戏日（期次创建时刻按该期次刷新规则换算），
     * 这个值只会早于或等于锚点。缺失时退回 [periodStart]，绝不放大到任意历史。
     */
    val countPeriodServiceStart: Map<String, LocalDate> = emptyMap(),
    /** periodId → 名义结束日（只对按日期期次作为上界）。 */
    val periodEnd: Map<String, LocalDate>,
    /** taskDefinitionId → 任务生效起始日。 */
    val taskActiveFrom: Map<String, LocalDate?>,
    /** taskDefinitionId → 任务生效截止日。 */
    val taskActiveUntil: Map<String, LocalDate?>,
)

/**
 * 事件归属日期的服务区间（S2-R2 / F1）。
 *
 * 任务自身的 `activeFrom/activeUntil` 永远生效。期次下界分两种：
 * - **按日期期次**：`startDate` .. `endDate`（含）；
 * - **计次期次**：下界是实际服务起点 [ProgressEventContext.countPeriodServiceStart]，
 *   不是存储锚点 `startDate`（同日续期会把锚点推到下一天，用它会把今天的合法进度判成
 *   非法）；上界开放——名义 `startDate/endDate` 在建单时相同，轮次可以晚于它们完成。
 */
private fun withinServiceRange(
    taskId: String,
    periodId: String?,
    gameDate: LocalDate,
    context: ProgressEventContext,
): Boolean {
    context.taskActiveFrom[taskId]?.let { if (gameDate < it) return false }
    context.taskActiveUntil[taskId]?.let { if (gameDate > it) return false }
    if (periodId == null) return true
    val countPeriod = periodId in context.countPeriodIds
    val start = if (countPeriod) {
        context.countPeriodServiceStart[periodId] ?: context.periodStart[periodId]
    } else {
        context.periodStart[periodId]
    } ?: return true
    if (gameDate < start) return false
    if (countPeriod) return true
    val end = context.periodEnd[periodId] ?: return true
    return !gameDate.isAfter(end)
}

/**
 * 严格校验追加式事件链（恢复时与写入前共用）。
 *
 * 覆盖：payload 形状、scope 矩阵、scope 归属、更正目标同任务/同 scope/同日、
 * 被取代事件不得再被更正（单后继）、更正环、序号关系、operationId 与 sequence 唯一。
 *
 * 只做判定，**不修复**：任何一条违规都表示这份数据不能被信任。
 */
object ProgressEventValidator {

    fun validate(
        events: List<TaskProgressEvent>,
        context: ProgressEventContext,
    ): List<ProgressEventViolation> {
        val violations = ArrayList<ProgressEventViolation>()
        val byId = HashMap<String, TaskProgressEvent>()
        val operationIds = HashSet<String>()
        val sequences = HashSet<String>()
        val correctedTargets = HashSet<String>()

        events.forEach { event ->
            if (byId.put(event.id, event) != null) {
                violations += ProgressEventViolation("DUPLICATE_EVENT_ID", event.id, "事件 id 重复")
            }
            if (!operationIds.add(event.operationId)) {
                violations += ProgressEventViolation(
                    "DUPLICATE_OPERATION_ID",
                    event.id,
                    "operationId ${event.operationId} 重复",
                )
            }
            if (!sequences.add("${event.taskDefinitionId}#${event.sequence}")) {
                violations += ProgressEventViolation(
                    "DUPLICATE_SEQUENCE",
                    event.id,
                    "任务内序号 ${event.sequence} 重复",
                )
            }
            if (event.sequence < 1) {
                violations += ProgressEventViolation("IMPOSSIBLE_SEQUENCE", event.id, "序号必须 ≥ 1")
            }
            event.correctsEventId?.let { target ->
                if (!correctedTargets.add(target)) {
                    violations += ProgressEventViolation(
                        "SUPERSEDED_EVENT",
                        event.id,
                        "目标事件 $target 已被更正过（单后继）",
                    )
                }
            }
        }

        events.forEach { event ->
            val orderId = context.taskOrder[event.taskDefinitionId]
            if (orderId == null) {
                violations += ProgressEventViolation(
                    "SCOPE_OWNERSHIP_INVALID",
                    event.id,
                    "事件引用了不存在的任务",
                )
                return@forEach
            }
            when (event.kind) {
                ProgressEventKind.SUBMIT -> if (event.correctsEventId != null || event.totalValue == null) {
                    violations += ProgressEventViolation(
                        "KIND_PAYLOAD_INVALID",
                        event.id,
                        "SUBMIT 必须携带总值且不指向被更正事件",
                    )
                }

                ProgressEventKind.CORRECT -> if (event.correctsEventId == null || event.totalValue == null) {
                    violations += ProgressEventViolation(
                        "KIND_PAYLOAD_INVALID",
                        event.id,
                        "CORRECT 必须指向被更正事件并携带总值",
                    )
                }

                ProgressEventKind.RETRACT -> if (event.correctsEventId == null || event.totalValue != null) {
                    violations += ProgressEventViolation(
                        "KIND_PAYLOAD_INVALID",
                        event.id,
                        "RETRACT 必须指向被更正事件且不携带总值",
                    )
                }
            }
            val taskId = event.taskDefinitionId
            val objects = buildMap {
                put("eventId", event.id)
                put("taskDefinitionId", taskId)
                put("orderId", orderId)
                put("periodId", context.taskPeriod[taskId].orEmpty())
                put("sessionId", event.scope.sessionIdOrNull().orEmpty())
            }
            // 只有"数值 + 累计"任务才有事件（S2-R2）。
            if (taskId !in context.cumulativeTaskIds) {
                violations += ProgressEventViolation(
                    "TASK_NOT_CUMULATIVE",
                    event.id,
                    "任务 $taskId 不是 NUMERIC + CUMULATIVE，不允许累计事件",
                    objects,
                )
            }
            val scopeType = event.scope.type()
            val hasPeriod = event.scope.periodIdOrNull() != null
            val hasSession = event.scope.sessionIdOrNull() != null
            val periodFieldOk = if (scopeType == ProgressScopeType.PERIOD) hasPeriod else !hasPeriod
            val sessionFieldOk = if (scopeType == ProgressScopeType.SESSION) hasSession else !hasSession
            if (!periodFieldOk || !sessionFieldOk) {
                violations += ProgressEventViolation(
                    "SCOPE_FIELDS_INCONSISTENT",
                    event.id,
                    "scope 类型与 periodId/sessionId 不一致",
                    objects,
                )
            }
            val allowed = context.scopeTypeByTask[taskId]
            if (allowed != null && scopeType != allowed) {
                violations += ProgressEventViolation(
                    "SCOPE_NOT_ALLOWED",
                    event.id,
                    "订单 $orderId 只允许 $allowed scope，实际 $scopeType",
                    objects,
                )
            }
            when (val scope = event.scope) {
                ProgressScope.Task -> {
                    // TASK scope 只属于"没有期次"的短期单任务。
                    if (context.taskPeriod[taskId] != null) {
                        violations += ProgressEventViolation(
                            "TASK_SCOPE_REQUIRES_PERIODLESS_TASK",
                            event.id,
                            "TASK scope 只属于短期单任务；任务 $taskId 的期次为 ${context.taskPeriod[taskId]}",
                            objects,
                        )
                    }
                    if (!withinServiceRange(taskId, null, event.gameDate, context)) {
                        violations += ProgressEventViolation(
                            "EVENT_DATE_OUT_OF_SERVICE_RANGE",
                            event.id,
                            "事件 ${event.id} 的游戏日 ${event.gameDate} 不在任务 $taskId 的生效区间内",
                            objects,
                        )
                    }
                }

                is ProgressScope.Period -> {
                    val periodOrder = context.periodOrder[scope.periodId]
                    if (periodOrder != orderId) {
                        violations += ProgressEventViolation(
                            "SCOPE_OWNERSHIP_INVALID",
                            event.id,
                            "scope 期次 ${scope.periodId} 不属于订单 $orderId",
                            objects,
                        )
                    } else {
                        if (context.taskPeriod[taskId] != scope.periodId) {
                            violations += ProgressEventViolation(
                                "SCOPE_PERIOD_NOT_TASK_PERIOD",
                                event.id,
                                "事件 ${event.id} 的期次 ${scope.periodId} 不是任务 $taskId 自己的期次",
                                objects,
                            )
                        }
                        if (scope.periodId in context.countPeriodIds) {
                            violations += ProgressEventViolation(
                                "SCOPE_PERIOD_NOT_DATE_BASED",
                                event.id,
                                "期次 ${scope.periodId} 是计次期次，不接受 PERIOD scope",
                                objects,
                            )
                        }
                        if (!withinServiceRange(taskId, scope.periodId, event.gameDate, context)) {
                            violations += ProgressEventViolation(
                                "EVENT_DATE_OUT_OF_SERVICE_RANGE",
                                event.id,
                                "事件 ${event.id} 的游戏日 ${event.gameDate} 不在期次 ${scope.periodId} 与任务的生效区间内",
                                objects,
                            )
                        }
                    }
                }

                is ProgressScope.Session -> {
                    val sessionOrder = context.sessionOrder[scope.sessionId]
                    if (sessionOrder != orderId) {
                        violations += ProgressEventViolation(
                            "SCOPE_OWNERSHIP_INVALID",
                            event.id,
                            "scope 轮次 ${scope.sessionId} 不属于订单 $orderId",
                            objects,
                        )
                    } else {
                        val taskPeriod = context.taskPeriod[taskId]
                        val sessionPeriod = context.sessionPeriod[scope.sessionId]
                        if (taskPeriod == null || sessionPeriod != taskPeriod) {
                            violations += ProgressEventViolation(
                                "SCOPE_SESSION_PERIOD_MISMATCH",
                                event.id,
                                "轮次 ${scope.sessionId} 属于期次 ${sessionPeriod.orEmpty()}，任务 $taskId 属于期次 ${taskPeriod.orEmpty()}",
                                objects,
                            )
                        }
                        if (sessionPeriod == null || sessionPeriod !in context.countPeriodIds) {
                            violations += ProgressEventViolation(
                                "SCOPE_SESSION_NOT_COUNT_BASED",
                                event.id,
                                "轮次 ${scope.sessionId} 的期次不是计次期次，不接受 SESSION scope",
                                objects,
                            )
                        } else if (!withinServiceRange(taskId, sessionPeriod, event.gameDate, context)) {
                            violations += ProgressEventViolation(
                                "EVENT_DATE_OUT_OF_SERVICE_RANGE",
                                event.id,
                                "事件 ${event.id} 的游戏日 ${event.gameDate} 早于期次开始日或超出任务 $taskId 的生效区间",
                                objects,
                            )
                        }
                    }
                }
            }
            val target = event.correctsEventId?.let(byId::get) ?: return@forEach
            if (target.taskDefinitionId != event.taskDefinitionId) {
                violations += ProgressEventViolation(
                    "CORRECTION_TARGET_OTHER_TASK",
                    event.id,
                    "被更正事件属于别的任务",
                )
            }
            if (target.scope != event.scope) {
                violations += ProgressEventViolation(
                    "CORRECTION_TARGET_OTHER_SCOPE",
                    event.id,
                    "更正不得跨 scope",
                )
            }
            if (target.gameDate != event.gameDate) {
                violations += ProgressEventViolation(
                    "CORRECTION_DATE_CHANGED",
                    event.id,
                    "更正必须与被更正事件同一游戏日",
                )
            }
            if (event.sequence <= target.sequence) {
                violations += ProgressEventViolation(
                    "IMPOSSIBLE_SEQUENCE",
                    event.id,
                    "更正事件序号必须大于被更正事件",
                )
            }
        }

        events.forEach { event ->
            val seen = HashSet<String>()
            var cursor: TaskProgressEvent? = event
            while (cursor?.correctsEventId != null) {
                if (!seen.add(cursor.id)) {
                    violations += ProgressEventViolation("CORRECTION_CYCLE", event.id, "更正链存在环")
                    break
                }
                cursor = byId[cursor.correctsEventId]
            }
        }
        return violations
    }
}
