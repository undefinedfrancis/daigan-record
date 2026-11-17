package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderEditDraft
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.model.withPeriod
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrderEditUiState(
    val loading: Boolean = true,
    val detail: OrderDetail? = null,
    val draft: OrderEditDraft? = null,
    val owners: List<OwnerSummary> = emptyList(),
    /** Field-level problems; the first one is also shown as a summary near 保存. */
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    /** Repository rejection of the save itself; the draft is untouched. */
    val saveError: String? = null,
    val loadError: String? = null,
) {
    /** Only an order without business history may be re-homed (see the repository guard). */
    val identityEditable: Boolean
        get() = detail != null && !detail.hasBusinessHistory() && !detail.order.cancelled &&
            !detail.order.archived && !detail.owner.archived && !detail.account.archived &&
            // A count-based order's rounds belong to this order/its container period, so
            // moving it or changing its type is refused rather than silently losing them.
            detail.order.hostingMode != com.daigan.record.data.model.HostingMode.COUNT_BASED
}

/**
 * Owns the one edit draft of the unified order editor (UF-030).
 *
 * The draft lives in the ViewModel — not in `rememberSaveable` — so a rejected save keeps
 * exactly what the user typed while the page stays open; nothing is written until the
 * single 保存 action reaches `OrderRepository.applyOrderEdit`, and 取消 simply leaves.
 */
@HiltViewModel
class OrderEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository,
    ownerRepository: OwnerRepository,
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle[Routes.ORDER_ID_ARG]) {
        "orderId 参数缺失"
    }

    private val _uiState = MutableStateFlow(OrderEditUiState())
    val uiState: StateFlow<OrderEditUiState> = _uiState.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once the whole edit committed; the screen then returns to the detail page. */
    val saved: SharedFlow<Unit> = _saved

    init {
        viewModelScope.launch {
            orderRepository.observeOrderDetail(orderId)
                .catch { error ->
                    _uiState.update {
                        it.copy(loading = false, loadError = error.message ?: "读取订单失败")
                    }
                }
                .collect { detail ->
                    if (detail == null) {
                        _uiState.update { it.copy(loading = false, loadError = "找不到该订单") }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                loading = false,
                                loadError = null,
                                detail = detail,
                                // Keep the user's draft across database refreshes.
                                draft = state.draft ?: OrderEditDraft.of(detail),
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            ownerRepository.observeActiveOwners().collect { owners ->
                _uiState.update { it.copy(owners = owners) }
            }
        }
    }

    /** Applies one field change without touching whatever was already typed elsewhere. */
    fun updateDraft(transform: (OrderEditDraft) -> OrderEditDraft) {
        if (_uiState.value.saving) return
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = transform(draft), errors = emptyMap(), saveError = null)
        }
    }

    /**
     * Switches the hosting period the edit applies to — historical, current or future.
     *
     * Dates, price, service plan and task definitions are reloaded from that period so a
     * correction can never be carried into another period by accident. Order-level fields
     * (name, notes, payment mode) stay as typed.
     */
    fun selectPeriod(periodId: String) {
        val state = _uiState.value
        val detail = state.detail ?: return
        val draft = state.draft ?: return
        if (detail.periods.none { it.id == periodId }) return
        _uiState.update {
            it.copy(
                draft = draft.withPeriod(detail, periodId),
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        val detail = state.detail ?: return
        val draft = state.draft ?: return
        if (state.saving) return
        val errors = draft.validate(detail)
        if (errors.isNotEmpty()) {
            _uiState.update {
                it.copy(errors = errors, saveError = "请先修正标记的问题，再保存；本次没有写入任何改动。")
            }
            return
        }
        _uiState.update { it.copy(saving = true, errors = emptyMap(), saveError = null) }
        viewModelScope.launch {
            runCatching { orderRepository.applyOrderEdit(draft) }
                .onSuccess {
                    _saved.tryEmit(Unit)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // Nothing was committed (one transaction); the draft stays editable.
                    _uiState.update {
                        it.copy(saving = false, saveError = error.message ?: "保存失败，请重试")
                    }
                }
        }
    }
}
