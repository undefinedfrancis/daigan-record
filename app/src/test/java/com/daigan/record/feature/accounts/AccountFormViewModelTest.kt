package com.daigan.record.feature.accounts

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.AccountField
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.repository.ARCHIVED_OWNER_ACCOUNT_MESSAGE
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeGameTemplateRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.testing.InMemoryUiPreferenceStore
import com.daigan.record.ui.navigation.Routes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ownerRepository = FakeOwnerRepository()
    private val accountRepository = FakeGameAccountRepository()
    private val preferences = InMemoryUiPreferenceStore()

    private fun viewModel(accountId: String? = null, settings: com.daigan.record.data.local.dao.AppSettingsDao? = null): AccountFormViewModel {
        ownerRepository.ownersById["owner-1"] = owner(number = 1, nickname = "小满")
        return AccountFormViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Routes.OWNER_ID_ARG to "owner-1",
                    Routes.ACCOUNT_ID_ARG to accountId,
                ),
            ),
            ownerRepository = ownerRepository,
            accountRepository = accountRepository,
            templateRepository = FakeGameTemplateRepository(),
            serverOptionRepository = com.daigan.record.testing.FakeServerOptionRepository(),
            categoryRepository = com.daigan.record.testing.FakeAccountCategoryRepository(),
            uiPreferenceStore = preferences,
            appSettingsDao = settings,
        )
    }

    @Test
    fun `new account form loads the owner nickname`() = runTest {
        val viewModel = viewModel()

        assertEquals("小满", viewModel.uiState.value.ownerNickname)
        assertEquals(LoginMethod.VERIFICATION_CODE, viewModel.uiState.value.draft.loginMethod)
    }

    @Test
    fun `new and edit drafts remain isolated and require explicit restoration`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val newForm = viewModel()
        newForm.onDraftChange { it.copy(gameName = "新建草稿") }
        val editForm = viewModel("account-1")
        editForm.onDraftChange { it.copy(server = "编辑草稿") }
        val reopenedNew = viewModel()
        assertEquals("", reopenedNew.uiState.value.draft.gameName)
        assertTrue(reopenedNew.pendingDraft.value != null)
        reopenedNew.restoreDraft()
        assertEquals("新建草稿", reopenedNew.uiState.value.draft.gameName)
        val reopenedEdit = viewModel("account-1")
        assertEquals("重返未来：1999", reopenedEdit.uiState.value.draft.gameName)
        reopenedEdit.restoreDraft()
        assertEquals("编辑草稿", reopenedEdit.uiState.value.draft.server)
        reopenedEdit.discardDraft()
        assertEquals("新建草稿", viewModel().pendingDraft.value?.gameName)
        assertNull(viewModel("account-1").pendingDraft.value)
    }

    @Test
    fun `saving edit clears only its draft and preserves unfinished creation`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        viewModel().onDraftChange { it.copy(gameName = "保留新建") }
        val edit = viewModel("account-1")
        edit.onDraftChange { it.copy(server = "渠道服") }
        edit.save()
        assertTrue(edit.uiState.value.completed)
        assertNull(viewModel("account-1").pendingDraft.value)
        assertEquals("保留新建", viewModel().pendingDraft.value?.gameName)
    }

    @Test
    fun `failed edit preserves input and persisted draft`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val edit = viewModel("account-1")
        edit.onDraftChange { it.copy(server = "失败后保留") }
        accountRepository.accountsById.remove("account-1")
        edit.save()
        assertFalse(edit.uiState.value.completed)
        assertEquals("失败后保留", edit.uiState.value.draft.server)
        assertTrue(edit.uiState.value.saveError != null)
        accountRepository.accountsById["account-1"] = account()
        assertEquals("失败后保留", viewModel("account-1").pendingDraft.value?.server)
    }

    @Test
    fun `form refuses account from another owner`() = runTest {
        accountRepository.accountsById["account-1"] = account().copy(ownerId = "other-owner")
        val edit = viewModel("account-1")
        assertTrue(edit.uiState.value.saveError != null)
        edit.onDraftChange { it.copy(server = "不能误改") }
        edit.save()
        assertTrue(accountRepository.updatedDrafts.isEmpty())
    }

    @Test
    fun `dataset replacement isolates pending drafts and rejects old open form`() = runTest {
        val settings = DraftSettings()
        val form = viewModel(settings = settings)
        form.onDraftChange { it.copy(gameName = "旧资料草稿", server = "国服", uid = "123", loginAccount = "账号") }
        settings.identity.value = "replacement"
        form.save()
        assertFalse(form.uiState.value.completed)
        assertTrue(form.uiState.value.saveError != null)
        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertNull(viewModel(settings = settings).pendingDraft.value)
        settings.identity.value = "original"
        assertEquals("旧资料草稿", viewModel(settings = settings).pendingDraft.value?.gameName)
    }

    private class DraftSettings : com.daigan.record.data.local.dao.AppSettingsDao {
        val identity = kotlinx.coroutines.flow.MutableStateFlow<String?>("original")
        override suspend fun datasetInstanceId() = identity.value
        override fun observeDatasetInstanceId(): kotlinx.coroutines.flow.Flow<String?> = identity
        override suspend fun find(): com.daigan.record.data.local.entity.AppSettingsEntity? = null
        override suspend fun lastRestoreId(): String? = null
        override suspend fun upsert(entity: com.daigan.record.data.local.entity.AppSettingsEntity) { identity.value = entity.datasetInstanceId }
        override suspend fun setLastRestoreId(restoreId: String?) = Unit
        override suspend fun deleteAll() { identity.value = null }
    }

    @Test
    fun `editing loads the stored account`() = runTest {
        accountRepository.accountsById["account-1"] = account()

        val viewModel = viewModel(accountId = "account-1")

        assertTrue(viewModel.uiState.value.isEdit)
        assertEquals("重返未来：1999", viewModel.uiState.value.draft.gameName)
        assertEquals(LoginMethod.PASSWORD, viewModel.uiState.value.draft.loginMethod)
    }

    @Test
    fun `switching to verification code login clears the entered password`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val viewModel = viewModel(accountId = "account-1")
        assertEquals("demo1999", viewModel.uiState.value.draft.password)

        viewModel.selectLoginMethod(LoginMethod.VERIFICATION_CODE)

        assertEquals(LoginMethod.VERIFICATION_CODE, viewModel.uiState.value.draft.loginMethod)
        assertEquals("", viewModel.uiState.value.draft.password)
    }

    @Test
    fun `save refuses blank required fields`() = runTest {
        val viewModel = viewModel()

        viewModel.save()

        val errors = viewModel.uiState.value.errors
        assertTrue(errors.containsKey(AccountField.GAME_NAME))
        assertTrue(errors.containsKey(AccountField.UID))
        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.completed)
    }

    @Test
    fun `save submits a verification code account without a password`() = runTest {
        val viewModel = viewModel()
        viewModel.onDraftChange {
            it.copy(
                gameName = "鸣潮",
                server = "国服",
                uid = "10002136",
                loginAccount = "demo-acheng",
            )
        }

        viewModel.save()

        assertEquals(1, accountRepository.createdDrafts.size)
        val submitted = accountRepository.createdDrafts.single()
        assertEquals(LoginMethod.VERIFICATION_CODE, submitted.loginMethod)
        assertEquals("", submitted.password)
        assertTrue(viewModel.uiState.value.completed)
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun `save submits a password account with the password`() = runTest {
        val viewModel = viewModel()
        viewModel.onDraftChange {
            it.copy(
                gameName = "鸣潮",
                server = "国服",
                uid = "10002136",
                loginAccount = "demo-acheng",
                password = "secret",
            )
        }
        viewModel.selectLoginMethod(LoginMethod.PASSWORD)

        viewModel.save()

        assertEquals("secret", accountRepository.createdDrafts.single().password)
        assertTrue(viewModel.uiState.value.completed)
    }

    @Test
    fun `editing an account submits an update instead of a create`() = runTest {
        accountRepository.accountsById["account-1"] = account()
        val viewModel = viewModel(accountId = "account-1")
        viewModel.onDraftChange { it.copy(server = "渠道服") }

        viewModel.save()

        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertEquals("渠道服", accountRepository.updatedDrafts.single().server)
    }

    /**
     * A form that was opened before the owner was archived must not submit anything:
     * the stale form is refused before the repository is even called.
     */
    @Test
    fun `an archived owner blocks a stale account form`() = runTest {
        ownerRepository.ownersById["owner-1"] = owner(number = 1, nickname = "小满", archived = true)
        val viewModel = AccountFormViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    Routes.OWNER_ID_ARG to "owner-1",
                    Routes.ACCOUNT_ID_ARG to null,
                ),
            ),
            ownerRepository = ownerRepository,
            accountRepository = accountRepository,
            templateRepository = FakeGameTemplateRepository(),
            serverOptionRepository = com.daigan.record.testing.FakeServerOptionRepository(),
            categoryRepository = com.daigan.record.testing.FakeAccountCategoryRepository(),
            uiPreferenceStore = preferences,
        )
        viewModel.onDraftChange {
            it.copy(gameName = "鸣潮", server = "国服", uid = "10002136", loginAccount = "demo")
        }

        assertTrue(viewModel.uiState.value.ownerArchived)
        viewModel.save()

        assertTrue(accountRepository.createdDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.completed)
        assertEquals(ARCHIVED_OWNER_ACCOUNT_MESSAGE, viewModel.uiState.value.saveError)
    }
}
