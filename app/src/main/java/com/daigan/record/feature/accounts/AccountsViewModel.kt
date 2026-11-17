package com.daigan.record.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.AccountCategory
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.preferences.UiPreferenceStore
import com.daigan.record.data.repository.AccountCategoryRepository
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.data.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountsViewModel @Inject constructor(
    ownerRepository: OwnerRepository,
    orderRepository: OrderRepository,
    accountRepository: GameAccountRepository,
    private val categoryRepository: AccountCategoryRepository,
    private val uiPreferenceStore: UiPreferenceStore,
) : ViewModel() {

    /** 视图偏好本机保存（UF-037）：切到账号视图后重进仍保持。 */
    val viewMode: StateFlow<String> = uiPreferenceStore.observeAccountViewMode()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = com.daigan.record.data.preferences.SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_OWNER,
        )

    val categoryFilter: StateFlow<String?> = uiPreferenceStore.observeAccountCategoryFilter()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    val uiState: StateFlow<OwnersUiState> = combine(
        combineOwners(ownerRepository, orderRepository),
        accountRepository.observeActiveAccounts(),
        categoryRepository.observeCategories(),
    ) { base, accounts, categories ->
        if (base !is OwnersUiState.Success) return@combine base
        base.copy(
            accounts = directoryRows(base.owners, accounts, categories),
            categories = categories,
        )
    }
        .catch { emit(OwnersUiState.Error("读取老板资料失败，请重试。")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OwnersUiState.Loading,
        )

    fun setViewMode(mode: String) {
        viewModelScope.launch { uiPreferenceStore.setAccountViewMode(mode) }
    }

    fun setCategoryFilter(categoryId: String?) {
        viewModelScope.launch { uiPreferenceStore.setAccountCategoryFilter(categoryId) }
    }

    /** 分类管理的操作结果（含删除后被置为未分类的账号数）。 */
    private val _categoryMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val categoryMessage: StateFlow<String?> = _categoryMessage

    fun renameCategory(categoryId: String, name: String) {
        viewModelScope.launch {
            val result = runCatching { categoryRepository.renameCategory(categoryId, name) }
            _categoryMessage.value = result.fold(
                onSuccess = { "已重命名" },
                onFailure = { it.message ?: "重命名失败，请重试。" },
            )
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            val result = runCatching { categoryRepository.deleteCategory(categoryId) }
            _categoryMessage.value = result.fold(
                onSuccess = { affected ->
                    if (affected == 0) "已删除分类" else "已删除分类，$affected 个账号回到未分类"
                },
                onFailure = { it.message ?: "删除失败，请重试。" },
            )
        }
    }

    fun clearCategoryMessage() {
        _categoryMessage.value = null
    }

    private fun combineOwners(
        ownerRepository: OwnerRepository,
        orderRepository: OrderRepository,
    ): Flow<OwnersUiState> = combine(
        ownerRepository.observeActiveOwners(),
        ownerRepository.observeArchivedOwners(),
        orderRepository.observeTodayHosting(),
        orderRepository.observeTodayShort(),
    ) { active, archived, hosting, shortOrders ->
        val unfinishedByOwner = (
            hosting.filter { !it.expired }.map { it.ownerId to it.order.id } +
                shortOrders.filter { !it.order.completed }.map { it.ownerId to it.order.id }
            ).distinct().groupingBy { it.first }.eachCount()
        val owners = active.map { summary ->
            summary.copy(unfinishedOrderCount = unfinishedByOwner[summary.owner.id] ?: 0)
        }.sortedWith(
            compareByDescending<OwnerSummary> { it.unfinishedOrderCount > 0 }
                .thenBy { it.owner.number },
        )
        OwnersUiState.Success(owners = owners, archivedCount = archived.size) as OwnersUiState
    }

    /**
     * 账号目录行：老板身份随行展示，但行身份始终是 accountId。分类名在分类被删除后
     * 自然回到 null（未分类），不会留下悬空名称。
     */
    private fun directoryRows(
        owners: List<OwnerSummary>,
        accounts: List<GameAccount>,
        categories: List<AccountCategory>,
    ): List<AccountDirectoryRow> {
        val ownersById = owners.associateBy { it.owner.id }
        val categoriesById = categories.associateBy { it.id }
        return accounts.mapNotNull { account ->
            val owner = ownersById[account.ownerId] ?: return@mapNotNull null
            AccountDirectoryRow(
                account = account,
                ownerId = owner.owner.id,
                ownerNickname = owner.owner.nickname,
                ownerNumberLabel = owner.owner.displayNumber,
                categoryName = account.categoryId?.let { categoriesById[it]?.name },
            )
        }
    }
}
