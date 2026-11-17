package com.daigan.record.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.OwnerDetail
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.model.OwnerValidation
import com.daigan.record.data.model.ReceiptStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Owner ("老板") persistence boundary.
 *
 * ViewModels depend on this interface only; they never touch DAOs or SQLite.
 */
interface OwnerRepository {

    fun observeActiveOwners(): Flow<List<OwnerSummary>>

    fun observeArchivedOwners(): Flow<List<OwnerSummary>>

    fun observeOwner(ownerId: String): Flow<OwnerDetail?>

    suspend fun findOwner(ownerId: String): Owner?

    /** Creates an owner with a number that is allocated inside the insert transaction. */
    suspend fun createOwner(nickname: String): Owner

    /** Changes the nickname only; the owner number is immutable. */
    suspend fun renameOwner(ownerId: String, nickname: String)

    /**
     * Archives the owner and its currently active accounts in one transaction, recording
     * an explicit archive batch id. Repeated calls are no-ops and never re-stamp the batch.
     */
    suspend fun archiveOwner(ownerId: String)

    /**
     * Restores the owner and exactly the accounts stamped with its archive batch id.
     * Accounts archived independently stay archived. Repeated calls are no-ops.
     */
    suspend fun restoreOwner(ownerId: String)

    /**
     * What a permanent deletion of this **archived** owner would remove (UF-031), read
     * before anything is deleted: accounts, orders, rounds/check-ins, receipts with their
     * collected sum (which leaves the statistics) and the outstanding debt.
     */
    suspend fun ownerDeletionImpact(ownerId: String): OwnerDeletionImpact {
        error("不支持删除老板")
    }

    /**
     * Permanently deletes an archived owner and every dependent row in one transaction.
     * Other owners are never touched. Attachment *files* are cleaned up by the caller
     * after the commit and retried by the start-up orphan pass if that pass fails.
     */
    suspend fun deleteArchivedOwner(ownerId: String): OwnerDeletionSummary {
        error("不支持删除老板")
    }
}

/**
 * Read-only preview of an archived owner's permanent deletion. Counts and money are
 * computed from the same rows the transaction later deletes, so the confirmation text
 * and the result cannot disagree.
 */
data class OwnerDeletionImpact(
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountCount: Int,
    /** Every order of every account, including cancelled and completed ones. */
    val orderCount: Int,
    val activeOrderCount: Int,
    val periodCount: Int,
    val occurrenceCount: Int,
    /** Count-based hosting rounds, which are not check-ins but their own identity. */
    val sessionCount: Int,
    /** Effective receipts only: voided/replaced rows never counted as income. */
    val receiptCount: Int,
    val receiptSumCents: Long,
    val outstandingCents: Long,
    val attachmentCount: Int,
) {
    val isEmpty: Boolean
        get() = accountCount == 0 && orderCount == 0 && attachmentCount == 0
}

data class OwnerDeletionSummary(
    val ownerId: String,
    val ownerNickname: String,
    val deletedAccountCount: Int,
    val deletedOrderCount: Int,
    val deletedDependentRowCount: Int,
    /** True when the committed deletion left files for the start-up orphan pass. */
    val attachmentCleanupDeferred: Boolean = false,
)

