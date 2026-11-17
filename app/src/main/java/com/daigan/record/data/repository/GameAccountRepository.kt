package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.CompletionBatchDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.ArchivedAccount
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ReceiptStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * User facing reason for every account write that is refused while the parent owner
 * is archived. UI and repository share the same wording so the message a stale form
 * shows matches the message the write layer enforces.
 */
const val ARCHIVED_OWNER_ACCOUNT_MESSAGE = "老板已归档，请先恢复老板后再操作游戏账号"

/**
 * Game-account persistence boundary.
 *
 * Every write is validated inside its own database transaction: while an owner is
 * archived its accounts are read-only, so hiding buttons is not the only defence.
 * Credentials stay in the repository/DAO layer: no logging, no analytics and no file
 * writes happen here. ViewModels only see domain models.
 */
interface GameAccountRepository {

    /**
     * What a permanent account deletion would remove. Shown in the confirmation dialog
     * *before* anything is deleted (UF-007): orders, check-ins, receipts (with the
     * collected sum that leaves the statistics), screenshots and outstanding money.
     */
    suspend fun deletionImpact(accountId: String): AccountDeletionImpact {
        error("不支持删除游戏账号")
    }

    /**
     * Permanently deletes an account and every order/dependent row in one transaction.
     * Attachment *files* are removed after the commit; a failed file pass leaves no
     * dangling rows and is retried by the orphan cleanup on the next start.
     */
    suspend fun deleteAccount(accountId: String): AccountDeletionSummary {
        error("不支持删除游戏账号")
    }

    /** Archived accounts whose owner is still active, for the archived-record view. */
    fun observeArchivedAccounts(): Flow<List<ArchivedAccount>>

    /**
     * 跨老板账号视图（UF-037）：所有未归档账号。调用方结合老板与分类信息展示；
     * 本方法不改变任何归属，也不隐藏未分类账号。
     */
    fun observeActiveAccounts(): Flow<List<GameAccount>>

    suspend fun findAccount(accountId: String): GameAccount?

    /** Rejected when the owner is archived. */
    suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount

    /** Rejected when the owner is archived or the account itself is archived. */
    suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount

    /** Rejected when the owner is archived. Already archived accounts are a no-op. */
    suspend fun archiveAccount(accountId: String)

    /** Rejected when the owner is archived. Already active accounts are a no-op. */
    suspend fun restoreAccount(accountId: String)
}

