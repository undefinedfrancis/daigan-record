package com.daigan.record.feature.accounts

import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.AccountField
import com.daigan.record.data.model.ArchivedAccount
import com.daigan.record.data.model.OwnerDetail
import com.daigan.record.data.model.OwnerSummary

sealed interface OwnersUiState {
    data object Loading : OwnersUiState

    data class Success(
        val owners: List<OwnerSummary>,
        val archivedCount: Int,
        /** 跨老板账号视图的行（UF-037）；按老板与分类筛选由 UI 完成。 */
        val accounts: List<AccountDirectoryRow> = emptyList(),
        /** 全部分类，用于筛选与分类管理。 */
        val categories: List<com.daigan.record.data.model.AccountCategory> = emptyList(),
    ) : OwnersUiState

    data class Error(val message: String) : OwnersUiState
}

/**
 * 一行账号目录（UF-037）。操作始终使用 [account] 的 id：显示名重复的账号分属不同老板时
 * 也不会选错对象。
 */
data class AccountDirectoryRow(
    val account: com.daigan.record.data.model.GameAccount,
    val ownerId: String,
    val ownerNickname: String,
    val ownerNumberLabel: String,
    val categoryName: String?,
)

sealed interface OwnerDetailUiState {
    data object Loading : OwnerDetailUiState

    data class Success(
        val detail: OwnerDetail,
        val ordersByAccount: Map<String, List<com.daigan.record.data.model.OrderSummary>> =
            emptyMap(),
    ) : OwnerDetailUiState

    data object NotFound : OwnerDetailUiState

    data class Error(val message: String) : OwnerDetailUiState
}

data class OwnerFormUiState(
    val isEdit: Boolean = false,
    val numberLabel: String = "自动生成",
    val nickname: String = "",
    val nicknameError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val completed: Boolean = false,
)

data class AccountFormUiState(
    val isEdit: Boolean = false,
    val ownerNickname: String? = null,
    /** True when the form was opened before its owner was archived (stale form). */
    val ownerArchived: Boolean = false,
    val draft: AccountDraft = AccountDraft(),
    val errors: Map<AccountField, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val completed: Boolean = false,
    /** Optional template preselected for new orders on this account. */
    val templates: List<com.daigan.record.data.model.GameTemplateSummary> = emptyList(),
    val templateName: String? = null,
    /** 区服 options configured for the draft's game name; empty when none. */
    val serverOptions: List<String> = emptyList(),
    /** 可供选择的账号分类（UF-037）；空列表即「仅未分类」。 */
    val categories: List<com.daigan.record.data.model.AccountCategory> = emptyList(),
    /** 当前草稿所属分类名；null 表示未分类。 */
    val categoryName: String? = null,
    /** 新建分类的写入状态。 */
    val categoryBusy: Boolean = false,
    val categoryError: String? = null,
)

data class ArchivedRecordsUiState(
    val isLoading: Boolean = true,
    val archivedOwners: List<OwnerSummary> = emptyList(),
    val archivedAccounts: List<ArchivedAccount> = emptyList(),
    val error: String? = null,
)
