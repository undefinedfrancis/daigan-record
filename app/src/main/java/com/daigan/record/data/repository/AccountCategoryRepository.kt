package com.daigan.record.data.repository

import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.AccountCategoryDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.entity.AccountCategoryEntity
import com.daigan.record.data.model.AccountCategory
import com.daigan.record.data.model.AccountCategoryRules
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Account-category persistence boundary (UF-037).
 *
 * Categories are labels, never owners of business rows:
 *
 * - Deleting a category sets its accounts back to unclassified **in the same
 *   transaction** and then deletes only the category row. Accounts, orders, tasks,
 *   receipts and history are untouched, and the deletion returns how many accounts were
 *   unclassified so the UI can say so.
 * - Names are unique after trimming, compared exactly (no case folding, no locale
 *   rules). The unique index in SQLite is the last line of defence; the message the user
 *   sees comes from [AccountCategoryRules].
 * - Legacy accounts and accounts whose category row disappeared stay readable: a
 *   dangling `categoryId` is treated as "no category" by [findCategories] consumers,
 *   never as an error.
 */
interface AccountCategoryRepository {

    fun observeCategories(): Flow<List<AccountCategory>>

    suspend fun findCategories(): List<AccountCategory>

    /** Creates a category; blank or duplicate names are rejected. */
    suspend fun createCategory(name: String): AccountCategory

    suspend fun renameCategory(categoryId: String, name: String)

    /**
     * Deletes the category and moves its accounts to unclassified in one transaction.
     * Returns how many accounts lost their category (0 when the row was already gone).
     */
    suspend fun deleteCategory(categoryId: String): Int

    /** Assigns or clears an account's category; unknown categories are rejected. */
    suspend fun assignAccountCategory(accountId: String, categoryId: String?)

    /** Persists a new display order given by the UI. Unknown ids are ignored. */
    suspend fun reorderCategories(orderedIds: List<String>)
}

@Singleton
class DefaultAccountCategoryRepository @Inject constructor(
    private val database: RecordDatabase,
    private val categoryDao: AccountCategoryDao,
    private val accountDao: GameAccountDao,
    private val timeProvider: TimeProvider,
) : AccountCategoryRepository {

    override fun observeCategories(): Flow<List<AccountCategory>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun findCategories(): List<AccountCategory> =
        categoryDao.findAll().map { it.toModel() }

    override suspend fun createCategory(name: String): AccountCategory {
        val now = timeProvider.now()
        return database.withDatasetTransaction {
            val existing = categoryDao.findAll()
            AccountCategoryRules.validateName(name, existing.map { it.name })
                ?.let { throw IllegalArgumentException(it) }
            val entity = AccountCategoryEntity(
                id = UUID.randomUUID().toString(),
                name = checkNotNull(AccountCategoryRules.normalizeName(name)),
                sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                createdAt = now,
                updatedAt = now,
            )
            categoryDao.insert(entity)
            entity.toModel()
        }
    }

    override suspend fun renameCategory(categoryId: String, name: String) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val entity = categoryDao.findById(categoryId)
                ?: throw IllegalArgumentException("分类不存在")
            val others = categoryDao.findAll().filter { it.id != categoryId }.map { it.name }
            AccountCategoryRules.validateName(name, others)?.let { throw IllegalArgumentException(it) }
            categoryDao.update(
                entity.copy(
                    name = checkNotNull(AccountCategoryRules.normalizeName(name)),
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun deleteCategory(categoryId: String): Int {
        val now = timeProvider.now()
        return database.withDatasetTransaction {
            val entity = categoryDao.findById(categoryId) ?: return@withDatasetTransaction 0
            val affected = accountDao.countForCategory(categoryId)
            // Order matters: unclassify first so no row is ever left pointing at a
            // deleted category, even if a reader observes mid-transaction snapshots.
            categoryDao.clearAccounts(categoryId, now.toEpochMilli())
            categoryDao.delete(entity.id)
            affected
        }
    }

    override suspend fun assignAccountCategory(accountId: String, categoryId: String?) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            if (categoryId != null && categoryDao.findById(categoryId) == null) {
                throw IllegalArgumentException("分类不存在")
            }
            categoryDao.assignAccount(accountId, categoryId, now.toEpochMilli())
        }
    }

    override suspend fun reorderCategories(orderedIds: List<String>) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val byId = categoryDao.findAll().associateBy { it.id }
            orderedIds.distinct().forEachIndexed { index, id ->
                val entity = byId[id] ?: return@forEachIndexed
                val sortOrder = index + 1
                if (entity.sortOrder != sortOrder) {
                    categoryDao.update(entity.copy(sortOrder = sortOrder, updatedAt = now))
                }
            }
        }
    }
}

private fun AccountCategoryEntity.toModel(): AccountCategory = AccountCategory(
    id = id,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
