package com.daigan.record.feature.orders

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.TaskProgressEvent
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.data.model.RenewalResult
import com.daigan.record.data.model.UndoResult
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.CorrectTotalCommand
import com.daigan.record.data.repository.CountSessionHistoryEntry
import com.daigan.record.data.repository.DatasetGate
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.ProgressCommandService
import com.daigan.record.data.repository.ReceiptRepository
import com.daigan.record.data.repository.StaleOccurrenceException
import com.daigan.record.data.repository.SubmitTotalCommand
import com.daigan.record.data.repository.DisplayedHostingSnapshot
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrderDetailUiState(
    val loading: Boolean = true,
    val detail: OrderDetail? = null,
    val error: String? = null,
    val lastBatchId: String? = null,
    val undoResult: UndoResult? = null,
    val paymentInFlight: Boolean = false,
    val maintenanceInFlight: Boolean = false,
    /**
     * Tasks removed from the current list, with the rounds they were still due in. This is
     * the reachable historical surface for a retired task (H1): it never re-adds the task
     * to the current action list, it only names it and its historical rounds.
     */
    val retiredTasks: List<RetiredTaskHistory> = emptyList(),
    /**
     * Dataset identity captured when this page opened. Every new write sends it back, so
     * a form opened before a completed restore is rejected even when the business ids are
     * unchanged afterwards. [refresh] re-captures it after the page reads again.
     */
    val datasetGeneration: Long = DatasetGate.currentGeneration(),
)

/** One removed task plus every existing round it was still due in. */
data class RetiredTaskHistory(
    val taskId: String,
    val title: String,
    val cumulative: Boolean,
    val retiredAt: Instant?,
    val rounds: List<RetiredTaskRound>,
)

/** One historical round of a removed task, identified by round index and game date. */
data class RetiredTaskRound(
    val sessionId: String,
    val sessionIndex: Int,
    val attributedDate: java.time.LocalDate,
    val completed: Boolean,
    val hasObservation: Boolean,
)

/**
 * 累计数值面板（UF-038）。输入的是**当前累计总值**，不是本次增量；同一次提交的重试
 * 复用 [pendingOperationId]，成功后作废，因此双击或网络式重试不会写出第二个事件。
 */
data class CumulativePanel(
    val taskId: String,
    val taskTitle: String,
    val scope: ProgressScope,
    val targetValue: BigDecimal,
    val unit: String?,
    val currentTotal: BigDecimal,
    val events: List<TaskProgressEvent> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
    val pendingOperationId: String? = null,
    /** 计次单当前轮次编号；非计次单为 null。 */
    val sessionIndex: Int? = null,
    /** 明确显示的是哪个范围：当前范围还是历史轮次（只读新增、可更正）。 */
    val scopeLabel: String = "",
    val historical: Boolean = false,
    /** 打开面板时的数据集身份；提交/更正都带上它。 */
    val datasetGeneration: Long = DatasetGate.currentGeneration(),
    /** 计次订单全部轮次，供显式切换查看；切换不改变派生活动缓存。 */
    val sessions: List<CumulativeSessionOption> = emptyList(),
    /** The payload the pending submit operation id belongs to (retry vs new attempt). */
    val pendingSubmitText: String? = null,
    val correctionAttempt: CorrectionAttempt? = null,
    /**
     * The game date a **removed** task's history may still be corrected on (H1). It is
     * the viewed round's own attributed date, never "today", so a correction cannot be
     * moved past the retirement date. Null means this panel takes no new observation.
     */
    val historicalSubmitDate: java.time.LocalDate? = null,
)

data class CumulativeSessionOption(
    val sessionId: String,
    val sessionIndex: Int,
    val periodIndex: Int,
    val completed: Boolean,
    /** The round's own attributed game date; used as the historical correction date. */
    val attributedDate: java.time.LocalDate? = null,
)

/**
 * One attempted correction. The operation id is reused only while the target event and
 * the payload text stay identical; editing the payload allocates a new id.
 */
