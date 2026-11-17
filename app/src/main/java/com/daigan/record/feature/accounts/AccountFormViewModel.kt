package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.preferences.UiPreferenceStore
import com.daigan.record.data.repository.ARCHIVED_OWNER_ACCOUNT_MESSAGE
import com.daigan.record.data.repository.AccountCategoryRepository
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.data.repository.ServerOptionRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.daigan.record.data.repository.DatasetGate

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    private val templateRepository: GameTemplateRepository,
    private val serverOptionRepository: ServerOptionRepository,
    private val categoryRepository: AccountCategoryRepository,
    private val uiPreferenceStore: UiPreferenceStore,
    private val appSettingsDao: com.daigan.record.data.local.dao.AppSettingsDao? = null,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }
    private val accountId: String? = savedStateHandle
        .get<String>(Routes.ACCOUNT_ID_ARG)
        ?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        AccountFormUiState(
            isEdit = accountId != null,
            isLoading = true,
        ),
    )
    val uiState: StateFlow<AccountFormUiState> = _uiState.asStateFlow()

    private val _pendingDraft = MutableStateFlow<AccountDraft?>(null)
    val pendingDraft: StateFlow<AccountDraft?> = _pendingDraft.asStateFlow()
    private var draftKey: String? = null
    private var initialDraft = AccountDraft()
    private var datasetIdentity: String? = null
    private var validTarget = false
    private val draftWrites = Mutex()

    private suspend fun isCurrentDataset(): Boolean = appSettingsDao == null ||
        appSettingsDao.datasetInstanceId() == datasetIdentity

    private fun persistDraft(draft: AccountDraft) {
        val key = draftKey ?: return
        viewModelScope.launch {
            draftWrites.withLock {
                if (isCurrentDataset()) uiPreferenceStore.setAccountDraft(key, draft)
            }
        }
    }

    init {
        viewModelScope.launch {
            // The library is a creation default only; an account never requires one.
            runCatching { templateRepository.ensureBuiltInTemplates() }
            val templates = runCatching { templateRepository.findAllSummaries() }
                .getOrDefault(emptyList())
            val categories = runCatching { categoryRepository.findCategories() }
                .getOrDefault(emptyList())
            val owner = ownerRepository.findOwner(ownerId)
            val account = accountId?.let { accountRepository.findAccount(it) }
            validTarget = owner != null && (accountId == null || account?.ownerId == ownerId)
            datasetIdentity = appSettingsDao?.datasetInstanceId()
            initialDraft = account?.takeIf { it.ownerId == ownerId }?.let(AccountDraft::from) ?: AccountDraft()
            if (validTarget && (appSettingsDao == null || datasetIdentity != null)) {
                // JSON array avoids ambiguous delimiters; dataset and creation identities prevent
                // restoring onto imported/recreated objects that happen to share their IDs.
                draftKey = kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(datasetIdentity ?: "test-dataset"))
                    add(kotlinx.serialization.json.JsonPrimitive(ownerId))
                    add(kotlinx.serialization.json.JsonPrimitive(owner?.createdAt.toString()))
                    add(kotlinx.serialization.json.JsonPrimitive(accountId ?: "new"))
                    add(kotlinx.serialization.json.JsonPrimitive(account?.createdAt.toString()))
                }.toString()
                _pendingDraft.value = uiPreferenceStore.accountDraft(checkNotNull(draftKey))
            }
            _uiState.update { state ->
                val draft = initialDraft
                val selected = templates.firstOrNull { it.id == draft.defaultTemplateId }
                val category = categories.firstOrNull { it.id == draft.categoryId }
                state.copy(
                    isLoading = false,
                    ownerNickname = owner?.nickname,
                    ownerArchived = owner?.archived == true,
                    saveError = if (!validTarget) "老板或账号已不存在，请返回后重新打开。" else null,
                    draft = draft.copy(
                        defaultTemplateId = if (selected == null) null else draft.defaultTemplateId,
                        // 分类已不存在（例如在别处被删除）→ 显示为未分类，不报错。
                        categoryId = if (category == null) null else draft.categoryId,
                    ),
                    templates = templates,
                    templateName = selected?.label,
                    categories = categories,
                    categoryName = category?.name,
                )
            }
        }
        viewModelScope.launch {
            // 分类会在其他页面被重命名或删除；跟随真实列表刷新，避免显示过期名称。
            categoryRepository.observeCategories().collect { categories ->
                _uiState.update { state ->
                    val name = categories.firstOrNull { it.id == state.draft.categoryId }?.name
                    state.copy(categories = categories, categoryName = name)
                }
            }
        }
        viewModelScope.launch {
            // 区服 options follow the typed game name; an empty name shows the free-text
            // field only. Renaming or removing options never touches stored accounts.
            _uiState
                .map { it.draft.gameName.trim() }
                .distinctUntilChanged()
                .flatMapLatest { name ->
                    if (name.isBlank()) {
                        flowOf(emptyList())
                    } else {
                        serverOptionRepository.observeForGame(name)
                    }
                }
                .collect { options -> _uiState.update { it.copy(serverOptions = options) } }
        }
    }

    /**
     * Choosing a template stores it as this account's creation default and fills an empty
     * game name. Existing typed text is never overwritten; the user can clear the choice
     * at any time and existing orders are unaffected.
     */
    fun selectTemplate(templateId: String?) {
        if (!validTarget || _uiState.value.isLoading || _uiState.value.isSaving) return
        _uiState.update { state ->
            val template = templateId?.let { id -> state.templates.firstOrNull { it.id == id } }
            val draft = state.draft.copy(
                    defaultTemplateId = template?.id,
                    gameName = if (template != null && state.draft.gameName.isBlank()) {
                        template.gameName
                    } else {
                        state.draft.gameName
                    },
                )
            _pendingDraft.value = null
            persistDraft(draft)
            state.copy(
                draft = draft,
                templateName = template?.label,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    /**
     * 选择或清除分类（UF-037）。分类只是本账号的标签：改分类不触碰老板、订单、任务、
     * 收款或历史。传入已不存在的分类时保持原状，绝不静默改指到别的分类。
     */
    fun selectCategory(categoryId: String?) {
        if (!validTarget || _uiState.value.isLoading || _uiState.value.isSaving) return
        _uiState.update { state ->
            if (categoryId != null && state.categories.none { it.id == categoryId }) return@update state
            val draft = state.draft.copy(categoryId = categoryId)
            _pendingDraft.value = null
            persistDraft(draft)
            state.copy(
                draft = draft,
                categoryName = state.categories.firstOrNull { it.id == categoryId }?.name,
                categoryError = null,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    /** 在账号表单内新建分类并立即选中；空名与 trim 后重名由仓库拒绝。 */
    fun createCategory(name: String) {
        val state = _uiState.value
        if (!validTarget || state.isLoading || state.isSaving || state.categoryBusy) return
        _uiState.update { it.copy(categoryBusy = true, categoryError = null) }
        viewModelScope.launch {
            val result = runCatching {
                DatasetGate.withWrite { categoryRepository.createCategory(name) }
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { category ->
                        val draft = current.draft.copy(categoryId = category.id)
                        _pendingDraft.value = null
                        persistDraft(draft)
                        current.copy(
                            draft = draft,
                            categoryName = category.name,
                            categoryBusy = false,
                            categoryError = null,
                        )
                    },
                    onFailure = {
                        current.copy(
                            categoryBusy = false,
                            categoryError = it.message ?: "创建分类失败，请重试。",
                        )
                    },
                )
            }
        }
    }

    fun onDraftChange(transform: (AccountDraft) -> AccountDraft) {
        if (!validTarget || _uiState.value.isLoading || _uiState.value.isSaving) return
        _uiState.update { state ->
            val draft = transform(state.draft)
            _pendingDraft.value = null
            persistDraft(draft)
            state.copy(
                draft = draft,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    /**
     * Switching to a non-password login also drops the in-memory password so the
     * submitted draft can never carry a stale credential.
     */
    fun selectLoginMethod(method: LoginMethod) {
        if (!validTarget || _uiState.value.isLoading || _uiState.value.isSaving) return
        _uiState.update { state ->
            val draft = state.draft.copy(loginMethod = method)
            val persisted = if (method == LoginMethod.PASSWORD) draft else draft.copy(password = "")
            _pendingDraft.value = null
            persistDraft(persisted)
            state.copy(
                draft = persisted,
                errors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isLoading || !validTarget) return
        // The owner may have been archived while this form was open. The repository
        // rejects the write inside its transaction as well; this is the fast path so a
        // stale form cannot even try.
        if (state.ownerArchived) {
            _uiState.update { it.copy(saveError = ARCHIVED_OWNER_ACCOUNT_MESSAGE) }
            return
        }
        val errors = state.draft.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val result = runCatching {
                DatasetGate.withWrite {
                    check(isCurrentDataset()) { "资料已被恢复替换，请返回后重新打开。" }
                    if (accountId != null) {
                        check(accountRepository.findAccount(accountId)?.ownerId == ownerId) {
                            "账号已不存在或所属老板已变化，请返回后重新打开。"
                        }
                    }
                    if (accountId == null) {
                        accountRepository.createAccount(ownerId, state.draft)
                    } else {
                        accountRepository.updateAccount(accountId, state.draft)
                    }
                }
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = {
                        draftWrites.withLock { draftKey?.let { uiPreferenceStore.clearAccountDraft(it) } }
                        _pendingDraft.value = null
                        current.copy(isSaving = false, completed = true)
                    },
                    onFailure = {
                        current.copy(
                            isSaving = false,
                            saveError = it.message ?: "保存失败，请重试。",
                        )
                    },
                )
            }
        }
    }

    fun restoreDraft() {
        val pending = _pendingDraft.value ?: return
        viewModelScope.launch {
            if (isCurrentDataset() && validTarget) {
                _uiState.update { it.copy(draft = pending, templateName = it.templates.firstOrNull { template -> template.id == pending.defaultTemplateId }?.label, errors = emptyMap(), saveError = null) }
                _pendingDraft.value = null
            }
        }
    }

    fun discardDraft() {
        val key = draftKey ?: return
        viewModelScope.launch { draftWrites.withLock { uiPreferenceStore.clearAccountDraft(key) } }
        _pendingDraft.value = null
        _uiState.update { it.copy(draft = initialDraft, errors = emptyMap(), saveError = null) }
    }
}
