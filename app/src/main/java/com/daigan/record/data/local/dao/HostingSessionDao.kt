package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daigan.record.data.local.entity.HostingSessionEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Check-in rounds of count-based hosting orders (UF-033).
 *
 * The unique `(orderId, sessionIndex)` index plus the conditional completion updates in
 * this DAO are the idempotency guard: a repeated tap, a retried batch or a losing
 * concurrent transaction can never complete the same round twice.
 */
@Dao
interface HostingSessionDao {

    @Query("SELECT * FROM hosting_sessions WHERE orderId = :orderId ORDER BY sessionIndex ASC")
    fun observeForOrder(orderId: String): Flow<List<HostingSessionEntity>>

    @Query(
        """
        SELECT s.* FROM hosting_sessions s
        INNER JOIN orders o ON o.id = s.orderId
        INNER JOIN game_accounts a ON a.id = o.accountId
        INNER JOIN owners w ON w.id = a.ownerId
        WHERE o.cancelledAt IS NULL AND o.archivedAt IS NULL AND o.type = 'HOSTING'
          AND a.archivedAt IS NULL AND w.archivedAt IS NULL
        ORDER BY s.orderId ASC, s.sessionIndex ASC
        """
    )
    fun observeForActiveHosting(): Flow<List<HostingSessionEntity>>

    @Query("SELECT * FROM hosting_sessions WHERE orderId = :orderId ORDER BY sessionIndex ASC")
    suspend fun findForOrder(orderId: String): List<HostingSessionEntity>

    @Query("SELECT * FROM hosting_sessions WHERE orderId = :orderId AND sessionIndex = :sessionIndex")
    suspend fun findByIndex(orderId: String, sessionIndex: Int): HostingSessionEntity?

    @Query("SELECT COUNT(*) FROM hosting_sessions WHERE orderId = :orderId")
    suspend fun countForOrder(orderId: String): Int

    @Query("SELECT * FROM hosting_sessions")
    suspend fun findAll(): List<HostingSessionEntity>

    /** Every round row, for owner summaries that must not join per order in SQL. */
    @Query("SELECT * FROM hosting_sessions ORDER BY orderId ASC, sessionIndex ASC")
    fun observeAll(): Flow<List<HostingSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(session: HostingSessionEntity): Long

    /**
     * Marks one round complete only when it is still open. A second attempt (parallel
     * tap, retried command) changes nothing, so the total cannot move twice.
     */
    @Query(
        "UPDATE hosting_sessions SET completedAt = :completedAt, completionBatchId = :batchId " +
            "WHERE orderId = :orderId AND sessionIndex = :sessionIndex AND completedAt IS NULL"
    )
    suspend fun markCompleted(
        orderId: String,
        sessionIndex: Int,
        completedAt: Instant,
        batchId: String?,
    ): Int

    /**
     * Derived-timestamp correction (UF-038): an incremental recompute that yields a
     * different evidence time rewrites only `completedAt`, keeping the batch marker.
     */
    @Query(
        "UPDATE hosting_sessions SET completedAt = :completedAt " +
            "WHERE orderId = :orderId AND sessionIndex = :sessionIndex"
    )
    suspend fun updateSessionCompletedAt(
        orderId: String,
        sessionIndex: Int,
        completedAt: Instant,
    ): Int

    /** Reopens a round after an undo/correction; the row and its history are kept. */
    @Query(
        "UPDATE hosting_sessions SET completedAt = NULL, completionBatchId = NULL " +
            "WHERE orderId = :orderId AND sessionIndex = :sessionIndex AND completedAt IS NOT NULL"
    )
    suspend fun reopen(orderId: String, sessionIndex: Int): Int

    @Query("DELETE FROM hosting_sessions WHERE orderId = :orderId")
    suspend fun deleteForOrder(orderId: String)
}
