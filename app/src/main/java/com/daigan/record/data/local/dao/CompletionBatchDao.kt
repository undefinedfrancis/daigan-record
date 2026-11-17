package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import java.time.Instant

@Dao
interface CompletionBatchDao {

    @Insert
    suspend fun insertBatch(batch: CompletionBatchEntity)

    @Insert
    suspend fun insertItems(items: List<CompletionBatchItemEntity>)

    /** Cascade deletes for order/account deletion, in dependency order. */
    @Query(
        "DELETE FROM completion_batch_items WHERE taskDefinitionId IN " +
            "(SELECT id FROM task_definitions WHERE orderId = :orderId) " +
            "OR occurrenceId IN (SELECT occ.id FROM task_occurrences occ " +
            "INNER JOIN task_definitions t ON t.id = occ.taskDefinitionId WHERE t.orderId = :orderId)",
    )
    suspend fun deleteItemsForOrder(orderId: String)

    @Query("DELETE FROM completion_batches WHERE targetId = :orderId")
    suspend fun deleteBatchesForOrder(orderId: String)

    /** HOSTING_ALL batches spanning several orders keep surviving items; empty ones go. */
    @Query(
        "DELETE FROM completion_batches WHERE scope = 'HOSTING_ALL' AND " +
            "id NOT IN (SELECT batchId FROM completion_batch_items)",
    )
    suspend fun deleteEmptyHostingAllBatches()

    @Query("SELECT * FROM completion_batches WHERE id = :batchId")
    suspend fun findById(batchId: String): CompletionBatchEntity?

    @Query("SELECT * FROM completion_batches WHERE operationId = :operationId")
    suspend fun findByOperationId(operationId: String): CompletionBatchEntity?

    @Query("SELECT * FROM completion_batch_items WHERE batchId = :batchId")
    suspend fun findItems(batchId: String): List<CompletionBatchItemEntity>

    @Query("UPDATE completion_batches SET undoneAt = :undoneAt WHERE id = :batchId")
    suspend fun markUndone(batchId: String, undoneAt: Instant)

    @Query(
        "SELECT * FROM completion_batches WHERE undoneAt IS NULL ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun latestUndoable(): CompletionBatchEntity?

    @Query("SELECT COUNT(*) FROM completion_batches")
    suspend fun count(): Int

    /** Typed full read used by backup validation (runs every converter). */
    @Query("SELECT * FROM completion_batches")
    suspend fun findAllBatches(): List<CompletionBatchEntity>

    /** Typed full read used by backup validation (runs every converter). */
    @Query("SELECT * FROM completion_batch_items")
    suspend fun findAllItems(): List<CompletionBatchItemEntity>
}