data class CorrectionAttempt(
    val eventId: String,
    val text: String,
    val operationId: String,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    private val attachmentRepository: AttachmentRepository,
    private val receiptRepository: ReceiptRepository,
    private val timeProvider: TimeProvider,
    private val progressRepository: ProgressCommandService,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[Routes.ORDER_ID_ARG]) {
        "orderId 参数缺失"
    }

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState: StateFlow<OrderDetailUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _renewalApplied = MutableSharedFlow<RenewalResult>(extraBufferCapacity = 1)
    val renewalApplied: SharedFlow<RenewalResult> = _renewalApplied

    /** Emits after a payment or receipt correction committed, so dialogs can close. */
    private val _financialApplied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val financialApplied: SharedFlow<Unit> = _financialApplied

    private val _maintenanceApplied = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val maintenanceApplied: SharedFlow<Boolean> = _maintenanceApplied

    /** 累计数值面板（UF-038）；null = 未打开。 */
    private val _cumulative = MutableStateFlow<CumulativePanel?>(null)
    val cumulative: StateFlow<CumulativePanel?> = _cumulative.asStateFlow()

    fun updateOrderDates(periodId: String?, start: java.time.LocalDate?, end: java.time.LocalDate?) =
        maintainOrder(false) { orderRepository.updateOrderDates(orderId, periodId, start, end) }

    fun deleteOrder() = maintainOrder(true) { orderRepository.deleteUnusedOrder(orderId) }
    fun cancelOrder() = maintainOrder(false) { orderRepository.cancelOrder(orderId) }
    fun archiveOrder() = maintainOrder(false, "已选择不续期并归档；可在账号的已归档订单中查看、收款或恢复") {
        orderRepository.archiveExpiredHostingOrder(orderId)
    }
    fun restoreOrder() = maintainOrder(false, "订单已恢复，原服务日期与收款保留；如需继续服务请续期") {
        orderRepository.restoreArchivedOrder(orderId)
    }

    /** UF-009: permanent deletion of a cancelled order plus all dependent records. */
    fun deleteCancelledOrder() = maintainOrder(true, "已删除作废订单及其关联记录") {
        orderRepository.deleteCancelledOrder(orderId)
        // Row deletes committed; orphaned attachment files self-heal here and at start.
        runCatching { attachmentRepository.cleanupOrphans() }
    }

    fun updateTitle(title: String) =
        maintainOrder(false) { orderRepository.updateOrderTitle(orderId, title) }

    /**
     * Order-level colour (UF-028). One tap in the detail header writes this order only;
     * the Today stripes and the Today colour filter follow the same persisted value.
     */
    fun setOrderColor(colorArgb: Int?) = maintainOrder(
        false,
        if (colorArgb == null) "已清除订单颜色" else "已更新订单颜色",
    ) { orderRepository.setOrderColor(orderId, colorArgb) }

    fun updateRefreshRules(rules: GameRefreshRules) =
        maintainOrder(false) { orderRepository.updateOrderRefresh(orderId, rules) }

    fun updateServiceSchedule(periodId: String, days: Int?, weekdays: Set<java.time.DayOfWeek>) =
        maintainOrder(false) { orderRepository.updateServiceSchedule(orderId, periodId, days, weekdays) }

    fun updateTasks(tasks: List<TaskDraft>) =
        maintainOrder(false) { orderRepository.updateTaskDetails(orderId, tasks) }

    fun updateIdentity(accountId: String, type: com.daigan.record.data.model.OrderType, start: java.time.LocalDate, end: java.time.LocalDate?) =
        maintainOrder(false) { orderRepository.updateUnusedOrderIdentity(orderId, accountId, type, start, end) }

    fun updateNotes(notes: String?) =
        maintainOrder(false) { orderRepository.updateOrderNotes(orderId, notes) }

    fun updatePaymentMode(mode: com.daigan.record.data.model.PaymentMode) =
        maintainOrder(false) { orderRepository.updatePaymentMode(orderId, mode) }

    /** Price correction; [operationId] retries are idempotent inside the repository. */
    fun updatePrice(periodId: String?, priceYuan: String, operationId: String, reason: String) =
        maintainOrder(false) {
            val cents = com.daigan.record.data.model.Money.parseYuanToCents(priceYuan)
            if (periodId == null) {
                orderRepository.updateShortOrderPrice(orderId, cents, operationId, reason)
            } else {
                orderRepository.updatePeriodPrice(orderId, periodId, cents, operationId, reason)
            }
        }

    private fun maintainOrder(
        deleted: Boolean,
        successMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        if (_uiState.value.maintenanceInFlight) return
        _uiState.value = _uiState.value.copy(maintenanceInFlight = true)
        viewModelScope.launch {
            try {
                block()
                _maintenanceApplied.emit(deleted)
                emit(successMessage ?: (if (deleted) "已删除误建订单" else "订单已更新"))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(error.message ?: "操作失败，请重试")
            } finally {
                _uiState.value = _uiState.value.copy(maintenanceInFlight = false)
            }
        }
    }

    private val refreshTick = MutableStateFlow(0L)
    // ViewModel actions run on Main; acquire before launch so queued taps cannot overlap.
    private var batchActionInFlight = false
    private var financialActionInFlight = false

    init {
        viewModelScope.launch {
            refreshTick
                .flatMapLatest { orderRepository.observeOrderDetail(orderId) }
                .map { detail ->
                    if (detail == null) {
                        OrderDetailUiState(loading = false, error = "找不到该订单")
                    } else {
                        _uiState.value.copy(
                            loading = false,
                            detail = detail,
                            error = null,
                            retiredTasks = retiredTasksFor(detail),
                        )
                    }
                }
                .catch { error ->
                    emit(OrderDetailUiState(loading = false, error = error.message ?: "读取订单失败"))
                }
                .collect { state -> _uiState.value = state }
        }
    }

    /**
     * Removed tasks of a count-based order with the rounds they were still due in.
     *
     * Loaded from the same read-only round history the detail page shows, and only when
     * the displayed identity actually changed: repeated emissions of an unchanged page
     * do not re-read the whole round history.
     */
    private suspend fun retiredTasksFor(detail: OrderDetail): List<RetiredTaskHistory> {
        if (!detail.countBased) {
            retiredSignature = null
            return emptyList()
        }
        val signature = buildString {
            append(_uiState.value.datasetGeneration).append('#')
            detail.tasks.forEach { task ->
                append(task.id).append(':').append(task.retiredAt).append(':')
                    .append(task.currentValue).append('|')
            }
            detail.sessions.forEach { session ->
                append(session.id).append(':').append(session.completedAt).append('|')
            }
            detail.occurrences.forEach { occurrence ->
                append(occurrence.id).append(':').append(occurrence.completedAt).append(':')
                    .append(occurrence.value).append('|')
            }
        }
        if (signature == retiredSignature) return _uiState.value.retiredTasks
        retiredSignature = signature
        val retired = detail.tasks.filter { it.retiredAt != null }
        if (retired.isEmpty()) return emptyList()
        val history = runCatching { progressRepository.countSessionHistory(orderId) }
            .getOrDefault(emptyList())
        return retired.map { task ->
            val rounds = history.flatMap { entry ->
                entry.retiredDueTasks.filter { it.taskId == task.id }.map { due ->
                    RetiredTaskRound(
                        sessionId = entry.session.id,
                        sessionIndex = entry.session.sessionIndex,
                        attributedDate = due.attributedDate,
                        completed = due.completed,
                        hasObservation = due.hasObservation,
                    )
                }
            }.sortedBy { it.sessionIndex }
            RetiredTaskHistory(
                taskId = task.id,
                title = task.title,
                cumulative = task.progressType == ProgressType.NUMERIC &&
                    task.numericMode == NumericMode.CUMULATIVE,
                retiredAt = task.retiredAt,
                rounds = rounds,
            )
        }
    }

    /** Guards [retiredTasksFor] against re-reading an unchanged round history. */
    private var retiredSignature: String? = null

    /**
     * Recomputes the current game day/window when returning to the foreground. The page
     * reads the dataset again, so it also adopts the current dataset identity; a form
     * instance that was opened earlier keeps its old identity and stays stale.
     */
    fun refresh() {
        refreshTick.value += 1
        _uiState.update { it.copy(datasetGeneration = DatasetGate.currentGeneration()) }
    }

    fun toggleShortTask(taskId: String, completed: Boolean) {
        runWrite {
            orderRepository.setShortTaskCompleted(taskId, completed)
        }
    }

    fun updateShortProgress(taskId: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            emit("请输入有效的数值")
            return
        }
        runWrite {
            orderRepository.updateShortTaskProgress(taskId, value)
        }
    }

    fun completeShortOrder() {
        runBatch {
            orderRepository.completeShortOrder(orderId, UUID.randomUUID().toString())
        }
    }

    fun setCalendarCompletion(
        periodId: String,
        taskId: String,
        date: java.time.LocalDate,
        occurrenceKey: String,
        completed: Boolean,
    ) {
        runWrite {
            orderRepository.setHistoricalCompletion(
                orderId = orderId,
                periodId = periodId,
                taskId = taskId,
                date = date,
                completed = completed,
                weeklyWindowStart = if (occurrenceKey.startsWith("W:")) {
                    java.time.LocalDate.parse(occurrenceKey.substringAfter(":"))
                } else null,
            )
        }
    }

    fun toggleOccurrence(taskId: String, occurrenceKey: String, completed: Boolean) {
        runWrite {
            orderRepository.setOccurrenceCompleted(taskId, completed, occurrenceKey)
        }
    }

    fun updateOccurrenceProgress(taskId: String, occurrenceKey: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            emit("请输入有效的数值")
            return
        }
        runWrite {
            orderRepository.updateOccurrenceProgress(taskId, value, occurrenceKey)
        }
    }

    // ---------------------------------------------------------------- 累计数值（UF-038）

    private fun isCumulative(task: OrderTask): Boolean =
        task.progressType == ProgressType.NUMERIC && task.numericMode == NumericMode.CUMULATIVE

    /**
     * 打开任务的累计面板（当前范围）。计次订单只在需要新的进行中轮次时创建轮次行；
     * 全部轮次已完成时改为打开历史轮次，绝不因为 PERIOD_FULL 而看不到历史。
     */
    fun openCumulative(taskId: String) {
        val detail = _uiState.value.detail ?: return
        val task = detail.tasks.firstOrNull { it.id == taskId } ?: return
        if (!isCumulative(task)) {
            emit("该任务不是累计数值目标")
            return
        }
        val generation = _uiState.value.datasetGeneration
        viewModelScope.launch {
            val sessions = loadSessionOptions()
            val scope: ProgressScope
            val index: Int?
            var historical = false
            when {
                detail.order.type == OrderType.SHORT_TASK -> {
                    scope = ProgressScope.Task
                    index = null
                    historical = detail.order.completedAt != null
                }

                detail.order.hostingMode == HostingMode.COUNT_BASED -> {
                    when (
                        val state = runCatching {
                            progressRepository.resolveActiveCountSession(orderId)
                        }.getOrNull()
                    ) {
                        is CountHosting.ActiveCountState.ActiveSession -> {
                            scope = ProgressScope.Session(state.sessionId)
                            index = state.sessionIndex
                        }

                        is CountHosting.ActiveCountState.AwaitingSessionRow -> {
                            // A pending active round is created only for genuinely new
                            // progress, never for viewing history.
                            val ensured = runCatching {
                                progressRepository.ensureCountSession(
                                    orderId,
                                    state.sessionIndex,
                                    generation,
                                )
                            }.getOrNull()
                            if (ensured == null) {
                                emit("当前没有可记录的轮次，请刷新后重试")
                                return@launch
                            }
                            scope = ProgressScope.Session(ensured.session.id)
                            index = ensured.session.sessionIndex
                        }

                        else -> {
                            val last = sessions.maxWithOrNull(
                                compareBy({ it.periodIndex }, { it.sessionIndex }),
                            )
                            if (last == null) {
                                emit("该订单还没有可查看的打卡轮次")
                                return@launch
                            }
                            scope = ProgressScope.Session(last.sessionId)
                            index = last.sessionIndex
                            historical = true
                        }
                    }
                }

                else -> {
                    val periodId = task.periodId ?: detail.effectivePeriod?.id
                    if (periodId == null) {
                        emit("当前没有可记录的期次，请先刷新后重试")
                        return@launch
                    }
                    scope = ProgressScope.Period(periodId)
                    index = null
                    historical = detail.order.completedAt != null
                }
            }
            _cumulative.value = loadCumulativePanel(
                task = task,
                scope = scope,
                sessionIndex = index,
                generation = generation,
                historical = historical,
                sessions = sessions,
            )
        }
    }

    /**
     * Read-only opening of one specific session, including completed/earlier rounds.
     * No session row is created and the derived active cache is not changed.
     */
    fun openCumulativeHistory(taskId: String, sessionId: String) {
        val detail = _uiState.value.detail ?: return
        val task = detail.tasks.firstOrNull { it.id == taskId } ?: return
        if (!isCumulative(task)) {
            emit("该任务不是累计数值目标")
            return
        }
        val generation = _uiState.value.datasetGeneration
        viewModelScope.launch {
            val sessions = loadSessionOptions()
            val option = sessions.firstOrNull { it.sessionId == sessionId }
            if (option == null) {
                emit("该打卡轮次不存在或不属于此订单")
                return@launch
            }
            _cumulative.value = loadCumulativePanel(
                task = task,
                scope = ProgressScope.Session(sessionId),
                sessionIndex = option.sessionIndex,
                sessionDate = option.attributedDate,
                generation = generation,
                historical = true,
                sessions = sessions,
            )
        }
    }

    /**
     * Explicit session selection from the panel. Viewing another session is read-only: it
     * never ensures a row, never changes the active cache and never rewrites events.
     */
    fun selectCumulativeSession(sessionId: String) {
        val panel = _cumulative.value ?: return
        val detail = _uiState.value.detail ?: return
        val task = detail.tasks.firstOrNull { it.id == panel.taskId } ?: return
        val option = panel.sessions.firstOrNull { it.sessionId == sessionId } ?: return
        viewModelScope.launch {
            _cumulative.value = loadCumulativePanel(
                task = task,
                scope = ProgressScope.Session(sessionId),
                sessionIndex = option.sessionIndex,
                sessionDate = option.attributedDate,
                generation = panel.datasetGeneration,
                historical = true,
                sessions = panel.sessions,
            )
        }
    }

    fun closeCumulative() {
        _cumulative.value = null
    }

    /**
     * 提交**当前累计总值**（不是本次增量）。operationId 只与同一份载荷绑定：相同文本
     * 的重试复用，改过文本则分配新的 operationId，绝不拿同一把钥匙提交不同内容。
     */
    fun submitCumulativeTotal(text: String) {
        val panel = _cumulative.value ?: return
        if (panel.submitting) return
        // A historical round of a **still active** task stays read-only; a removed task may
        // only be completed inside its own round's date, which is the retirement-safe
        // attribution (H1). The repository still enforces the retirement boundary.
        if (panel.historical && panel.historicalSubmitDate == null) return
        val detail = _uiState.value.detail ?: return
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            _cumulative.value = panel.copy(error = "请输入有效的数值（当前累计总值）")
            return
        }
        val operationId = if (panel.pendingSubmitText == text) {
            panel.pendingOperationId ?: UUID.randomUUID().toString()
        } else {
            UUID.randomUUID().toString()
        }
        _cumulative.value = panel.copy(
            submitting = true,
            error = null,
            pendingOperationId = operationId,
            pendingSubmitText = text,
            correctionAttempt = null,
        )
        viewModelScope.launch {
            runCatching {
                progressRepository.submitTotal(
                    SubmitTotalCommand(
                        operationId = operationId,
                        taskId = panel.taskId,
                        scope = panel.scope,
                        attributedGameDate = panel.historicalSubmitDate ?: detail.today,
                        total = value,
                        expectedGeneration = panel.datasetGeneration,
                    ),
                )
            }
                .onSuccess { result ->
                    emit(
                        if (result is com.daigan.record.data.repository.ProgressCommandResult.IdempotentReplay) {
                            "这次提交已经生效过，没有重复记录"
                        } else {
                            "已记录当前累计总值"
                        },
                    )
                    _cumulative.value = panel.copy(
                        submitting = false,
                        error = null,
                        pendingOperationId = null,
                        pendingSubmitText = null,
                    )
                    refreshAndReload(
                        panel.taskId,
                        panel.scope,
                        panel.sessionIndex,
                        historical = panel.historical,
                        sessionDate = panel.historicalSubmitDate,
                    )
                }
                .onFailure { error ->
                    // The attempted key must survive a failed/uncertain submission: the
                    // retry of the same payload is the same command.
                    _cumulative.value = panel.copy(
                        submitting = false,
                        error = error.message ?: "记录失败，请重试",
                        pendingOperationId = operationId,
                        pendingSubmitText = text,
                    )
                }
        }
    }

    /**
     * 更正（有值）或撤销（空值）一条累计事件；历史行与附件全部保留。
     *
     * The correction key is reused only for the same target and the same text. A retry
     * after an uncertain result therefore stays idempotent, while an edited payload gets
     * a new key instead of conflicting with the earlier attempt.
     */
    fun correctCumulativeEvent(eventId: String, text: String) {
        val panel = _cumulative.value ?: return
        if (panel.submitting) return
        val raw = text.trim()
        val value = if (raw.isEmpty()) null else raw.toBigDecimalOrNull()
        if (raw.isNotEmpty() && value == null) {
            _cumulative.value = panel.copy(error = "请输入有效的数值")
            return
        }
        val attempt = panel.correctionAttempt
        val operationId = if (attempt != null && attempt.eventId == eventId && attempt.text == raw) {
            attempt.operationId
        } else {
            UUID.randomUUID().toString()
        }
        _cumulative.value = panel.copy(
            submitting = true,
            error = null,
            correctionAttempt = CorrectionAttempt(eventId, raw, operationId),
        )
        viewModelScope.launch {
            runCatching {
                progressRepository.correctTotal(
                    CorrectTotalCommand(
                        operationId = operationId,
                        taskId = panel.taskId,
                        targetEventId = eventId,
                        total = value,
                        expectedGeneration = panel.datasetGeneration,
                    ),
                )
            }
                .onSuccess {
                    emit(if (value == null) "已撤销该条记录" else "已更正该条记录")
                    _cumulative.value = panel.copy(
                        submitting = false,
                        error = null,
                        correctionAttempt = null,
                    )
                    refreshAndReload(
                        panel.taskId,
                        panel.scope,
                        panel.sessionIndex,
                        historical = panel.historical,
                        sessionDate = panel.historicalSubmitDate,
                    )
                }
                .onFailure { error ->
                    _cumulative.value = panel.copy(
                        submitting = false,
                        error = error.message ?: "更正失败，请重试",
                        correctionAttempt = CorrectionAttempt(eventId, raw, operationId),
                    )
                }
        }
    }

    private suspend fun refreshAndReload(
        taskId: String,
        scope: ProgressScope,
        sessionIndex: Int?,
        historical: Boolean,
        sessionDate: java.time.LocalDate? = null,
    ) {
        refresh()
        val task = _uiState.value.detail?.tasks?.firstOrNull { it.id == taskId } ?: return
        _cumulative.value = loadCumulativePanel(
            task = task,
            scope = scope,
            sessionIndex = sessionIndex,
            sessionDate = sessionDate,
            generation = _uiState.value.datasetGeneration,
            historical = historical,
            sessions = loadSessionOptions(),
        )
    }

    private suspend fun loadSessionOptions(): List<CumulativeSessionOption> {
        val detail = _uiState.value.detail ?: return emptyList()
        if (detail.order.hostingMode != HostingMode.COUNT_BASED) return emptyList()
        val history = runCatching { progressRepository.countSessionHistory(orderId) }
            .getOrDefault(emptyList())
        return history
            .sortedWith(compareBy({ it.periodIndex }, { it.session.sessionIndex }))
            .map {
                CumulativeSessionOption(
                    sessionId = it.session.id,
                    sessionIndex = it.session.sessionIndex,
                    periodIndex = it.periodIndex,
                    completed = it.session.completed,
                    attributedDate = it.attributedDate,
                )
            }
    }

    private suspend fun loadCumulativePanel(
        task: OrderTask,
        scope: ProgressScope,
        sessionIndex: Int?,
        generation: Long,
        historical: Boolean,
        sessions: List<CumulativeSessionOption>,
        sessionDate: java.time.LocalDate? = null,
    ): CumulativePanel {
        val history = runCatching { progressRepository.progressHistory(task.id) }
            .getOrDefault(emptyList())
        val status = runCatching { progressRepository.cumulativeStatus(task.id, scope) }.getOrNull()
        return CumulativePanel(
            taskId = task.id,
            taskTitle = task.title,
            scope = scope,
            targetValue = task.targetValue,
            unit = task.unit,
            currentTotal = status?.total ?: task.startValue,
            events = history.filter { it.scope == scope },
            sessionIndex = sessionIndex,
            scopeLabel = when (scope) {
                is ProgressScope.Task -> if (historical) "整单累计（已完成）" else "整单累计"
                is ProgressScope.Period -> if (historical) "本期累计（已完成）" else "本期累计"
                is ProgressScope.Session -> if (historical) {
                    "第 ${sessionIndex ?: "?"} 次（历史）"
                } else {
                    "第 ${sessionIndex ?: "?"} 次"
                }
            },
            historical = historical,
            datasetGeneration = generation,
            sessions = sessions,
            // A removed task keeps its historical responsibility, so its own round stays
            // usable for a first observation — dated at that round, never at today. The
            // repository re-checks the retirement boundary (H1).
            historicalSubmitDate = if (historical && task.retiredAt != null) sessionDate else null,
        )
    }

    /**
     * H1: complete/retract one historical round of a removed task. The round index and
     * its own game date come from the retired-task history card, so the write can never be
     * moved to another round or another date.
     */
    fun setHistoricalRoundCompletion(taskId: String, sessionIndex: Int, completed: Boolean) {
        if (_uiState.value.maintenanceInFlight) return
        _uiState.value = _uiState.value.copy(maintenanceInFlight = true)
        val generation = _uiState.value.datasetGeneration
        viewModelScope.launch {
            try {
                runCatching {
                    orderRepository.setHistoricalRoundCompletion(
                        orderId = orderId,
                        sessionIndex = sessionIndex,
                        taskId = taskId,
                        completed = completed,
                        expectedGeneration = generation,
                    )
                }.onSuccess {
                    retiredSignature = null
                    refresh()
                    emit(if (completed) "已补记第 $sessionIndex 次打卡" else "已撤销该轮次的补记")
                }.onFailure { error ->
                    emit(error.message ?: "更正历史记录失败，请重试")
                }
            } finally {
                _uiState.value = _uiState.value.copy(maintenanceInFlight = false)
            }
        }
    }

    fun completeHostingOrder() {
        val operationId = UUID.randomUUID().toString()
        val displayed = _uiState.value.detail?.toDisplayedSnapshot()
        launchBatchAction {
            runCatching {
                val snapshot = displayed
                    ?: throw StaleOccurrenceException("展示数据已过期，请刷新后再试")
                orderRepository.completeHostingOrder(orderId, operationId, snapshot)
            }
                .onSuccess { result ->
                    if (result.completedCount > 0) {
                        _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
                    }
                    emit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项" +
                                if (result.skippedCount > 0) {
                                    "，跳过 ${result.skippedCount} 项累计目标（请用「记录累计」输入真实总值）"
                                } else {
                                    ""
                                }
                        },
                    )
                }
                .onFailure { error ->
                    if (error is StaleOccurrenceException) {
                        refresh()
                        emit("已跨过刷新时间，详情已刷新；请按新的游戏日/游戏周重新确认")
                    } else {
                        emit("完成失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    /** UF-033 correction path: reopen one completed round; the total drops by one. */
    fun reopenCountSession(sessionIndex: Int) {
        val generation = _uiState.value.datasetGeneration
        runWrite {
            orderRepository.reopenCountSession(orderId, sessionIndex, generation)
            emit("已撤销第 $sessionIndex 次打卡，可重新完成这一轮")
        }
    }

    fun undoLastBatch() {
        val batchId = _uiState.value.lastBatchId ?: return
        launchBatchAction {
            runCatching { orderRepository.undoBatch(batchId) }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(
                        lastBatchId = null,
                        undoResult = result,
                    )
                }
                .onFailure { emit("撤销失败：${it.message ?: "未知错误"}") }
        }
    }

    fun clearUndoResult() {
        _uiState.value = _uiState.value.copy(undoResult = null)
    }

    fun addAttachment(taskDefinitionId: String?, occurrenceId: String?, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                attachmentRepository.addAttachment(taskDefinitionId, occurrenceId, uri)
            }.onSuccess { emit("已添加截图") }
                .onFailure { emit("添加截图失败：${it.message ?: "无法读取图片"}") }
        }
    }

    fun replaceAttachment(attachmentId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching { attachmentRepository.replaceAttachment(attachmentId, uri) }
                .onSuccess { emit("已替换截图") }
                .onFailure { emit("替换截图失败，原截图已保留：${it.message ?: "无法读取图片"}") }
        }
    }

    fun deleteAttachment(attachmentId: String) {
        viewModelScope.launch {
            runCatching { attachmentRepository.deleteAttachment(attachmentId) }
                .onSuccess { emit("已删除截图") }
                .onFailure { emit("删除截图失败：${it.message ?: "未知错误"}") }
        }
    }

    fun fileFor(attachment: Attachment): File = attachmentRepository.fileFor(attachment)

    fun renew(operationId: String, durationDays: Int, priceYuan: String) {
        val cents = com.daigan.record.data.model.Money.parseYuanToCentsOrNull(priceYuan)
        if (cents == null) {
            emit("续期价格需为不超过两位小数的非负数字")
            return
        }
        if (durationDays <= 0) {
            emit("续期天数必须大于 0")
            return
        }
        viewModelScope.launch {
            runCatching {
                orderRepository.renewHosting(orderId, operationId, durationDays, cents)
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(lastBatchId = null)
                _messages.emit(
                    if (result.alreadyApplied) {
                        "该续期请求已经生效，未重复创建期次"
                    } else {
                        "已续期：第 ${result.periodIndex + 1} 期，${result.startDate} 至 ${result.endDate}"
                    },
                )
                _renewalApplied.emit(result)
            }.onFailure { emit("续期失败：${it.message ?: "未知错误"}") }
        }
    }

    /**
     * 计次托管追加一个独立期次：该期次有自己的目标次数、价格与任务快照，历史期次与
     * 轮次全部保留，且**不**伪造任何日历截止日。
     */
    fun renewCount(targetSessionsText: String, priceYuan: String, operationId: String) {
        val target = targetSessionsText.trim().toIntOrNull()
        if (target == null || target < 1) {
            emit("打卡次数需为 1–${CountHosting.MAX_TARGET_SESSIONS} 的整数")
            return
        }
        val cents = Money.parseYuanToCentsOrNull(priceYuan)
        if (cents == null) {
            emit("本期价格需为不超过两位小数的非负数字")
            return
        }
        val generation = _uiState.value.datasetGeneration
        viewModelScope.launch {
            runCatching {
                orderRepository.renewCountHosting(
                    orderId = orderId,
                    operationId = operationId,
                    targetSessions = target,
                    priceCents = cents,
                    expectedGeneration = generation,
                )
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(lastBatchId = null)
                _messages.emit(
                    if (result.alreadyApplied) {
                        "该续期请求已经生效，未重复创建期次"
                    } else {
                        "已追加第 ${result.periodIndex + 1} 期：$target 次，可分别记录该期收款"
                    },
                )
                _renewalApplied.emit(result)
            }.onFailure { emit("续期失败：${it.message ?: "未知错误"}") }
        }
    }

    /** Fresh clock reading for a payment form default. */
    fun now(): Instant = timeProvider.now()

    /**
     * Records a payment against an explicitly chosen period (or the short order).
     * The gate is acquired synchronously before the coroutine starts, so two taps in
     * the same frame cannot both reach the repository. [operationId] comes from the
     * dialog and is kept for retries of the same submission.
     */
    fun recordPayment(
        periodId: String?,
        amountYuan: String,
        receivedAt: Instant,
        channel: PaymentChannel,
        note: String,
        operationId: String,
    ) {
        val cents = Money.parsePositiveYuanToCentsOrNull(amountYuan)
        if (cents == null) {
            emit("请输入大于 0 且最多两位小数的收款金额")
            return
        }
        if (operationId.isBlank()) {
            emit("缺少收款操作标识，请重新打开收款窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.recordPayment(
                    PaymentRequest(
                        orderId = orderId,
                        periodId = periodId,
                        amountCents = cents,
                        receivedAt = receivedAt,
                        channel = channel,
                        note = note,
                        operationId = operationId,
                    ),
                )
            }.onSuccess { result ->
                emit(
                    if (result.alreadyApplied) {
                        "该笔收款已经记录，未重复入账"
                    } else {
                        "已记录实收 ${Money.formatCents(result.receipt.amountCents)}（${result.receipt.channel.label}）"
                    },
                )
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("收款失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun amendReceipt(
        receiptId: String,
        amountYuan: String,
        receivedAt: Instant,
        channel: PaymentChannel,
        note: String,
        reason: String,
        operationId: String,
    ) {
        val cents = Money.parsePositiveYuanToCentsOrNull(amountYuan)
        if (cents == null) {
            emit("请输入大于 0 且最多两位小数的更正金额")
            return
        }
        if (operationId.isBlank()) {
            emit("缺少更正操作标识，请重新打开更正窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.correctReceipt(
                    ReceiptCorrectionRequest.Amend(
                        operationId = operationId,
                        receiptId = receiptId,
                        amountCents = cents,
                        receivedAt = receivedAt,
                        channel = channel,
                        note = note,
                        reason = reason,
                    ),
                )
            }.onSuccess { result ->
                emit(
                    if (result.alreadyApplied) {
                        "该更正已经生效，未重复处理"
                    } else {
                        "已更正收款，原记录保留在流水中"
                    },
                )
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("更正失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun voidReceipt(receiptId: String, reason: String, operationId: String) {
        if (operationId.isBlank()) {
            emit("缺少作废操作标识，请重新打开作废窗口")
            return
        }
        runFinancial {
            runCatching {
                receiptRepository.correctReceipt(
                    ReceiptCorrectionRequest.Void(
                        operationId = operationId,
                        receiptId = receiptId,
                        reason = reason,
                    ),
                )
            }.onSuccess { result ->
                emit(if (result.alreadyApplied) "该收款已经作废" else "已作废该笔收款，记录仍保留在流水中")
                _financialApplied.tryEmit(Unit)
            }.onFailure { error ->
                emit("作废失败：${error.message ?: "未知错误"}")
            }
        }
    }

    private fun runFinancial(block: suspend () -> Unit) {
        if (financialActionInFlight) return
        financialActionInFlight = true
        _uiState.value = _uiState.value.copy(paymentInFlight = true)
        viewModelScope.launch {
            try {
                block()
            } finally {
                financialActionInFlight = false
                _uiState.value = _uiState.value.copy(paymentInFlight = false)
            }
        }
    }

    /** Serialize completion and undo; a no-op must retain the last useful undo target. */
    private fun launchBatchAction(block: suspend () -> Unit) {
        if (batchActionInFlight) return
        batchActionInFlight = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                batchActionInFlight = false
            }
        }
    }

    private fun runWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    if (error is StaleOccurrenceException) {
                        refresh()
                        emit("已跨过刷新时间，详情已刷新；请按新的游戏日/游戏周重新确认")
                    } else {
                        emit(error.message ?: "操作失败，请重试")
                    }
                }
        }
    }

    private fun runBatch(block: suspend () -> com.daigan.record.data.model.BulkCompletionResult) {
        launchBatchAction {
            runCatching { block() }
                .onSuccess { result ->
                    if (result.completedCount > 0) {
                        _uiState.value = _uiState.value.copy(lastBatchId = result.batchId)
                    }
                    emit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项" +
                                if (result.skippedCount > 0) {
                                    "，跳过 ${result.skippedCount} 项累计目标（请用「记录累计」输入真实总值）"
                                } else {
                                    ""
                                }
                        },
                    )
                }
                .onFailure { emit("完成失败：${it.message ?: "未知错误"}") }
        }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(trim()) }.getOrNull()
}

/**
 * Captures what the order-detail page is showing for the current hosting period: the
 * period identity, the game date and each task's occurrence window. Validated inside
 * the bulk-completion transaction so a crossed refresh boundary cannot complete a
 * different day's tasks.
 */
fun OrderDetail.toDisplayedSnapshot(): DisplayedHostingSnapshot? {
    // A count-based order owns one container period; the round keys identify the work.
    val period = currentPeriod ?: periods.firstOrNull() ?: return null
    return DisplayedHostingSnapshot(
        orderId = order.id,
        periodId = period.id,
        gameDate = today,
        occurrenceKeys = currentHostingTasks().associate { it.task.id to it.slot.key },
    )
}
