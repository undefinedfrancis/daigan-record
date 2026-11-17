package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.daigan.record.data.local.entity.AccountCategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账号分类（UF-037，schema 15）。
 *
 * 名称唯一由 `index_account_categories_name` 保证；查重语义是 trim 后的精确比较，
 * 由仓库层在写入前完成，数据库只做最后一道约束。
 */
@Dao
interface AccountCategoryDao {

    @Query("SELECT * FROM account_categories ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<AccountCategoryEntity>>

    @Query("SELECT * FROM account_categories ORDER BY sortOrder ASC, name ASC")
    suspend fun findAll(): List<AccountCategoryEntity>

    @Query("SELECT * FROM account_categories WHERE id = :id")
    suspend fun findById(id: String): AccountCategoryEntity?

    @Query("SELECT * FROM account_categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): AccountCategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: AccountCategoryEntity)

    @Update
    suspend fun update(category: AccountCategoryEntity)

    /** 删除分类前必须在同一事务里把成员账号置为未分类（见仓库层）。 */
    @Query("DELETE FROM account_categories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM account_categories")
    suspend fun count(): Int

    @Query("UPDATE game_accounts SET categoryId = NULL, updatedAt = :updatedAt WHERE categoryId = :categoryId")
    suspend fun clearAccounts(categoryId: String, updatedAt: Long)

    @Query("UPDATE game_accounts SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id = :accountId")
    suspend fun assignAccount(accountId: String, categoryId: String?, updatedAt: Long)
}
