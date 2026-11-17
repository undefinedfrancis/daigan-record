package com.daigan.record.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.UndoResult
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.preferences.UiPreferenceStore
import com.daigan.record.data.repository.StaleOccurrenceException
import com.daigan.record.data.repository.toDisplayedSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TodayTab(val label: String) {
    HOSTING("托管打卡"),
    SHORT_TASK("短期任务"),
}

data class TodayUiState(
    val dateLabel: String = "",
    val selectedTab: TodayTab = TodayTab.HOSTING,
    val hosting: List<TodayHostingOrder> = emptyList(),
    val shortOrders: List<ShortWorkOrder> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val lastBatchId: String? = null,
    val undoResult: UndoResult? = null,
    val completedExpanded: Boolean = false,
    /** Order-colour filter (UF-028); colour is set on the order, never here. */
    val colorFilter: TodayColorFilter = TodayColorFilter.All,
    val groups: Map<String, String> = emptyMap(),
) {
    /** Hosting orders the current colour filter keeps visible. */
    val filteredHosting: List<TodayHostingOrder>
        get() = TodayColorFiltering.hosting(hosting, colorFilter)

    val filteredShortOrders: List<ShortWorkOrder>
        get() = TodayColorFiltering.short(shortOrders, colorFilter)

    val activeHosting: List<TodayHostingOrder>
        // Expired orders remain available in order details/history, but no longer
        // occupy the default daily-work list after their service date ends.
        // A count-based order leaves this list only when its agreed rounds are done.
        get() = filteredHosting.filter { !it.expired && !it.allTasksCompleted && !it.sessionsComplete }

    val completedHosting: List<TodayHostingOrder>
        get() = filteredHosting.filter {
            !it.expired && (it.allTasksCompleted || it.sessionsComplete)
        }

    /**
     * Colours present in today's work, in palette order (chips stay compact). The active
     * filter colour is kept even when it currently matches nothing, so its chip does not
     * vanish while the list is filtered by it.
     */
    val usedColors: List<com.daigan.record.ui.theme.OrderColorOption>
        get() {
            val used = hosting.map { it.order.colorArgb } + shortOrders.map { it.order.colorArgb }
            val active = (colorFilter as? TodayColorFilter.Matched)?.argb
            return com.daigan.record.ui.theme.OrderColors.usedBy(
                if (active == null) used else used + active,
            )
        }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val timeProvider: TimeProvider,
    private val orderRepository: OrderRepository,
    private val preferences: UiPreferenceStore,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(TodayTab.HOSTING)
    private val refreshTick = MutableStateFlow(0L)
    // ViewModel actions run on Main; acquire before launch so queued taps cannot overlap.
    private var batchActionInFlight = false
    private val lastBatchId = MutableStateFlow<String?>(null)
    private val undoResult = MutableStateFlow<UndoResult?>(null)
    private val completedExpanded = MutableStateFlow(false)
    private val colorFilter = MutableStateFlow<TodayColorFilter>(TodayColorFilter.All)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val work: kotlinx.coroutines.flow.Flow<Pair<List<TodayHostingOrder>, List<ShortWorkOrder>>> =
        refreshTick.flatMapLatest {
            combine(
                orderRepository.observeTodayHosting(),
                orderRepository.observeTodayShort(),
            ) { hosting, short -> hosting to short }
        }

    private val baseUiState = combine(
        selectedTab,
        work,
        lastBatchId,
        undoResult,
        completedExpanded,
    ) { tab, (hosting, shortOrders), batchId, undo, expanded ->
        TodayUiState(
            dateLabel = dateLabel(),
            selectedTab = tab,
            hosting = hosting,
            shortOrders = shortOrders,
            loading = false,
            lastBatchId = batchId,
            undoResult = undo,
            completedExpanded = expanded,
        )
    }

    val uiState: StateFlow<TodayUiState> = combine(
        baseUiState,
        colorFilter,
        preferences.observeTodayGroups(),
    ) { state, filter, groups -> state.copy(colorFilter = filter, groups = groups) }
        .catch { error ->
            emit(
                TodayUiState(
                    dateLabel = dateLabel(),
                    loading = false,
                    error = error.message ?: "读取今日事项失败",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(dateLabel = dateLabel(), selectedTab = TodayTab.HOSTING),
        )

    fun selectTab(tab: TodayTab) {
        selectedTab.value = tab
    }

    /** Recomputes game dates on resume so a day/window boundary is not stale. */
    fun refresh() {
        refreshTick.value += 1
    }

    fun toggleCompletedSection() {
        completedExpanded.value = !completedExpanded.value
    }

    /**
     * Colour filtering only. Assigning a colour happens on the order detail page
     * (UF-028), so this page can no longer recolour a whole account by mistake.
     */
    fun setColorFilter(filter: TodayColorFilter) {
        colorFilter.value = filter
    }

    fun setTaskGroup(key: String, group: String?) = viewModelScope.launch { preferences.setTodayGroup(key, group) }

    fun completeAllHosting() {
        val operationId = UUID.randomUUID().toString()
        // Only what the current colour filter shows is completed: a hidden order must not
        // be affected by a bulk action the user cannot see.
        val displayed = uiState.value.filteredHosting.map { it.toDisplayedSnapshot() }
        launchBatchAction {
            runCatching { orderRepository.completeAllHosting(operationId, displayed) }
                .onSuccess { result ->
                    if (result.completedCount > 0) lastBatchId.value = result.batchId
                    _messages.tryEmit(
                        if (result.alreadyApplied) {
                            "该批量操作已经生效"
                        } else {
                            "已完成 ${result.completedCount} 项托管任务" +
                                if (result.skippedCount > 0) {
                                    "，跳过 ${result.skippedCount} 项累计目标（需输入真实总值）"
                                } else {
                                    ""
                                }
                        },
                    )
                }
                .onFailure { refreshAfterStale(it, "批量完成失败") }
        }
    }

    fun completeHostingOrder(orderId: String) {
        val operationId = UUID.randomUUID().toString()
        val displayed = uiState.value.filteredHosting
            .firstOrNull { it.order.id == orderId }
            ?.toDisplayedSnapshot()
        launchBatchAction {
            runCatching {
                val snapshot = displayed
                    ?: throw StaleOccurrenceException("展示数据已过期，请刷新后再试")
                orderRepository.completeHostingOrder(orderId, operationId, snapshot)
            }
                .onSuccess { result ->
                    if (result.completedCount > 0) lastBatchId.value = result.batchId
                    _messages.tryEmit(
                        "该单已完成 ${result.completedCount} 项" +
                            if (result.skippedCount > 0) {
                                "，跳过 ${result.skippedCount} 项累计目标（请在订单详情记录当前总值）"
                            } else {
                                ""
                            },
                    )
                }
                .onFailure { refreshAfterStale(it, "整单完成失败") }
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

    /**
     * A stale bulk submission means a refresh boundary was crossed between render and
     * tap. Refresh and explain rather than silently completing another game day.
     */
    private fun refreshAfterStale(error: Throwable, prefix: String) {
        if (error is StaleOccurrenceException) {
            refresh()
            _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
        } else {
            _messages.tryEmit("$prefix：${error.message ?: "未知错误"}")
        }
    }

    fun toggleHostingTask(taskId: String, occurrenceKey: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching {
                orderRepository.setOccurrenceCompleted(taskId, completed, occurrenceKey)
            }.onFailure { error ->
                if (error is StaleOccurrenceException) {
                    refresh()
                    _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
                } else {
                    _messages.tryEmit("更新打卡失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun updateHostingProgress(taskId: String, occurrenceKey: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            _messages.tryEmit("请输入有效的数值")
            return
        }
        viewModelScope.launch {
            runCatching {
                orderRepository.updateOccurrenceProgress(taskId, value, occurrenceKey)
            }.onFailure { error ->
                if (error is StaleOccurrenceException) {
                    refresh()
                    _messages.tryEmit("已跨过刷新时间，列表已刷新，请确认新的游戏日/游戏周后再操作")
                } else {
                    _messages.tryEmit("更新进度失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun toggleShortTask(taskId: String, completed: Boolean) {
        viewModelScope.launch {
            runCatching { orderRepository.setShortTaskCompleted(taskId, completed) }
                .onFailure { _messages.tryEmit("更新任务失败：${it.message ?: "未知错误"}") }
        }
    }

    fun updateShortProgress(taskId: String, text: String) {
        val value = text.trim().toBigDecimalOrNull()
        if (value == null) {
            _messages.tryEmit("请输入有效的数值")
            return
        }
        viewModelScope.launch {
            runCatching { orderRepository.updateShortTaskProgress(taskId, value) }
                .onFailure { _messages.tryEmit("更新进度失败：${it.message ?: "未知错误"}") }
        }
    }

    fun undoLastBatch() {
        val batchId = lastBatchId.value ?: return
        launchBatchAction {
            runCatching { orderRepository.undoBatch(batchId) }
                .onSuccess { result ->
                    lastBatchId.value = null
                    undoResult.value = result
                }
                .onFailure { _messages.tryEmit("撤销失败：${it.message ?: "未知错误"}") }
        }
    }

    fun clearUndoResult() {
        undoResult.value = null
    }

    private fun dateLabel(): String =
        DateTimeFormatter
            .ofPattern("M 月 d 日 · EEEE", Locale.SIMPLIFIED_CHINESE)
            .format(timeProvider.now().atZone(ZoneId.systemDefault()))

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(trim()) }.getOrNull()
}
