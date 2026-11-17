package com.daigan.record.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.repository.AttachmentFileCleanup
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OwnerDeletionImpact
import com.daigan.record.data.repository.OwnerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ArchivedRecordsViewModel @Inject constructor(
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    /** Post-commit file cleanup, declared here so a killed screen is retried on start. */
    private val attachmentCleanup: AttachmentFileCleanup,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** Non-null while the destructive owner confirmation is open. */
    private val _deletionImpact = MutableStateFlow<OwnerDeletionImpact?>(null)
    val deletionImpact: StateFlow<OwnerDeletionImpact?> = _deletionImpact.asStateFlow()

    private val _deletionInFlight = MutableStateFlow(false)
    val deletionInFlight: StateFlow<Boolean> = _deletionInFlight.asStateFlow()

    val uiState: StateFlow<ArchivedRecordsUiState> =
        combine(
            ownerRepository.observeArchivedOwners(),
            accountRepository.observeArchivedAccounts(),
        ) { owners, accounts ->
            ArchivedRecordsUiState(
                isLoading = false,
                archivedOwners = owners,
                archivedAccounts = accounts,
            )
        }
            .catch {
                emit(ArchivedRecordsUiState(isLoading = false, error = "读取归档记录失败，请重试。"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ArchivedRecordsUiState(),
            )

    fun restoreOwner(ownerId: String) {
        viewModelScope.launch {
            runCatching { ownerRepository.restoreOwner(ownerId) }
                // Accounts archived separately stay archived, so do not promise more.
                .onSuccess { _messages.emit("已恢复老板") }
                .onFailure { _messages.emit("恢复失败：${it.message ?: "未知错误"}") }
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
     * UF-031: an archived owner can be deleted directly, without restoring first. The
     * real affected counts and money are read before the confirmation is shown.
     */
    fun requestOwnerDeletion(ownerId: String) {
        if (_deletionInFlight.value) return
        viewModelScope.launch {
            runCatching { ownerRepository.ownerDeletionImpact(ownerId) }
                .onSuccess { _deletionImpact.value = it }
                .onFailure { _messages.emit("无法读取删除影响：${it.message ?: "未知错误"}") }
        }
    }

    /** Cancelling changes nothing: the owner stays archived with all its data. */
    fun cancelOwnerDeletion() {
        if (_deletionInFlight.value) return
        _deletionImpact.value = null
    }

    fun confirmOwnerDeletion() {
        val impact = _deletionImpact.value ?: return
        if (_deletionInFlight.value) return
        _deletionInFlight.value = true
        viewModelScope.launch {
            try {
                runCatching { ownerRepository.deleteArchivedOwner(impact.ownerId) }
                    .onSuccess { summary ->
                        _deletionImpact.value = null
                        // Rows are committed. Files are removed now; a failure leaves no
                        // dangling rows and is retried by the start-up orphan pass.
                        val cleanupFailed = runCatching { attachmentCleanup.cleanupOrphans() }
                            .isFailure
                        _messages.emit(
                            buildString {
                                append("已删除老板「${summary.ownerNickname}」")
                                if (summary.deletedAccountCount > 0) {
                                    append("、${summary.deletedAccountCount} 个游戏账号")
                                }
                                if (summary.deletedOrderCount > 0) {
                                    append("、${summary.deletedOrderCount} 个订单")
                                }
                                append("及其关联记录")
                                if (cleanupFailed) append("；截图文件将在下次启动时清理")
                            },
                        )
                    }
                    .onFailure { error ->
                        _messages.emit("删除失败：${error.message ?: "未知错误"}")
                    }
            } finally {
                _deletionInFlight.value = false
            }
        }
    }
}
