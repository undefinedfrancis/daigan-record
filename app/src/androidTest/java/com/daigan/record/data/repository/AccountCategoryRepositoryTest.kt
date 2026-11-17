package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.testing.OrderTestEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-037 against a real Room database: unique index, transaction ordering and the
 * "delete a category never deletes an account" rule are enforced by SQLite, not by the
 * fake used in the ViewModel tests.
 */
@RunWith(AndroidJUnit4::class)
class AccountCategoryRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var categories: DefaultAccountCategoryRepository

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
        categories = DefaultAccountCategoryRepository(
            database = env.database,
            categoryDao = env.database.accountCategoryDao(),
            accountDao = env.database.gameAccountDao(),
            timeProvider = env.clock,
        )
    }

    @After
    fun tearDown() {
        env.database.close()
    }

    private fun draft(gameName: String) = AccountDraft(
        gameName = gameName,
        server = "国服",
        uid = "uid-$gameName",
        loginAccount = "login-$gameName",
    )

    @Test
    fun createTrimRenameAndRejectDuplicate() = runBlocking {
        val created = categories.createCategory("  常客  ")
        assertEquals("常客", created.name)
        assertEquals(1, categories.findCategories().size)

        // Trimmed exact duplicate is rejected before SQLite raises a constraint error.
        val duplicate = runCatching { categories.createCategory("常客") }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)
        assertEquals("已存在同名分类", duplicate?.message)
        assertEquals(1, categories.findCategories().size)

        categories.renameCategory(created.id, "老朋友")
        assertEquals("老朋友", categories.findCategories().single().name)
    }

    @Test
    fun blankNameIsRejected() = runBlocking {
        val error = runCatching { categories.createCategory("   ") }.exceptionOrNull()
        assertEquals("请填写分类名称", error?.message)
        assertTrue(categories.findCategories().isEmpty())
    }

    @Test
    fun accountKeepsCategoryAndSurvivesCategoryDeletion() = runBlocking {
        val owner = env.ownerRepository.createOwner("小满")
        val category = categories.createCategory("常客")
        val assigned = env.accountRepository.createAccount(
            owner.id,
            draft("鸣潮").copy(categoryId = category.id),
        )
        assertEquals(category.id, assigned.categoryId)

        val unclassified = categories.deleteCategory(category.id)

        assertEquals("一个账号回到未分类", 1, unclassified)
        assertTrue(categories.findCategories().isEmpty())
        val reloaded = env.accountRepository.findAccount(assigned.id)
        assertNotNull("账号本身必须保留", reloaded)
        assertNull("分类删除后账号变为未分类", reloaded?.categoryId)
    }

    @Test
    fun unknownCategoryIsRejectedOnAssign() = runBlocking {
        val owner = env.ownerRepository.createOwner("阿橙")
        val account = env.accountRepository.createAccount(owner.id, draft("原神"))
        val error = runCatching { categories.assignAccountCategory(account.id, "missing") }
            .exceptionOrNull()
        assertEquals("分类不存在", error?.message)
        assertNull(env.accountRepository.findAccount(account.id)?.categoryId)
    }

    @Test
    fun savingAnAccountWithUnknownCategoryIsRejected() = runBlocking {
        val owner = env.ownerRepository.createOwner("小满")
        val error = runCatching {
            env.accountRepository.createAccount(owner.id, draft("鸣潮").copy(categoryId = "missing"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("分类不存在", error?.message)
    }
}
