package com.daigan.record.testing

import com.daigan.record.data.model.AccountCategory
import com.daigan.record.data.model.AccountCategoryRules
import com.daigan.record.data.repository.AccountCategoryRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [AccountCategoryRepository] for JVM ViewModel tests.
 *
 * It enforces the same user-visible rules as the Room implementation: blank names and
 * trimmed duplicates are rejected, renaming excludes the row itself, and deleting a
 * category only unclassifies its accounts.
 */
class FakeAccountCategoryRepository : AccountCategoryRepository {

    val categoriesById = linkedMapOf<String, AccountCategory>()
    private val state = MutableStateFlow<List<AccountCategory>>(emptyList())
    private var nextId = 1
    private var clock = Instant.parse("2026-10-22T00:00:00Z")

    override fun observeCategories(): Flow<List<AccountCategory>> = state.map { it }

    override suspend fun findCategories(): List<AccountCategory> = state.value

    override suspend fun createCategory(name: String): AccountCategory {
        AccountCategoryRules.validateName(name, state.value.map { it.name })
            ?.let { throw IllegalArgumentException(it) }
        val category = AccountCategory(
            id = "category-${nextId++}",
            name = checkNotNull(AccountCategoryRules.normalizeName(name)),
            sortOrder = (state.value.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            createdAt = clock,
            updatedAt = clock,
        )
        categoriesById[category.id] = category
        publish()
        return category
    }

    override suspend fun renameCategory(categoryId: String, name: String) {
        val existing = categoriesById[categoryId] ?: throw IllegalArgumentException("分类不存在")
        val others = state.value.filter { it.id != categoryId }.map { it.name }
        AccountCategoryRules.validateName(name, others)?.let { throw IllegalArgumentException(it) }
        categoriesById[categoryId] = existing.copy(
            name = checkNotNull(AccountCategoryRules.normalizeName(name)),
            updatedAt = clock,
        )
        publish()
    }

    override suspend fun deleteCategory(categoryId: String): Int {
        categoriesById.remove(categoryId) ?: return 0
        publish()
        return 0
    }

    override suspend fun assignAccountCategory(accountId: String, categoryId: String?) = Unit

    override suspend fun reorderCategories(orderedIds: List<String>) = Unit

    private fun publish() {
        state.value = categoriesById.values.sortedBy { it.sortOrder }
    }
}