@Singleton
class DefaultGameAccountRepository @Inject constructor(
    private val database: RecordDatabase,
    private val ownerDao: OwnerDao,
    private val accountDao: GameAccountDao,
    private val orderDao: OrderDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val receiptDao: ReceiptDao,
    private val attachmentDao: AttachmentDao,
    private val completionBatchDao: CompletionBatchDao,
    /** 分类归属校验（UF-037）：`categoryId` 是逻辑外键，写入前必须确认分类仍然存在。 */
    private val categoryDao: com.daigan.record.data.local.dao.AccountCategoryDao,
    private val timeProvider: TimeProvider,
    /** Post-commit file cleanup for the rows deleted above. */
    private val attachmentCleanup: AttachmentFileCleanup,
) : GameAccountRepository {

    override suspend fun deletionImpact(accountId: String): AccountDeletionImpact {
        val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
        val orders = orderDao.findForAccount(accountId)
        val receipts = orders.flatMap { receiptDao.findForOrder(it.id) }
        val occurrences = orders.flatMap { occurrenceDao.findForOrder(it.id) }
        val attachments = orders.flatMap { attachmentDao.findForOrder(it.id) }
        val periods = orders.flatMap { orderDao.findPeriods(it.id) }
        val activeReceipts = receipts.filter { it.status == ReceiptStatus.ACTIVE }
        val activeOrderIds = orders.filter { it.cancelledAt == null }.map { it.id }.toSet()
        val outstanding = periods.filter { it.orderId in activeOrderIds }.sumOf { period ->
            val paid = activeReceipts.filter { it.periodId == period.id }.sumOf { it.amountCents }
            (period.priceCents - paid).coerceAtLeast(0L)
        } + orders.filter { it.type == OrderType.SHORT_TASK && it.cancelledAt == null }.sumOf { order ->
            val paid = activeReceipts.filter { it.orderId == order.id && it.periodId == null }
                .sumOf { it.amountCents }
            ((order.priceCents ?: 0L) - paid).coerceAtLeast(0L)
        }
        return AccountDeletionImpact(
            accountId = accountId,
            gameName = account.gameName,
            server = account.server,
            uid = account.uid,
            orderCount = orders.size,
            activeOrderCount = orders.count { it.cancelledAt == null },
            periodCount = periods.size,
            occurrenceCount = occurrences.size,
            receiptCount = activeReceipts.size,
            receiptSumCents = activeReceipts.sumOf { it.amountCents },
            outstandingCents = outstanding,
            attachmentCount = attachments.size,
        )
    }

    override suspend fun deleteAccount(accountId: String): AccountDeletionSummary {
        val summary = database.withDatasetTransaction {
            val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            val orders = orderDao.findForAccount(accountId)
            var deletedOrders = 0
            var deletedRows = 0
            orders.forEach { order ->
                val orderSummary = deleteOrderCascade(
                    order.id,
                    orderDao,
                    taskDao,
                    occurrenceDao,
                    receiptDao,
                    attachmentDao,
                    completionBatchDao,
                    database.hostingSessionDao(),
                )
                deletedOrders++
                deletedRows += orderSummary.occurrenceCount + orderSummary.receiptCount +
                    orderSummary.attachmentCount
            }
            accountDao.deleteById(accountId)
            AccountDeletionSummary(
                accountId = accountId,
                gameName = account.gameName,
                deletedOrderCount = deletedOrders,
                deletedDependentRowCount = deletedRows,
            )
        }
        // Rows are committed. Orphaned screenshot files are removed now; if this pass
        // fails the rows are already gone and the start-up cleanup retries it, so a
        // committed deletion is never reported as failed.
        val cleanupFailed = runCatching { attachmentCleanup.cleanupOrphans() }.isFailure
        return summary.copy(attachmentCleanupDeferred = cleanupFailed)
    }

    override fun observeArchivedAccounts(): Flow<List<ArchivedAccount>> =
        combine(ownerDao.observeAll(), accountDao.observeArchived()) { owners, accounts ->
            val ownersById = owners.associateBy { it.id }
            accounts.mapNotNull { account ->
                val owner = ownersById[account.ownerId] ?: return@mapNotNull null
                // Accounts archived together with their owner are listed under that owner.
                if (owner.archivedAt != null) return@mapNotNull null
                ArchivedAccount(
                    ownerId = owner.id,
                    ownerNumber = owner.toModel().displayNumber,
                    ownerNickname = owner.nickname,
                    account = account.toModel(),
                )
            }
        }

    override fun observeActiveAccounts(): Flow<List<GameAccount>> =
        accountDao.observeActive().map { rows -> rows.map { it.toModel() } }

    override suspend fun findAccount(accountId: String): GameAccount? =
        accountDao.findById(accountId)?.toModel()

    override suspend fun createAccount(ownerId: String, draft: AccountDraft): GameAccount {
        val normalized = draft.normalized()
        normalized.validate().values.firstOrNull()?.let { throw IllegalArgumentException(it) }

        val now = timeProvider.now()
        return database.withDatasetTransaction {
            requireActiveOwner(ownerId)
            val entity = GameAccountEntity(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                // A verification-code account never keeps a password value.
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
                archivedAt = null,
                defaultTemplateId = normalized.defaultTemplateId,
                loginDevice = normalized.loginDevice.ifBlank { null },
                resourceNotes = normalized.resourceNotes.ifBlank { null },
                categoryId = normalized.categoryId?.also { categoryId ->
                    require(categoryDao.findById(categoryId) != null) { "分类不存在" }
                },
            )
            accountDao.insert(entity)
            entity.toModel()
        }
    }

    override suspend fun updateAccount(accountId: String, draft: AccountDraft): GameAccount {
        val normalized = draft.normalized()
        normalized.validate().values.firstOrNull()?.let { throw IllegalArgumentException(it) }

        val now = timeProvider.now()
        return database.withDatasetTransaction {
            val existing = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            // A stale form may be submitted after the owner was archived elsewhere.
            requireActiveOwner(existing.ownerId)
            require(existing.archivedAt == null) { "游戏账号已归档，请先恢复后再编辑" }
            val categoryId = normalized.categoryId?.also { target ->
                require(categoryDao.findById(target) != null) { "分类不存在" }
            }
            accountDao.update(
                accountId = accountId,
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                // Switching to verification-code login clears the stored password.
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                defaultTemplateId = normalized.defaultTemplateId,
                loginDevice = normalized.loginDevice.ifBlank { null },
                resourceNotes = normalized.resourceNotes.ifBlank { null },
                categoryId = categoryId,
                updatedAt = now,
            )
            existing.copy(
                gameName = normalized.gameName,
                server = normalized.server,
                uid = normalized.uid,
                loginAccount = normalized.loginAccount,
                loginMethod = normalized.loginMethod,
                password = normalized.password.takeIf { normalized.loginMethod == LoginMethod.PASSWORD && it.isNotBlank() },
                defaultTemplateId = normalized.defaultTemplateId,
                loginDevice = normalized.loginDevice.ifBlank { null },
                // The returned model must show what was just saved; leaving this out made
                // the caller render the previous resource text.
                resourceNotes = normalized.resourceNotes.ifBlank { null },
                categoryId = categoryId,
                updatedAt = now,
            ).toModel()
        }
    }

    override suspend fun archiveAccount(accountId: String) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            requireActiveOwner(account.ownerId)
            // Idempotent: archiving twice keeps the original archive instant.
            if (account.archivedAt != null) return@withDatasetTransaction
            require(orderDao.countUnfinishedShortOrdersForAccount(accountId) == 0) {
                ARCHIVED_ACCOUNT_HAS_ACTIVE_WORK_MESSAGE
            }
            val accountOrders = orderDao.findForAccount(accountId)
            assertNoUnexpiredPeriod(
                periods = orderDao.findHostingPeriodsForAccount(accountId),
                now = now,
                message = ARCHIVED_ACCOUNT_HAS_ACTIVE_WORK_MESSAGE,
                skipOrderIds = accountOrders
                    .filter { it.hostingMode == HostingMode.COUNT_BASED }
                    .map { it.id }
                    .toSet(),
            )
            // Count-based hosting has no expiry date, so it needs its own guard.
            assertNoUnfinishedCountHosting(
                orders = accountOrders,
                sessionsOf = { orderId -> database.hostingSessionDao().findForOrder(orderId) },
                periodsOf = { orderId -> orderDao.findPeriods(orderId) },
                message = ARCHIVED_ACCOUNT_HAS_UNFINISHED_COUNT_MESSAGE,
            )
            accountDao.archiveIndependently(accountId = accountId, archivedAt = now)
        }
    }

    override suspend fun restoreAccount(accountId: String) {
        val now = timeProvider.now()
        database.withDatasetTransaction {
            val account = accountDao.findById(accountId) ?: throw IllegalArgumentException("游戏账号不存在")
            // While the owner is archived its accounts are read-only, so an archived
            // child can never be pulled back into an archived parent.
            requireActiveOwner(account.ownerId)
            if (account.archivedAt == null) return@withDatasetTransaction
            accountDao.restoreIndependently(accountId = accountId, updatedAt = now)
        }
    }

    /** Must be called inside the write transaction so the state cannot change underneath. */
    private suspend fun requireActiveOwner(ownerId: String) {
        val owner = ownerDao.findById(ownerId) ?: throw IllegalArgumentException("老板不存在")
        require(owner.archivedAt == null) { ARCHIVED_OWNER_ACCOUNT_MESSAGE }
    }
}

data class AccountDeletionSummary(
    val accountId: String,
    val gameName: String,
    val deletedOrderCount: Int,
    val deletedDependentRowCount: Int,
    /** True when the committed deletion left files for the start-up orphan pass. */
    val attachmentCleanupDeferred: Boolean = false,
)

/**
 * Read-only preview of a permanent account deletion. Counts and money are computed from
 * the same rows the transaction later deletes, so the confirmation text and the result
 * cannot disagree (UF-007).
 */
data class AccountDeletionImpact(
    val accountId: String,
    val gameName: String,
    val server: String,
    val uid: String,
    /** Every order of this account, including cancelled and completed ones. */
    val orderCount: Int,
    /** Orders that are neither cancelled nor removed. */
    val activeOrderCount: Int,
    val periodCount: Int,
    val occurrenceCount: Int,
    /** Effective receipts only: voided/replaced rows never counted as income. */
    val receiptCount: Int,
    val receiptSumCents: Long,
    val outstandingCents: Long,
    val attachmentCount: Int,
)