@Singleton
class DefaultOwnerRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ownerDao: OwnerDao,
    private val accountDao: GameAccountDao,
    private val orderDao: OrderDao,
    private val timeProvider: TimeProvider,
) : OwnerRepository {

    override fun observeActiveOwners(): Flow<List<OwnerSummary>> =
        combine(ownerDao.observeActive(), accountDao.observeActive()) { owners, accounts ->
            val accountsByOwner = accounts.groupBy { it.ownerId }
            owners.map { owner ->
                OwnerSummary(
                    owner = owner.toModel(),
                    accounts = accountsByOwner[owner.id].orEmpty().map { it.toModel() },
                )
            }
        }

    override fun observeArchivedOwners(): Flow<List<OwnerSummary>> =
        combine(ownerDao.observeArchived(), accountDao.observeArchived()) { owners, accounts ->
            val accountsByOwner = accounts.groupBy { it.ownerId }
            owners.map { owner ->
                OwnerSummary(
                    owner = owner.toModel(),
                    accounts = accountsByOwner[owner.id].orEmpty().map { it.toModel() },
                )
            }
        }

    override fun observeOwner(ownerId: String): Flow<OwnerDetail?> =
        combine(ownerDao.observeById(ownerId), accountDao.observeForOwner(ownerId)) { owner, accounts ->
            owner?.let {
                val models = accounts.map { account -> account.toModel() }
                OwnerDetail(
                    owner = it.toModel(),
                    activeAccounts = models.filter { account -> !account.archived },
                    archivedAccounts = models.filter { account -> account.archived },
                )
            }
        }

    override suspend fun findOwner(ownerId: String): Owner? =
        ownerDao.findById(ownerId)?.toModel()

    override suspend fun createOwner(nickname: String): Owner {
        val normalized = nickname.trim()
        OwnerValidation.nicknameError(normalized)?.let { throw IllegalArgumentException(it) }

        var attempt = 0
        while (true) {
            try {
                return database.withDatasetTransaction {
                    ownerDao.ensureNumberSequence()
                    // The UPDATE takes the write lock, so two transactions can never
                    // observe the same "next" value.
                    ownerDao.incrementNumberSequence()
                    val number = ownerDao.lastAllocatedNumber()
                    val now = timeProvider.now()
                    val entity = OwnerEntity(
                        id = UUID.randomUUID().toString(),
                        number = number,
                        nickname = normalized,
                        createdAt = now,
                        updatedAt = now,
                        archivedAt = null,
                        archiveBatchId = null,
                    )
                    ownerDao.insert(entity)
                    entity.toModel()
                }
            } catch (constraint: SQLiteConstraintException) {
                // The unique index on owners.number is the last line of defence.
                attempt += 1
                if (attempt >= MAX_ALLOCATION_ATTEMPTS) throw constraint
            }
        }
    }

    override suspend fun renameOwner(ownerId: String, nickname: String) {
        val normalized = nickname.trim()
        OwnerValidation.nicknameError(normalized)?.let { throw IllegalArgumentException(it) }
        val now = timeProvider.now()
        database.withDatasetTransaction {
            ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            ownerDao.updateNickname(ownerId = ownerId, nickname = normalized, updatedAt = now)
        }
    }

    override suspend fun archiveOwner(ownerId: String) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            // Idempotent: a second archive must not create a new batch or re-stamp rows,
            // otherwise a later restore would lose the accounts of the original batch.
            if (owner.archivedAt != null) return@withDatasetTransaction

            // Do not hide active work behind an archive. Finished short orders and
            // expired periods may be archived; unfinished work must be dealt with first.
            require(orderDao.countUnfinishedShortOrdersForOwner(ownerId) == 0) {
                ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE
            }
            val ownerOrders = accountDao.findForOwner(ownerId)
                .flatMap { orderDao.findForAccount(it.id) }
            val countBasedOrderIds = ownerOrders
                .filter { it.hostingMode == HostingMode.COUNT_BASED }
                .map { it.id }
                .toSet()
            assertNoUnexpiredPeriod(
                periods = orderDao.findHostingPeriodsForOwner(ownerId),
                now = now,
                message = ARCHIVED_OWNER_HAS_ACTIVE_WORK_MESSAGE,
                skipOrderIds = countBasedOrderIds,
            )
            // Count-based hosting has no expiry date, so it needs its own guard.
            assertNoUnfinishedCountHosting(
                orders = ownerOrders,
                sessionsOf = { orderId -> database.hostingSessionDao().findForOrder(orderId) },
                periodsOf = { orderId -> orderDao.findPeriods(orderId) },
                message = ARCHIVED_OWNER_HAS_UNFINISHED_COUNT_MESSAGE,
            )

            // The batch id is the identity of "archived together with its owner".
            // Timestamps are not used for that decision: two independent archives can
            // share a millisecond.
            val batchId = UUID.randomUUID().toString()
            ownerDao.archive(ownerId = ownerId, archivedAt = now, archiveBatchId = batchId)
            accountDao.archiveActiveAccountsForOwner(
                ownerId = ownerId,
                archivedAt = now,
                archiveBatchId = batchId,
            )
        }
    }

    override suspend fun restoreOwner(ownerId: String) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            // Idempotent: restoring an active owner changes nothing.
            if (owner.archivedAt == null) return@withDatasetTransaction

            // Read the provenance before the restore clears it.
            val batchId = owner.archiveBatchId
            ownerDao.restore(ownerId = ownerId, updatedAt = now)
            // Legacy rows migrated from version 1 have no batch id. Their original
            // provenance cannot be reconstructed, so nothing is restored automatically:
            // every account stays archived and can be restored explicitly by the user.
            if (batchId != null) {
                accountDao.restoreArchivedWithOwnerBatch(
                    ownerId = ownerId,
                    archiveBatchId = batchId,
                    updatedAt = now,
                )
            }
        }
    }

    /**
     * UF-031 read side. Counts come from the rows the delete transaction will remove, so
     * the confirmation dialog can state the real impact (including outstanding debt)
     * without a second, differently-filtered query.
     */
    override suspend fun ownerDeletionImpact(ownerId: String): OwnerDeletionImpact {
        val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
        val accounts = accountDao.findForOwner(ownerId)
        val orders = accounts.flatMap { orderDao.findForAccount(it.id) }
        val periods = orders.flatMap { orderDao.findPeriods(it.id) }
        val occurrences = orders.flatMap { database.occurrenceDao().findForOrder(it.id) }
        val sessions = orders.flatMap { database.hostingSessionDao().findForOrder(it.id) }
        val attachments = orders.flatMap { database.attachmentDao().findForOrder(it.id) }
        val receipts = orders.flatMap { database.receiptDao().findForOrder(it.id) }
        val activeReceipts = receipts.filter { it.status == ReceiptStatus.ACTIVE }
        val activeOrderIds = orders.filter { it.cancelledAt == null }.map { it.id }.toSet()
        val outstanding = periods.filter { it.orderId in activeOrderIds }.sumOf { period ->
            val paid = activeReceipts.filter { it.periodId == period.id }.sumOf { it.amountCents }
            (period.priceCents - paid).coerceAtLeast(0L)
        } + orders.filter { it.type == OrderType.SHORT_TASK && it.cancelledAt == null }.sumOf { order ->
            val paid = activeReceipts
                .filter { it.orderId == order.id && it.periodId == null }
                .sumOf { it.amountCents }
            ((order.priceCents ?: 0L) - paid).coerceAtLeast(0L)
        }
        return OwnerDeletionImpact(
            ownerId = ownerId,
            ownerNumber = owner.toModel().displayNumber,
            ownerNickname = owner.nickname,
            accountCount = accounts.size,
            orderCount = orders.size,
            activeOrderCount = orders.count { it.cancelledAt == null },
            periodCount = periods.size,
            occurrenceCount = occurrences.size,
            sessionCount = sessions.size,
            receiptCount = activeReceipts.size,
            receiptSumCents = activeReceipts.sumOf { it.amountCents },
            outstandingCents = outstanding,
            attachmentCount = attachments.size,
        )
    }

    /**
     * UF-031 write side: one transaction removes every order of every account of this
     * owner (tasks, rounds, check-ins, receipts, corrections, batches, prices, creation
     * identity) and finally the account and owner rows. A failure rolls the whole owner
     * back; other owners' rows are never in scope.
     */
    override suspend fun deleteArchivedOwner(ownerId: String): OwnerDeletionSummary =
        database.withDatasetTransaction {
            val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
            require(owner.archivedAt != null) {
                "只能删除已归档的老板；请先归档后再删除，或保留这份档案"
            }
            val accounts = accountDao.findForOwner(ownerId)
            var deletedOrders = 0
            var deletedRows = 0
            accounts.forEach { account ->
                orderDao.findForAccount(account.id).forEach { order ->
                    val summary = deleteOrderCascade(
                        orderId = order.id,
                        orderDao = orderDao,
                        taskDao = database.taskDao(),
                        occurrenceDao = database.occurrenceDao(),
                        receiptDao = database.receiptDao(),
                        attachmentDao = database.attachmentDao(),
                        completionBatchDao = database.completionBatchDao(),
                        hostingSessionDao = database.hostingSessionDao(),
                    )
                    deletedOrders += 1
                    deletedRows += summary.occurrenceCount + summary.receiptCount +
                        summary.attachmentCount
                }
                accountDao.deleteById(account.id)
            }
            ownerDao.deleteById(ownerId)
            OwnerDeletionSummary(
                ownerId = ownerId,
                ownerNickname = owner.nickname,
                deletedAccountCount = accounts.size,
                deletedOrderCount = deletedOrders,
                deletedDependentRowCount = deletedRows,
            )
        }

    private companion object {
        const val MAX_ALLOCATION_ATTEMPTS = 3
    }
}
