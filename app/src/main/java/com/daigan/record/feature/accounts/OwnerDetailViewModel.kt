package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.repository.AccountDeletionImpact
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class OwnerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    orderRepository: OrderRepository,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** Account awaiting a delete confirmation; its impact is loaded before the dialog. */
    private val _deletePendingAccountId = MutableStateFlow<String?>(null)
    val deletePendingAccountId: StateFlow<String?> = _deletePendingAccountId

    private val _deleteImpact = MutableStateFlow<AccountDeletionImpact?>(null)
    val deleteImpact: StateFlow<AccountDeletionImpact?> = _deleteImpact

    val uiState: StateFlow<OwnerDetailUiState> = combine(
        ownerRepository.observeOwner(ownerId),
        orderRepository.observeOrdersForOwner(ownerId),
    ) { detail, orders ->
        if (detail == null) {
            OwnerDetailUiState.NotFound
        } else {
            OwnerDetailUiState.Success(
                detail = detail,
                ordersByAccount = orders.groupBy { it.accountId },
            )
        }
    }
        .catch { emit(OwnerDetailUiState.Error("读取老板资料失败，请重试。")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OwnerDetailUiState.Loading,
        )

    fun archiveOwner() {
        viewModelScope.launch {
            runCatching { ownerRepository.archiveOwner(ownerId) }
                .onSuccess { _messages.emit("已归档老板，可在归档记录中恢复") }
                .onFailure { _messages.emit("归档失败：${it.message ?: "未知错误"}") }
        }
    }

    fun restoreOwner() {
        viewModelScope.launch {
            runCatching { ownerRepository.restoreOwner(ownerId) }
                .onSuccess { _messages.emit("已恢复老板") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    fun archiveAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { accountRepository.archiveAccount(accountId) }
                .onSuccess { _messages.emit("已归档游戏账号") }
                .onFailure { _messages.emit("归档失败：${it.message ?: "未知错误"}") }
        }
    }

    fun restoreAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { accountRepository.restoreAccount(accountId) }
                .onSuccess { _messages.emit("已恢复游戏账号") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    /**
     * Opens the confirmation with concrete counts and money (UF-007). Reading the impact
     * first means the dialog never claims "全部删除" without saying how much disappears.
     */
    fun requestDeleteAccount(accountId: String) {
        _deletePendingAccountId.value = accountId
        _deleteImpact.value = null
        viewModelScope.launch {
            runCatching { accountRepository.deletionImpact(accountId) }
                .onSuccess { impact ->
                    if (_deletePendingAccountId.value == accountId) _deleteImpact.value = impact
                }
                .onFailure { error ->
                    _deletePendingAccountId.value = null
                    _deleteImpact.value = null
                    _messages.emit("无法读取删除影响：${error.message ?: "未知错误"}")
                }
        }
    }

    fun dismissDeleteAccount() {
        _deletePendingAccountId.value = null
        _deleteImpact.value = null
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            runCatching { accountRepository.deleteAccount(accountId) }
                .onSuccess { summary ->
                    _deletePendingAccountId.value = null
                    _deleteImpact.value = null
                    _messages.emit(
                        "已删除「${summary.gameName}」及 ${summary.deletedOrderCount} 个关联订单" +
                            if (summary.attachmentCleanupDeferred) "；部分截图文件将在下次启动后清理" else "",
                    )
                }
                .onFailure { _messages.emit("删除失败，数据未变更：${it.message ?: "未知错误"}") }
        }
    }
}
