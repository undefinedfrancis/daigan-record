package com.daigan.record.data.repository

import androidx.room.withTransaction
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.HostingPeriodClock
import com.daigan.record.core.time.HostingPeriodSelection
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.core.time.PersistedInstant
import com.daigan.record.core.time.ResolvedHostingPeriod
import com.daigan.record.core.time.TimeTicker
import com.daigan.record.core.time.nextBoundaryAcross
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.CompletionBatchDao
import com.daigan.record.data.local.dao.CreationOperationDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.HostingSessionDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.dao.TaskProgressEventDao
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.HostingSessionEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.parseWeekdays
import com.daigan.record.data.local.encodeWeekdays
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.BulkCompletionResult
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.OccurrenceSlot
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderCommandFingerprint
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderDeletionSummary
import com.daigan.record.data.model.OrderEditDraft
import com.daigan.record.data.model.OrderPriceChangeRecord
import com.daigan.record.data.model.OrderSummary
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.RenewalResult
import com.daigan.record.data.model.encodeMonthlyDates
import com.daigan.record.data.model.parseMonthlyDates
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.ShortWorkTask
import com.daigan.record.data.model.TaskOccurrence
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.TodayHostingTask
import com.daigan.record.data.model.UndoConflict
import com.daigan.record.data.model.UndoResult
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge

/** User facing reasons shared by repositories and screens. */
const val ARCHIVED_OWNER_ORDER_MESSAGE = "老板已归档，请先恢复老板后再操作订单"
const val ARCHIVED_ACCOUNT_ORDER_MESSAGE = "游戏账号已归档，请先恢复账号后再操作订单"
const val ARCHIVED_OWNER_WORK_MESSAGE = "老板已归档，请先恢复老板后再记录工作"

/**
 * Reusing a create-order operation id with a different normalized payload is a
 * programming/UI error, not a retry: the stored order is returned only when the whole
 * command matches.
 */
const val CREATION_CONFLICT_MESSAGE = "该建单操作标识已被另一份订单使用，请刷新后查看原订单"

/**
 * The occurrence a screen showed is no longer the current one (a daily or weekly
 * boundary was crossed). Callers must refresh and ask again instead of silently
 * writing the action into another game day.
 */
class StaleOccurrenceException(message: String) : IllegalStateException(message)

/**
 * Identities a screen was showing when a hosting bulk action was tapped.
 *
 * The caller passes exactly what the user saw; the repository validates all three
 * levels inside the transaction. If the displayed work is stale the whole operation
 * is rejected with [StaleOccurrenceException] before any row is written, so a batch
 * can never silently complete a different game day's or game week's tasks.
 */
data class DisplayedHostingSnapshot(
    val orderId: String,
    /** The hosting period shown on screen. */
    val periodId: String,
    /** The game date shown on screen (drives daily occurrence windows). */
    val gameDate: LocalDate,
    /** taskId -> occurrence slot key the screen showed for that task today. */
    val occurrenceKeys: Map<String, String>,
)

interface OrderRepository {
    suspend fun archiveExpiredHostingOrder(orderId: String) { error("不支持归档订单") }
    suspend fun restoreArchivedOrder(orderId: String) { error("不支持恢复订单") }
    suspend fun updateUnusedOrderIdentity(orderId: String, accountId: String, type: OrderType, start: LocalDate, end: LocalDate?) { error("不支持更正所属账号与订单类型") }
    suspend fun updateServiceSchedule(orderId: String, periodId: String, serviceDays: Int?, weekdays: Set<DayOfWeek>) { error("服务计划尚不支持更正") }
    suspend fun updateOrderRefresh(orderId: String, rules: GameRefreshRules) { error("不支持修改刷新规则") }
    suspend fun updateTaskDetails(orderId: String, tasks: List<com.daigan.record.data.model.TaskDraft>) { error("不支持修改任务") }
    suspend fun updateOrderDates(orderId: String, periodId: String?, startDate: LocalDate?, endDate: LocalDate?) {
        error("不支持修改日期")
    }
    suspend fun deleteUnusedOrder(orderId: String) { error("不支持删除订单") }
    suspend fun cancelOrder(orderId: String) { error("不支持作废订单") }

    /**
     * Permanently deletes a cancelled order with every dependent record (UF-009):
     * tasks, check-ins, progress, attachment rows, receipts and their corrections,
     * batch audit rows, price-change audit rows, periods and the creation identity.
     * Attachment *files* are cleaned up by `cleanupOrphans()` after the transaction.
     * Only cancelled orders qualify; other orders keep the v1.6 delete/void rules.
     */
    suspend fun deleteCancelledOrder(orderId: String): OrderDeletionSummary {
        error("不支持删除订单")
    }

    /** Renames the order in place; identity, periods, tasks, check-ins and receipts stay. */
    suspend fun updateOrderTitle(orderId: String, title: String) { error("不支持修改订单名称") }
    suspend fun updateOrderNotes(orderId: String, notes: String?) { error("不支持修改订单备注") }
    suspend fun updatePaymentMode(orderId: String, mode: PaymentMode) { error("不支持修改收款方式") }

    /**
     * Stores or clears the order-level business colour (UF-028).
     *
     * Colour belongs to one order, so two orders of the same account are independent.
     * It is presentation metadata: changing it never touches prices, tasks, check-ins,
     * receipts or attachments, and it is persisted in `orders.orderColor` so a backup
     * carries it.
     */
    suspend fun setOrderColor(orderId: String, colorArgb: Int?) { error("不支持修改订单颜色") }

    /**
     * One atomic multi-field correction (UF-030).
     *
     * Everything the old per-field dialogs did — title, notes, payment mode, agreed
     * price (audited), service dates, service-day plan, refresh rules, task
     * additions/changes/removals and (for untouched orders only) owning account/type —
     * is applied inside **one** transaction against one [OrderEditDraft]. Any rejected
     * field rolls the whole edit back, so no partial update can survive a failed save.
     * Recorded history is protected by the same guards the individual entry points used.
     */
    suspend fun applyOrderEdit(draft: OrderEditDraft) {
        error("不支持统一修改订单")
    }

    /**
     * Corrects one short order's agreed price. Receipts are never rewritten; the change
     * is audited under [operationId], which makes retries idempotent.
     */
    suspend fun updateShortOrderPrice(
        orderId: String,
        priceCents: Long,
        operationId: String,
        reason: String?,
    ) {
        error("不支持修改价格")
    }

    /** Like [updateShortOrderPrice] but for exactly one hosting period's price. */
    suspend fun updatePeriodPrice(
        orderId: String,
        periodId: String,
        priceCents: Long,
        operationId: String,
        reason: String?,
    ) {
        error("不支持修改价格")
    }


    fun observeOrdersForOwner(ownerId: String): Flow<List<OrderSummary>>

    /**
     * One order with its tasks, receipts and correction audit. Every emission is read
     * in a single transaction, so a detail balance always belongs to one database
     * version rather than a mix of independently refreshed DAO flows.
     */
    fun observeOrderDetail(orderId: String): Flow<OrderDetail?>

    /** Active accounts only. Expired and not-yet-started periods are included. */
    fun observeTodayHosting(): Flow<List<TodayHostingOrder>>

    /** Unfinished short orders on active accounts. */
    fun observeTodayShort(): Flow<List<ShortWorkOrder>>

    /**
     * Creates a short order (tasks and optional first receipt) as **one** idempotent
     * command keyed by [ShortOrderDraft.creationOperationId].
     *
     * An identical retry returns the original order id. Reusing the id with a different
     * normalized payload is rejected. Any validation or receipt failure rolls the whole
     * creation back.
     */
    suspend fun createShortOrder(draft: ShortOrderDraft): String

    /** Hosting counterpart of [createShortOrder]; the period is part of the command. */
    suspend fun createHostingOrder(draft: HostingOrderDraft): String

    suspend fun setShortTaskCompleted(taskId: String, completed: Boolean)

    suspend fun updateShortTaskProgress(taskId: String, value: BigDecimal)

    suspend fun completeShortOrder(orderId: String, operationId: String): BulkCompletionResult

    /**
     * Completes/clears the current actionable occurrence of one hosting task.
     *
     * [expectedOccurrenceKey] is the occurrence key the screen displayed. When a
     * boundary was crossed in the meantime the write is rejected with
     * [StaleOccurrenceException] instead of landing on a different game day/window.
     */
    suspend fun setOccurrenceCompleted(
        taskId: String,
        completed: Boolean,
        expectedOccurrenceKey: String? = null,
    )

    suspend fun updateOccurrenceProgress(
        taskId: String,
        value: BigDecimal,
        expectedOccurrenceKey: String? = null,
    )

    /**
     * Completes the currently actionable occurrences of one hosting order.
     *
     * [displayed] is what the screen was showing when the user tapped. It is validated
     * inside the transaction; a stale period, game date or occurrence window rejects
     * the whole operation instead of writing to another game day.
     */
    suspend fun completeHostingOrder(
        orderId: String,
        operationId: String,
        displayed: DisplayedHostingSnapshot,
    ): BulkCompletionResult

    /**
     * Completes the currently actionable occurrences of every active hosting order.
     *
     * [displayed] carries one snapshot per order the screen showed. Only orders the
     * user actually saw are completed; an order missing from the snapshot is skipped.
     * A stale snapshot rejects the whole operation with no partial batch.
     */
    suspend fun completeAllHosting(
        operationId: String,
        displayed: List<DisplayedHostingSnapshot>,
    ): BulkCompletionResult

    suspend fun undoBatch(batchId: String): UndoResult

    /**
     * Backfill/correction for a specific historical game date.
     *
     * Future game dates and weekly windows that have not begun are rejected inside the
     * transaction. [weeklyWindowStart] identifies the exact weekly occurrence when one
     * game day touches two windows; when null the documented midday window is used.
     */
    suspend fun setHistoricalCompletion(
        orderId: String,
        periodId: String,
        taskId: String,
        date: LocalDate,
        completed: Boolean,
        weeklyWindowStart: LocalDate? = null,
    ): Boolean

    suspend fun renewHosting(
        orderId: String,
        operationId: String,
        durationDays: Int,
        priceCents: Long,
    ): RenewalResult

    /**
     * 计次托管续期：追加一个**独立期次**（自己的目标次数、价格与任务快照），不伪造
     * 任何日历截止日。历史期次、轮次与事件全部保留；新期次的累计 scope 从空开始。
     */
    suspend fun renewCountHosting(
        orderId: String,
        operationId: String,
        targetSessions: Int,
        priceCents: Long,
        expectedGeneration: Long? = null,
    ): RenewalResult {
        error("不支持计次续期")
    }

    /**
     * Explicit numeric-mode switch. Blocked once the task has an occurrence or a progress
     * event: switching rewrites the meaning of existing history.
     */
    suspend fun updateTaskNumericMode(taskId: String, mode: NumericMode, expectedGeneration: Long? = null) {
        error("不支持修改数值口径")
    }

    /**
     * Reopens one completed round of 打卡式托管 for correction. The round row is kept (so
     * history is not silently deleted), its task completions are cleared and the total
     * drops by exactly one. Reaching the target again counts it once more.
     */
    suspend fun reopenCountSession(orderId: String, sessionIndex: Int, expectedGeneration: Long? = null) {
        error("不支持撤销打卡轮次")
    }

    /**
     * Historical correction for a task that has been **removed** (H1).
     *
     * A retired task keeps its responsibility for every round it was still due in, so a
     * round that owes it must stay reachable for correction instead of being permanently
     * incomplete. This command is deliberately narrow and separate from
     * [setOccurrenceCompleted] so normal new-progress writes stay unavailable:
     *
     * - only an **existing** round may be corrected (never a not-yet-written index);
     * - the round's own attributed game date must fall on or before the retirement game
     *   date, i.e. the task was still due then ([CountHosting.isDueInRound]);
     * - the task must belong to that round's period and order;
     * - cumulative tasks are refused here — their historical truth is the append-only
     *   event chain and is corrected in the cumulative history panel.
     */
    suspend fun setHistoricalRoundCompletion(
        orderId: String,
        sessionIndex: Int,
        taskId: String,
        completed: Boolean,
        expectedGeneration: Long? = null,
    ) {
        error("不支持更正已移除任务的历史轮次")
    }
}

/**
 * Captures exactly what the Today page is showing for one order: the period, the game
 * date and each task's occurrence window. Bulk operations validate this snapshot so a
 * refresh boundary crossed between render and tap cannot complete a different day.
 */
fun TodayHostingOrder.toDisplayedSnapshot(): DisplayedHostingSnapshot = DisplayedHostingSnapshot(
    orderId = order.id,
    periodId = period.id,
    gameDate = today,
    occurrenceKeys = tasks.associate { it.task.id to it.slot.key },
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class DefaultOrderRepository @Inject constructor(
    private val database: RecordDatabase,
    private val orderDao: OrderDao,
    private val taskDao: TaskDao,
    private val occurrenceDao: OccurrenceDao,
    private val batchDao: CompletionBatchDao,
    private val attachmentDao: AttachmentDao,
    private val accountDao: GameAccountDao,
    private val ownerDao: OwnerDao,
    private val receiptDao: ReceiptDao,
    private val receiptLedger: ReceiptLedger,
    private val creationOperationDao: CreationOperationDao,
    private val hostingSessionDao: HostingSessionDao,
    private val eventDao: TaskProgressEventDao,
    private val countSessions: CountSessionService,
    private val ticker: TimeTicker,
) : OrderRepository {

    private data class AccountOwner(
        val account: GameAccountEntity,
        val owner: OwnerEntity,
    )

    private data class PendingOccurrence(
        val occurrence: TaskOccurrenceEntity,
        val previousCompletedAt: Instant?,
        val previousRecordedAt: Instant?,
        val previousBackfilled: Boolean,
        val previousValue: BigDecimal?,
        val previousBatchId: String?,
        val appliedCompletedAt: Instant,
        val appliedValue: BigDecimal?,
    )

    private fun accountOwnerFlow(): Flow<Map<String, AccountOwner>> =
        combine(accountDao.observeActive(), ownerDao.observeAll()) { accounts, owners ->
            val ownersById = owners.associateBy { it.id }
            accounts.mapNotNull { account ->
                ownersById[account.ownerId]?.let { owner ->
                    account.id to AccountOwner(account, owner)
                }
            }.toMap()
        }

    override fun observeOrdersForOwner(ownerId: String): Flow<List<OrderSummary>> {
        val ownerContext = combine(
            accountDao.observeForOwner(ownerId),
            ownerDao.observeById(ownerId),
        ) { accounts, owner -> accounts.associateBy { it.id } to owner }

        data class OwnerSnapshot(
            val orders: List<OrderEntity>,
            val periods: List<HostingPeriodEntity>,
            val tasks: List<TaskDefinitionEntity>,
            val occurrences: List<TaskOccurrenceEntity>,
            val sessions: List<HostingSessionEntity>,
            val accountsById: Map<String, GameAccountEntity>,
            val owner: OwnerEntity,
        )

        data class OwnerRows(
            val orders: List<OrderEntity>,
            val periods: List<HostingPeriodEntity>,
            val tasks: List<TaskDefinitionEntity>,
            val occurrences: List<TaskOccurrenceEntity>,
        )

        val rows = combine(
            orderDao.observeForOwner(ownerId),
            orderDao.observePeriodsForOwner(ownerId),
            taskDao.observeForOwner(ownerId),
            occurrenceDao.observeForOwner(ownerId),
        ) { orders, periods, tasks, occurrences ->
            OwnerRows(orders, periods, tasks, occurrences)
        }

        return combine(rows, hostingSessionDao.observeAll(), ownerContext) { data, sessions, context ->
            val (accountsById, owner) = context
            if (owner == null) {
                null
            } else {
                OwnerSnapshot(
                    data.orders,
                    data.periods,
                    data.tasks,
                    data.occurrences,
                    sessions.filter { row -> data.orders.any { it.id == row.orderId } },
                    accountsById,
                    owner,
                )
            }
        }.flatMapLatest { snapshot ->
            if (snapshot == null) {
                flowOf(emptyList<OrderSummary>())
            } else {
                // Totals describe the period running today, so the list also follows a
                // day boundary without a database write.
                val rules = snapshot.periods.map { refreshOf(it) } + snapshot.orders.map { refreshOf(it) }
                ticker.ticks { now -> nextBoundaryAcross(rules, now) }.map { now ->
                    buildOwnerSummaries(snapshot.orders, snapshot.periods, snapshot.tasks,
                        snapshot.occurrences, snapshot.sessions, snapshot.accountsById, snapshot.owner, now)
                }
            }
        }
    }

    private fun buildOwnerSummaries(
        orders: List<OrderEntity>,
        periods: List<HostingPeriodEntity>,
        tasks: List<TaskDefinitionEntity>,
        occurrences: List<TaskOccurrenceEntity>,
        sessions: List<HostingSessionEntity>,
        accountsById: Map<String, GameAccountEntity>,
        owner: OwnerEntity,
        now: Instant,
    ): List<OrderSummary> = orders.mapNotNull { order ->
        val account = accountsById[order.accountId] ?: return@mapNotNull null
        val orderPeriods = periods.filter { it.orderId == order.id }
        val orderTasks = tasks.filter { it.orderId == order.id }
        // 打卡式托管 counts completed rounds, not today's task rows: a 12/30 progress
        // must stay meaningful on days without work and across round changes. The target
        // is the sum of each count period's own target, never the order-level mirror.
        if (order.hostingMode == HostingMode.COUNT_BASED) {
            val orderSessions = sessions.filter { it.orderId == order.id }.map { it.toModel() }
            val countPeriods = orderPeriods.filter { it.serviceCount != null }
            val target = countPeriods.sumOf { it.serviceCount ?: 0 }
            val completed = CountHosting.completedCount(orderSessions)
            val active = CountHosting.resolveActiveSession(
                order.id,
                orderPeriods.map { it.toModel() },
                orderSessions,
            )
            val currentPeriodId = when (active) {
                is CountHosting.ActiveCountState.ActiveSession -> active.periodId
                is CountHosting.ActiveCountState.AwaitingSessionRow -> active.periodId
                is CountHosting.ActiveCountState.IndexSpaceExhausted -> active.periodId
                CountHosting.ActiveCountState.AllPeriodsComplete -> null
            }
            val shownPeriod = currentPeriodId?.let { id -> orderPeriods.firstOrNull { it.id == id } }
                ?: orderPeriods.maxByOrNull { it.periodIndex }
            return@mapNotNull OrderSummary(
                order = order.toModel(),
                ownerId = owner.id,
                ownerNumber = owner.toModel().displayNumber,
                ownerNickname = owner.nickname,
                accountId = account.id,
                gameName = account.gameName,
                server = account.server,
                currentPeriod = shownPeriod?.toModel(),
                taskCount = target,
                completedTaskCount = completed,
            )
        }
        // The list must describe the period that is running today. Taking the highest
        // periodIndex would describe a future period after an early renewal and report
        // 0 / N completed tasks for a running order. Each period is judged by its own
        // stored refresh snapshot, exactly like the Today list and the write path.
        val currentPeriod = resolvePeriod(orderPeriods, now)?.period
        val relevantTaskIds = if (order.type == OrderType.HOSTING) {
            orderTasks.filter { it.periodId == currentPeriod?.id && it.retiredAt == null }.map { it.id }.toSet()
        } else {
            orderTasks.filter { it.retiredAt == null }.map { it.id }.toSet()
        }
        val completedCount = if (order.type == OrderType.HOSTING) {
            occurrences.count { it.taskDefinitionId in relevantTaskIds && it.completedAt != null }
        } else {
            orderTasks.count { it.completedAt != null && it.retiredAt == null }
        }
        OrderSummary(
            order = order.toModel(),
            ownerId = owner.id,
            ownerNumber = owner.toModel().displayNumber,
            ownerNickname = owner.nickname,
            accountId = account.id,
            gameName = account.gameName,
            server = account.server,
            currentPeriod = currentPeriod?.toModel(),
            taskCount = relevantTaskIds.size,
            completedTaskCount = completedCount,
        )
    }.sortedByDescending { it.order.createdAt }

    /**
     * Rounds whose every active task has a completed occurrence under the round key.
     * Derived from occurrences (not from dates), so a day without activity adds nothing
     * and two rounds on the same day are still two.
     */
    private fun countCompletedRounds(
        orderTasks: List<TaskDefinitionEntity>,
        occurrences: List<TaskOccurrenceEntity>,
    ): Int {
        val activeTaskIds = orderTasks.filter { it.retiredAt == null }.map { it.id }.toSet()
        if (activeTaskIds.isEmpty()) return 0
        val completedByRound = mutableMapOf<Int, MutableSet<String>>()
        occurrences.forEach { occurrence ->
            if (occurrence.completedAt == null) return@forEach
            if (occurrence.taskDefinitionId !in activeTaskIds) return@forEach
            val index = CountHosting.sessionIndexOrNull(occurrence.occurrenceKey)
                ?: return@forEach
            completedByRound.getOrPut(index) { mutableSetOf() } += occurrence.taskDefinitionId
        }
        return completedByRound.count { (_, done) ->
            CountHosting.roundCompleted(activeTaskIds, done)
        }
    }

    private data class OrderDetailSnapshot(
        val order: OrderEntity,
        val owner: OwnerEntity,
        val account: GameAccountEntity,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val attachments: List<AttachmentEntity>,
        val periods: List<HostingPeriodEntity>,
        val receipts: List<ReceiptEntity>,
        val corrections: List<ReceiptCorrectionEntity>,
        val priceChanges: List<com.daigan.record.data.local.entity.OrderPriceChangeEntity>,
        val sessions: List<HostingSessionEntity>,
    )

    private data class TodayHostingSnapshot(
        val orders: List<OrderEntity>,
        val periods: List<HostingPeriodEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val sessions: List<HostingSessionEntity>,
        val context: Map<String, AccountOwner>,
    )

    private data class TodayShortSnapshot(
        val orders: List<OrderEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val context: Map<String, AccountOwner>,
    )

    /**
     * One transactionally consistent per-order read per database change.
     *
     * This used to combine seven independent DAO flows. Each query re-runs on its own
     * schedule, so one commit could be published as a mix of two database versions (for
     * example a new receipt next to the previous period list). The invalidation sources
     * are still Room's own table observations, but the rows are now read inside one
     * `withTransaction` block, like [LedgerSnapshotSource] does for the global ledger.
     * No debounce or delay hides a real inconsistency.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOrderDetail(orderId: String): Flow<OrderDetail?> =
        merge(
            orderDao.observeById(orderId).map { Unit },
            orderDao.observePeriodsForOrder(orderId).map { Unit },
            taskDao.observeForOrder(orderId).map { Unit },
            occurrenceDao.observeForOrder(orderId).map { Unit },
            attachmentDao.observeForOrder(orderId).map { Unit },
            receiptDao.observeForOrder(orderId).map { Unit },
            receiptDao.observeCorrectionsForOrder(orderId).map { Unit },
            orderDao.observePriceChanges(orderId).map { Unit },
            hostingSessionDao.observeForOrder(orderId).map { Unit },
            accountDao.observeAll().map { Unit },
            ownerDao.observeAll().map { Unit },
        ).mapLatest {
            database.withDatasetTransaction { readOrderDetail(orderId) }
        }.flatMapLatest { snapshot ->
            if (snapshot == null) {
                flowOf(null)
            } else {
                // Daily/weekly boundaries move "today" and the live weekly window
                // without any database write.
                val rules = buildList {
                    add(refreshOf(snapshot.order))
                    snapshot.periods.forEach { add(refreshOf(it)) }
                }
                ticker.ticks { now -> nextBoundaryAcross(rules, now) }
                    .map { now -> snapshot.toDetail(now) }
            }
        }

    /** Must run inside [observeOrderDetail]'s transaction; every read sees one version. */
    private suspend fun readOrderDetail(orderId: String): OrderDetailSnapshot? {
        val order = orderDao.findById(orderId) ?: return null
        val account = accountDao.findById(order.accountId) ?: return null
        val owner = ownerDao.findById(account.ownerId) ?: return null
        return OrderDetailSnapshot(
            order = order,
            owner = owner,
            account = account,
            tasks = taskDao.findForOrder(orderId),
            occurrences = occurrenceDao.findForOrder(orderId),
            attachments = attachmentDao.findForOrder(orderId),
            periods = orderDao.findPeriods(orderId),
            receipts = receiptDao.findForOrder(orderId),
            corrections = receiptDao.findCorrectionsForOrder(orderId),
            priceChanges = orderDao.findPriceChanges(orderId),
            sessions = hostingSessionDao.findForOrder(orderId),
        )
    }

    private fun OrderDetailSnapshot.toDetail(now: Instant): OrderDetail {
        val periodModels = periods.map { period -> period.toModel() }
        // A hosting detail page is about one period, so "today" is that period's own game
        // date. Short orders have no period and keep the order's rules.
        // Count-based hosting has no period in the business sense: the container row's
        // dates are bookkeeping, so "today" must come from the order's own refresh rules
        // (otherwise a round completed later would be recorded on the container date).
        val today = if (order.hostingMode == HostingMode.COUNT_BASED) {
            GameCalendar.gameDate(now, refreshOf(order))
        } else {
            resolvePeriod(periods, now)?.gameDate
                ?: GameCalendar.gameDate(now, refreshOf(order))
        }
        return OrderDetail(
            order = order.toModel(),
            owner = owner.toModel(),
            account = account.toModel(),
            tasks = tasks.map { task -> task.toModel() },
            periods = periodModels,
            occurrences = occurrences.map { it.toModel() },
            attachments = attachments.map { it.toModel() },
            receipts = receipts.map { it.toModel() },
            receiptCorrections = corrections.map { it.toModel() },
            priceChanges = priceChanges.map {
                OrderPriceChangeRecord(
                    id = it.id,
                    operationId = it.operationId,
                    orderId = it.orderId,
                    periodId = it.periodId,
                    previousPriceCents = it.previousPriceCents,
                    newPriceCents = it.newPriceCents,
                    reason = it.reason,
                    createdAt = it.createdAt,
                )
            },
            today = today,
            now = now,
            sessions = sessions.map { it.toModel() },
        )
    }

    override fun observeTodayHosting(): Flow<List<TodayHostingOrder>> {
        // `combine` has typed overloads only up to five flows; occurrences and rounds are
        // paired first so the snapshot still describes one database version.
        val occurrencesAndSessions = combine(
            occurrenceDao.observeHostingOccurrencesForActiveAccounts(),
            hostingSessionDao.observeForActiveHosting(),
        ) { occurrences, sessions -> occurrences to sessions }
        return combine(
            orderDao.observeActiveHostingOrders(),
            orderDao.observePeriodsForActiveHosting(),
            taskDao.observeHostingTasksForActiveAccounts(),
            occurrencesAndSessions,
            accountOwnerFlow(),
        ) { orders, periods, tasks, occurrencePairs, context ->
            TodayHostingSnapshot(
                orders = orders,
                periods = periods,
                tasks = tasks,
                occurrences = occurrencePairs.first,
                sessions = occurrencePairs.second,
                context = context,
            )
        }.flatMapLatest { snapshot ->
            val rules = snapshot.periods.map { refreshOf(it) }
            ticker.ticks { now -> nextBoundaryAcross(rules, now) }.map { now ->
                buildTodayHosting(snapshot, now)
            }
        }
    }

    override fun observeTodayShort(): Flow<List<ShortWorkOrder>> =
        combine(
            orderDao.observeUnfinishedShortOrders(),
            taskDao.observeShortTasksForActiveAccounts(),
            accountOwnerFlow(),
        ) { orders, tasks, context ->
            TodayShortSnapshot(orders, tasks, context)
        }.flatMapLatest { snapshot ->
            val rules = snapshot.orders.map { refreshOf(it) }
            ticker.ticks { now -> nextBoundaryAcross(rules, now) }.map { now ->
                buildTodayShort(snapshot, now)
            }
        }

    private fun buildTodayHosting(
        snapshot: TodayHostingSnapshot,
        now: Instant,
    ): List<TodayHostingOrder> = snapshot.orders.mapNotNull { order ->
        val accountOwner = snapshot.context[order.accountId] ?: return@mapNotNull null
        // 打卡式托管 has no business dates and never expires; its work comes from the
        // round in progress, so it must not go through the calendar status logic below.
        if (order.hostingMode == HostingMode.COUNT_BASED) {
            return@mapNotNull buildTodayCountBased(order, accountOwner, snapshot, now)
        }
        // Early renewal inserts a period that has not started yet. Today's list must stay
        // on the period that contains today's game date; only the display fallback uses
        // the upcoming/expired period so an order never silently disappears.
        //
        // Selection, status and task slots all read the same resolved pair (period + its
        // own game date). Recomputing `today` from the period snapshot after selecting
        // with the order row's rules used to disagree at a daily-refresh boundary, which
        // showed tasks that the write path then rejected as stale.
        val resolved = resolvePeriod(snapshot.periods.filter { it.orderId == order.id }, now)
            ?: return@mapNotNull null
        val period = resolved.period
        val rules = refreshOf(period)
        val today = resolved.gameDate
        val status = GameCalendar.hostingStatus(today, period.startDate, period.endDate)
        val periodTasks = snapshot.tasks.filter { it.periodId == period.id }
        val occurrencesByKey = snapshot.occurrences
            .filter { occurrence -> periodTasks.any { it.id == occurrence.taskDefinitionId } }
            .associateBy { it.taskDefinitionId to it.occurrenceKey }

        val todayTasks = if (status == HostingStatus.ACTIVE) {
            periodTasks.mapNotNull { task ->
                val model = task.toModel()
                val slot = OccurrenceRules.slotForNow(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    monthDay = model.monthDay,
                    monthDays = model.monthDays,
                    activeFrom = model.activeFrom,
                    activeUntil = model.activeUntil,
                    retiredAt = model.retiredAt,
                    serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    today = today,
                    rules = rules,
                    now = now,
                ) ?: return@mapNotNull null
                val occurrence = occurrencesByKey[task.id to slot.key]
                TodayHostingTask(
                    task = model,
                    occurrenceId = occurrence?.id,
                    slot = slot,
                    completed = occurrence?.completedAt != null,
                    value = occurrence?.value,
                    windowLabel = OccurrenceRules.windowLabel(slot),
                )
            }.sortedBy { it.task.sortOrder }
        } else {
            emptyList()
        }

        TodayHostingOrder(
            order = order.toModel(),
            ownerId = accountOwner.owner.id,
            ownerNumber = accountOwner.owner.toModel().displayNumber,
            ownerNickname = accountOwner.owner.nickname,
            accountId = accountOwner.account.id,
            gameName = accountOwner.account.gameName,
            server = accountOwner.account.server,
            period = period.toModel(),
            today = today,
            status = status,
            tasks = todayTasks,
        )
    }.sortedWith(
        compareBy<TodayHostingOrder>(
            { it.status == HostingStatus.EXPIRED || it.status == HostingStatus.NOT_STARTED },
            { it.allTasksCompleted },
            { it.order.createdAt },
        ),
    )

    /**
     * Today's card of a count-based order.
     *
     * The card carries the round currently in progress (fresh task progress for every
     * round), the completed/target counters and no date-based status. When the target is
     * reached the card has no tasks and [TodayHostingOrder.sessionsComplete] is true, so
     * the Today page moves it to the finished section instead of calling it overdue.
     */
    private fun buildTodayCountBased(
        order: OrderEntity,
        accountOwner: AccountOwner,
        snapshot: TodayHostingSnapshot,
        now: Instant,
    ): TodayHostingOrder? {
        // Count periods carry price/refresh only; their dates are never read for
        // calendar expiry. With several periods the active state names the owning
        // period, so round 2 of a renewed agreement is never rendered from period 1.
        val orderPeriods = snapshot.periods.filter { it.orderId == order.id }
        if (orderPeriods.isEmpty()) return null
        val sessions = snapshot.sessions.filter { it.orderId == order.id }.map { it.toModel() }
        val state = CountHosting.resolveActiveSession(
            order.id,
            orderPeriods.map { it.toModel() },
            sessions,
        )
        val periodId = when (state) {
            is CountHosting.ActiveCountState.ActiveSession -> state.periodId
            is CountHosting.ActiveCountState.AwaitingSessionRow -> state.periodId
            is CountHosting.ActiveCountState.IndexSpaceExhausted -> state.periodId
            CountHosting.ActiveCountState.AllPeriodsComplete ->
                orderPeriods.maxByOrNull { it.periodIndex }?.id
        } ?: return null
        val period = orderPeriods.firstOrNull { it.id == periodId } ?: return null
        val today = GameCalendar.gameDate(now, refreshOf(period))
        val target = orderPeriods.filter { it.serviceCount != null }.sumOf { it.serviceCount ?: 0 }
            .takeIf { it > 0 }
        val completed = CountHosting.completedCount(sessions)
        val sessionIndex = when (state) {
            is CountHosting.ActiveCountState.ActiveSession -> state.sessionIndex
            is CountHosting.ActiveCountState.AwaitingSessionRow -> state.sessionIndex
            else -> null
        }
        val periodTasks = snapshot.tasks.filter { it.periodId == period.id }
        val occurrencesByTaskAndKey = snapshot.occurrences
            .filter { occurrence -> periodTasks.any { it.id == occurrence.taskDefinitionId } }
            .associateBy { it.taskDefinitionId to it.occurrenceKey }
        val tasks = if (sessionIndex == null) {
            emptyList()
        } else {
            val key = CountHosting.occurrenceKey(sessionIndex)
            val slot = OccurrenceSlot(
                key = key,
                attributedDate = today,
                windowStart = today,
                windowEnd = today,
            )
            periodTasks.map { task ->
                val model = task.toModel()
                val occurrence = occurrencesByTaskAndKey[task.id to key]
                TodayHostingTask(
                    task = model,
                    occurrenceId = occurrence?.id,
                    slot = slot,
                    completed = occurrence?.completedAt != null,
                    value = occurrence?.value,
                    windowLabel = null,
                )
            }.sortedBy { it.task.sortOrder }
        }
        return TodayHostingOrder(
            order = order.toModel(),
            ownerId = accountOwner.owner.id,
            ownerNumber = accountOwner.owner.toModel().displayNumber,
            ownerNickname = accountOwner.owner.nickname,
            accountId = accountOwner.account.id,
            gameName = accountOwner.account.gameName,
            server = accountOwner.account.server,
            period = period.toModel(),
            today = today,
            status = HostingStatus.ACTIVE,
            tasks = tasks,
            countBased = true,
            completedSessions = completed,
            targetSessions = target,
            activeSessionIndex = sessionIndex,
        )
    }

    private fun buildTodayShort(
        snapshot: TodayShortSnapshot,
        now: Instant,
    ): List<ShortWorkOrder> = snapshot.orders.mapNotNull { order ->
        val accountOwner = snapshot.context[order.accountId] ?: return@mapNotNull null
        val orderTasks = snapshot.tasks.filter { it.orderId == order.id }
        val today = GameCalendar.gameDate(now, refreshOf(order))
        ShortWorkOrder(
            order = order.toModel(),
            ownerId = accountOwner.owner.id,
            ownerNumber = accountOwner.owner.toModel().displayNumber,
            ownerNickname = accountOwner.owner.nickname,
            accountId = accountOwner.account.id,
            gameName = accountOwner.account.gameName,
            server = accountOwner.account.server,
            tasks = orderTasks.map { task ->
                val model = task.toModel()
                ShortWorkTask(
                    task = model,
                    completed = model.completedAt != null,
                    currentValue = model.currentValue,
                )
            }.sortedBy { it.task.sortOrder },
            today = today,
        )
    }.sortedWith(
        compareBy<ShortWorkOrder>(
            { !it.overdue },
            { it.order.deadlineDate ?: LocalDate.MAX },
            { it.order.createdAt },
        ),
    )

    override suspend fun createShortOrder(draft: ShortOrderDraft): String {
        require(draft.creationOperationId.isNotBlank()) { "建单操作标识不能为空" }
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.values.first() }
        val priceCents = requireNotNull(draft.priceCentsOrNull) { "请填写约定总价" }
        val now = PersistedInstant.normalize(ticker.now())
        val rules = draft.refresh ?: GameRefreshRules.provisional(ZoneId.systemDefault())
        val fingerprint = OrderCommandFingerprint.short(draft)

        return createOnce(draft.creationOperationId, OrderType.SHORT_TASK, fingerprint) {
            requireWritableAccount(draft.accountId, draft.ownerId)
            val orderId = UUID.randomUUID().toString()
            orderDao.insert(
                OrderEntity(
                    id = orderId,
                    accountId = draft.accountId,
                    type = OrderType.SHORT_TASK,
                    title = draft.title.trim(),
                    priceCents = priceCents,
                    paymentMode = draft.paymentMode,
                    deadlineDate = draft.deadlineDate,
                    notes = draft.notes.trim().ifBlank { null },
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                    sourceTemplateId = draft.sourceTemplate?.templateId,
                    sourceTemplateName = draft.sourceTemplate?.gameName,
                    sourceTemplateVersion = draft.sourceTemplate?.templateVersion,
                    monthlyRefreshDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                ),
            )
            draft.tasks.forEachIndexed { index, task ->
                taskDao.insert(
                    task.toEntity(
                        orderId = orderId,
                        periodId = null,
                        sortOrder = index,
                        now = now,
                    ),
                )
            }
            val receiptId = recordInitialPayment(
                orderId = orderId,
                periodId = null,
                draft = draft.initialPayment,
                now = now,
            )
            CreatedOrder(orderId = orderId, receiptId = receiptId)
        }
    }

    override suspend fun createHostingOrder(draft: HostingOrderDraft): String {
        require(draft.creationOperationId.isNotBlank()) { "建单操作标识不能为空" }
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.values.first() }
        val priceCents = requireNotNull(draft.priceCentsOrNull) { "请填写本期约定价格" }
        val now = PersistedInstant.normalize(ticker.now())
        val rules = draft.refresh
        val fingerprint = OrderCommandFingerprint.hosting(draft)

        return createOnce(draft.creationOperationId, OrderType.HOSTING, fingerprint) {
            requireWritableAccount(draft.accountId, draft.ownerId)
            val orderId = UUID.randomUUID().toString()
            val periodId = UUID.randomUUID().toString()
            val countBased = draft.hostingMode == HostingMode.COUNT_BASED
            orderDao.insert(
                OrderEntity(
                    id = orderId,
                    accountId = draft.accountId,
                    type = OrderType.HOSTING,
                    title = draft.title.trim(),
                    priceCents = null,
                    paymentMode = draft.paymentMode,
                    deadlineDate = null,
                    notes = draft.notes.trim().ifBlank { null },
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                    sourceTemplateId = draft.sourceTemplate?.templateId,
                    sourceTemplateName = draft.sourceTemplate?.gameName,
                    sourceTemplateVersion = draft.sourceTemplate?.templateVersion,
                    hostingMode = draft.hostingMode,
                    targetSessions = if (countBased) {
                        requireNotNull(draft.targetSessions) { "请填写打卡次数" }
                    } else {
                        null
                    },
                    monthlyRefreshDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                ),
            )
            // Count-based orders still own one period row: it is the immutable price and
            // refresh snapshot receipts attach to. Its dates are bookkeeping only (the
            // creation game date) and are never used for expiry, overdue or renewal;
            // progress lives in `hosting_sessions` and in `N:<round>` occurrences.
            val containerStart = if (countBased) {
                GameCalendar.gameDate(now, rules)
            } else {
                draft.startDate
            }
            orderDao.insertPeriod(
                HostingPeriodEntity(
                    id = periodId,
                    orderId = orderId,
                    periodIndex = 0,
                    startDate = containerStart,
                    endDate = if (countBased) containerStart else draft.effectiveEndDate,
                    priceCents = priceCents,
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    renewalOperationId = null,
                    serviceDays = if (countBased) null else draft.serviceDays,
                    serviceWeekdays = if (countBased) null else encodeWeekdays(draft.serviceWeekdays),
                    // 计次期次自己就是目标容器：期次目标来自本次约定，绝不由订单级
                    // targetSessions 在每个期次上重复解释。
                    serviceCount = if (countBased) draft.targetSessions else null,
                    monthlyRefreshDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                ),
            )
            if (countBased) {
                // Round 1 starts empty. No completed round is invented, so a template's
                // finished work can never leak into a fresh order.
                hostingSessionDao.insertIgnore(
                    HostingSessionEntity(
                        id = UUID.randomUUID().toString(),
                        orderId = orderId,
                        sessionIndex = 1,
                        createdAt = now,
                        completedAt = null,
                        completionBatchId = null,
                        periodId = periodId,
                    ),
                )
            }
            draft.tasks.forEachIndexed { index, task ->
                taskDao.insert(
                    task.toEntity(
                        orderId = orderId,
                        periodId = periodId,
                        sortOrder = index,
                        now = now,
                    ),
                )
            }
            val receiptId = recordInitialPayment(
                orderId = orderId,
                periodId = periodId,
                draft = draft.initialPayment,
                now = now,
            )
            CreatedOrder(orderId = orderId, receiptId = receiptId)
        }
    }

    private data class CreatedOrder(val orderId: String, val receiptId: String?)

    /**
     * Runs [create] and stores its result under [operationId] in the same transaction.
     *
     * A replay with the same fingerprint returns the stored order. A different payload
     * under an already-used id is rejected. The unique index on the operation id is the
     * final guard: if a concurrent transaction won the race, the losing transaction is
     * rolled back and re-reads the winner's committed result.
     */
    private suspend fun createOnce(
        operationId: String,
        type: OrderType,
        fingerprint: String,
        create: suspend () -> CreatedOrder,
    ): String = DatasetGate.withWrite {
        // The gate is entered before the transaction opens: a dataset replacement waits
        // for this command to finish instead of interleaving with it.
        val attempted = runCatching {
            database.withDatasetTransaction {
                existingCreationOrNull(operationId, type, fingerprint)?.let {
                    return@withDatasetTransaction it.orderId
                }
                val created = create()
                creationOperationDao.insert(
                    OrderCreationOperationEntity(
                        operationId = operationId,
                        orderType = type,
                        fingerprint = fingerprint,
                        orderId = created.orderId,
                        receiptId = created.receiptId,
                        createdAt = PersistedInstant.normalize(ticker.now()),
                    ),
                )
                created.orderId
            }
        }
        attempted.getOrElse { error ->
            // The insert may lose a race against an identical concurrent command. The
            // rollback removed this attempt's order/receipt; return the winner instead.
            val winner = runCatching { creationOperationDao.findByOperationId(operationId) }
                .getOrNull()
            if (winner != null) {
                require(winner.orderType == type && winner.fingerprint == fingerprint) {
                    CREATION_CONFLICT_MESSAGE
                }
                return@getOrElse winner.orderId
            }
            throw error
        }
    }

    private suspend fun existingCreationOrNull(
        operationId: String,
        type: OrderType,
        fingerprint: String,
    ): OrderCreationOperationEntity? {
        val existing = creationOperationDao.findByOperationId(operationId) ?: return null
        require(existing.orderType == type && existing.fingerprint == fingerprint) {
            CREATION_CONFLICT_MESSAGE
        }
        return existing
    }

    /**
     * Optional first receipt, written inside the creation transaction. The receipt uses
     * the user-entered **actual** receipt time (not the recording time) normalized to
     * the persisted precision. Any validation or insert failure aborts the whole
     * transaction, so a rejected payment can never leave a half-created order or period.
     *
     * Returns the created receipt id (or null) so the creation operation can store the
     * complete result of the command.
     */
    private suspend fun recordInitialPayment(
        orderId: String,
        periodId: String?,
        draft: com.daigan.record.data.model.InitialPaymentDraft?,
        now: Instant,
    ): String? {
        if (draft == null) return null
        val amountCents = Money.parsePositiveYuanToCents(draft.amountYuan)
        val receivedAt = draft.receivedAt?.let(PersistedInstant::normalize) ?: now
        val result = receiptLedger.record(
            PaymentRequest(
                orderId = orderId,
                periodId = periodId,
                amountCents = amountCents,
                receivedAt = receivedAt,
                channel = draft.channel,
                note = draft.note,
                operationId = draft.operationId,
            ),
            now = now,
        )
        return result.receipt.id
    }

    override suspend fun setShortTaskCompleted(taskId: String, completed: Boolean) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.retiredAt == null) { "任务已更正，请刷新后再操作" }
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.SHORT_TASK) { "只有短期任务可以单独勾选" }
            requireWritableAccount(order.accountId, null)
            val model = task.toModel()
            val newValue = when {
                completed && model.progressType == ProgressType.NUMERIC ->
                    maxOf(model.currentValue, model.targetValue)
                !completed && model.progressType == ProgressType.NUMERIC && model.reachedTarget ->
                    model.startValue
                else -> model.currentValue
            }
            taskDao.updateProgress(
                taskId = taskId,
                value = newValue,
                completedAt = if (completed) now else null,
                batchId = null,
                updatedAt = now,
            )
            refreshShortCompletion(order.id, now)
        }
    }

    override suspend fun updateShortTaskProgress(taskId: String, value: BigDecimal) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.retiredAt == null) { "任务已更正，请刷新后再操作" }
            require(task.progressType == ProgressType.NUMERIC) { "该任务不使用数值进度" }
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.SHORT_TASK) { "只有短期任务可以编辑进度" }
            requireWritableAccount(order.accountId, null)
            val reached = value >= task.targetValue
            val completedAt = when {
                reached -> task.completedAt ?: now
                else -> null
            }
            taskDao.updateProgress(
                taskId = taskId,
                value = value,
                completedAt = completedAt,
                batchId = null,
                updatedAt = now,
            )
            refreshShortCompletion(order.id, now)
        }
    }

    override suspend fun completeShortOrder(
        orderId: String,
        operationId: String,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.SHORT_TASK) { "只有短期订单可以整单完成" }
            requireWritableAccount(order.accountId, null)
            val tasks = taskDao.findForOrder(orderId)
            // 契约（UF-038）：整单完成不得给累计目标虚构数值；未达标的累计任务跳过，
            // 由用户在累计面板输入真实总值。跳过的数量通过 skippedCount 明确提示。
            val incomplete = tasks.filter {
                it.completedAt == null && it.retiredAt == null && !it.isCumulativeNumeric()
            }
            val batchId = UUID.randomUUID().toString()
            batchDao.insertBatch(
                CompletionBatchEntity(
                    id = batchId,
                    operationId = operationId,
                    scope = BatchScope.SHORT_ORDER,
                    targetId = orderId,
                    // 契约：HOSTING_ORDER 与 SHORT_ORDER 的批次必须有游戏日（只有横跨订单的
                    // HOSTING_ALL 才是 null）。此前这里恒写 null，导出的备份会被两端的
                    // 校验器整份拒绝（"缺少游戏日"），属于自产文件无法自恢复的缺陷。
                    gameDate = GameCalendar.gameDate(now, refreshOf(order)),
                    createdAt = now,
                    undoneAt = null,
                ),
            )
            val items = incomplete.map { task ->
                CompletionBatchItemEntity(
                    id = UUID.randomUUID().toString(),
                    batchId = batchId,
                    itemType = BatchItemType.SHORT_TASK,
                    occurrenceId = null,
                    taskDefinitionId = task.id,
                    previousCompletedAt = task.completedAt,
                    previousRecordedAt = null,
                    previousBackfilled = null,
                    previousValue = task.currentValue,
                    previousCompletionBatchId = task.completionBatchId,
                    appliedCompletedAt = now,
                    appliedRecordedAt = null,
                    appliedBackfilled = null,
                    appliedValue = if (task.progressType == ProgressType.NUMERIC) {
                        maxOf(task.currentValue, task.targetValue)
                    } else {
                        task.currentValue
                    },
                    appliedCompletionBatchId = batchId,
                )
            }
            if (items.isNotEmpty()) batchDao.insertItems(items)
            items.forEach { item ->
                taskDao.updateProgress(
                    taskId = checkNotNull(item.taskDefinitionId),
                    value = checkNotNull(item.appliedValue),
                    completedAt = now,
                    batchId = batchId,
                    updatedAt = now,
                )
            }
            refreshShortCompletion(orderId, now)
            BulkCompletionResult(
                batchId = batchId,
                completedCount = items.size,
                skippedCount = tasks.size - items.size,
                alreadyApplied = false,
            )
        }
    }

    /**
     * 完成态应落的值——正常打卡与历史补记共享的唯一语义：普通数值任务至少提到目标值，
     * 已记录的更高值不回落（缺失值以起点值代入），其余进度口径保持行内原值。
     * 撤销沿用正常路径的重置语义（值清空），不在此处定义。
     */
    private fun completedOccurrenceValue(
        task: TaskDefinitionEntity,
        occurrence: TaskOccurrenceEntity,
    ): BigDecimal? = if (task.progressType == ProgressType.NUMERIC) {
        maxOf(occurrence.value ?: task.startValue, task.targetValue)
    } else {
        occurrence.value
    }

    override suspend fun setOccurrenceCompleted(
        taskId: String,
        completed: Boolean,
        expectedOccurrenceKey: String?,
    ) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.retiredAt == null) { "任务已更正，请刷新后再操作" }
            val period = task.periodId?.let { orderDao.findPeriod(it) }
                ?: throw IllegalArgumentException("托管期次不存在")
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.HOSTING) { "只有托管任务使用打卡记录" }
            requireWritableAccount(order.accountId, null)
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)
            val model = task.toModel()
            val slot = if (order.hostingMode == HostingMode.COUNT_BASED) {
                countSlot(order, expectedOccurrenceKey, today)
            } else {
                OccurrenceRules.slotForNow(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    monthDay = model.monthDay,
                    monthDays = model.monthDays,
                    activeFrom = model.activeFrom,
                    activeUntil = model.activeUntil,
                    retiredAt = model.retiredAt,
                    serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    today = today,
                    rules = rules,
                    now = now,
                ) ?: throw IllegalArgumentException("当前没有可完成的托管任务")
            }
            if (expectedOccurrenceKey != null && expectedOccurrenceKey != slot.key) {
                throw StaleOccurrenceException("已跨过刷新时间，当前任务已变为 ${slot.key}，请刷新后重试")
            }
            val occurrence = ensureOccurrence(task, slot, now)
            if (completed) {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = now,
                    recordedAt = now,
                    backfilled = false,
                    batchId = null,
                    value = completedOccurrenceValue(task, occurrence),
                )
            } else {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = null,
                    recordedAt = null,
                    backfilled = false,
                    batchId = null,
                    value = null,
                )
            }
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                // Clearing a task of an already counted round reopens that round; the
                // total drops by one and the session row stays as history.
                val roundIndex = CountHosting.sessionIndexOrNull(slot.key)
                if (!completed && roundIndex != null) {
                    countSessions.reopenIfIncomplete(order.id, roundIndex, now)
                }
                countSessions.reconcile(orderId = order.id, now = now)
            }
        }
    }

    /**
     * Slot of the round being edited.
     *
     * The round index comes from the key the screen showed (so unchecking a task of the
     * round that was just completed reopens that round), otherwise from the shared
     * service's active round. Keys are `N:<index>`, so several rounds on the same day
     * stay distinct, and an explicit index is never silently re-targeted.
     */
    private suspend fun countSlot(
        order: OrderEntity,
        expectedOccurrenceKey: String?,
        today: LocalDate,
    ): OccurrenceSlot {
        val index = expectedOccurrenceKey?.let(CountHosting::sessionIndexOrNull)
        if (index != null) {
            // An explicit index must be either an existing row or the exact allocated
            // (not yet written) active index the screen showed. Anything else is a stale
            // or foreign round and is never redirected.
            val round = countSessions.roundFor(order.id, index)
            if (round != null) {
                return OccurrenceSlot(
                    key = round.key,
                    attributedDate = today,
                    windowStart = today,
                    windowEnd = today,
                )
            }
            val active = countSessions.activeRound(order.id)
            if (active != null && active.sessionIndex == index && active.awaitingRow) {
                return OccurrenceSlot(
                    key = active.key,
                    attributedDate = today,
                    windowStart = today,
                    windowEnd = today,
                )
            }
            throw IllegalArgumentException("打卡轮次不存在或不属于该订单")
        }
        val round = countSessions.activeRound(order.id)
            ?: throw IllegalArgumentException("该打卡式托管已完成约定次数")
        return OccurrenceSlot(
            key = round.key,
            attributedDate = today,
            windowStart = today,
            windowEnd = today,
        )
    }

    override suspend fun updateOccurrenceProgress(
        taskId: String,
        value: BigDecimal,
        expectedOccurrenceKey: String?,
    ) {
        val now = ticker.now()
        database.withDatasetTransaction {
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.retiredAt == null) { "任务已更正，请刷新后再操作" }
            require(task.progressType == ProgressType.NUMERIC) { "该任务不使用数值进度" }
            val period = task.periodId?.let { orderDao.findPeriod(it) }
                ?: throw IllegalArgumentException("托管期次不存在")
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)
            val model = task.toModel()
            val slot = if (order.hostingMode == HostingMode.COUNT_BASED) {
                countSlot(order, expectedOccurrenceKey, today)
            } else {
                OccurrenceRules.slotForNow(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    monthDay = model.monthDay,
                    monthDays = model.monthDays,
                    activeFrom = model.activeFrom,
                    activeUntil = model.activeUntil,
                    retiredAt = model.retiredAt,
                    serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    today = today,
                    rules = rules,
                    now = now,
                ) ?: throw IllegalArgumentException("当前没有可记录的托管任务")
            }
            if (expectedOccurrenceKey != null && expectedOccurrenceKey != slot.key) {
                throw StaleOccurrenceException("已跨过刷新时间，当前任务已变为 ${slot.key}，请刷新后重试")
            }
            val occurrence = ensureOccurrence(task, slot, now)
            val reached = value >= model.targetValue
            occurrenceDao.updateCompletion(
                id = occurrence.id,
                completedAt = if (reached) occurrence.completedAt ?: now else null,
                recordedAt = if (reached) occurrence.recordedAt ?: now else null,
                backfilled = false,
                batchId = null,
                value = value,
            )
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                val roundIndex = CountHosting.sessionIndexOrNull(slot.key)
                if (!reached && roundIndex != null) {
                    countSessions.reopenIfIncomplete(order.id, roundIndex, now)
                }
                countSessions.reconcile(orderId = order.id, now = now)
            }
        }
    }

    override suspend fun completeHostingOrder(
        orderId: String,
        operationId: String,
        displayed: DisplayedHostingSnapshot,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(displayed.orderId == orderId) { "展示快照与订单不一致" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.HOSTING) { "只有托管订单可以整单完成" }
            requireWritableAccount(order.accountId, null)
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                val round = countSessions.activeRound(orderId)
                    ?: throw IllegalArgumentException("该订单没有可完成的打卡轮次")
                assertCountSnapshotCurrent(displayed, order, round)
                val changes = collectPendingForCountRound(round, now)
                val result = commitBatch(
                    scope = BatchScope.HOSTING_ORDER,
                    targetId = orderId,
                    gameDate = GameCalendar.gameDate(now, refreshOf(round.period)),
                    operationId = operationId,
                    changes = changes,
                    now = now,
                )
                // One round counts once, and only when every due task of it is done.
                countSessions.reconcile(orderId, now)
                // 跳过的累计目标必须让用户看到，而不是静默吞掉。
                return@withDatasetTransaction result.copy(
                    skippedCount = pendingCumulativeCount(round.period.id),
                )
            }
            // Bulk completion belongs to the period containing today, never to a future
            // period created by an early renewal.
            val period = activePeriodForToday(orderDao.findPeriods(orderId), now)
                ?: throw IllegalArgumentException("该订单今天没有进行中的托管期次")
            assertSnapshotCurrent(displayed, period, now)
            val changes = collectPendingForOrder(period, now)
            commitBatch(
                scope = BatchScope.HOSTING_ORDER,
                targetId = orderId,
                gameDate = GameCalendar.gameDate(now, refreshOf(period)),
                operationId = operationId,
                changes = changes,
                now = now,
            ).copy(skippedCount = pendingCumulativeCount(period.id))
        }
    }

    override suspend fun completeAllHosting(
        operationId: String,
        displayed: List<DisplayedHostingSnapshot>,
    ): BulkCompletionResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            batchDao.findByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction BulkCompletionResult(
                    batchId = existing.id,
                    completedCount = batchDao.findItems(existing.id).size,
                    skippedCount = 0,
                    alreadyApplied = true,
                )
            }
            val changes = mutableListOf<PendingOccurrence>()
            val countBasedOrders = mutableListOf<String>()
            var skippedCumulative = 0
            orderDao.findActiveHostingOrders().forEach { order ->
                val snapshot = displayed.firstOrNull { it.orderId == order.id } ?: return@forEach
                if (order.hostingMode == HostingMode.COUNT_BASED) {
                    val round = countSessions.activeRound(order.id) ?: return@forEach
                    assertCountSnapshotCurrent(snapshot, order, round)
                    changes += collectPendingForCountRound(round, now)
                    skippedCumulative += pendingCumulativeCount(round.period.id)
                    countBasedOrders += order.id
                    return@forEach
                }
                // Same rule as a single order: only the period containing today has work.
                val period = activePeriodForToday(orderDao.findPeriods(order.id), now) ?: return@forEach
                assertSnapshotCurrent(snapshot, period, now)
                changes += collectPendingForOrder(period, now)
                skippedCumulative += pendingCumulativeCount(period.id)
            }
            val result = commitBatch(
                scope = BatchScope.HOSTING_ALL,
                targetId = null,
                gameDate = null,
                operationId = operationId,
                changes = changes,
                now = now,
            )
            countBasedOrders.forEach { orderId ->
                countSessions.reconcile(orderId, now)
            }
            result.copy(skippedCount = skippedCumulative)
        }
    }

    override suspend fun reopenCountSession(orderId: String, sessionIndex: Int, expectedGeneration: Long?) {
        val now = PersistedInstant.normalize(ticker.now())
        database.withDatasetTransaction {
            DatasetExpectation.requireCurrent(expectedGeneration)
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.hostingMode == HostingMode.COUNT_BASED) { "该订单不是打卡式托管" }
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            val session = hostingSessionDao.findByIndex(orderId, sessionIndex)
                ?: throw IllegalArgumentException("打卡轮次不存在")
            if (session.completedAt == null) return@withDatasetTransaction
            val round = countSessions.roundFor(orderId, sessionIndex)
                ?: throw IllegalArgumentException("打卡轮次不存在或不属于该订单")
            // A cumulative round is carried by append-only events, never by occurrence
            // rows: reopening it without retracting the event would immediately derive
            // "complete" again. The honest correction path is the event history. The check
            // uses the round's *historical due set* (F2), so a task retired after that round
            // cannot hide the cumulative target that still governs it.
            if (round.dueTasks.any { it.isCumulativeNumeric() }) {
                throw IllegalArgumentException(
                    "该轮次包含累计目标，请在累计历史的「更正/撤销」中处理对应记录",
                )
            }
            // Reopen means "this round did not count": its task completions are cleared so
            // the round can be redone, while the session row itself stays as history.
            val key = CountHosting.occurrenceKey(sessionIndex)
            occurrenceDao.findForOrder(orderId)
                .filter { it.occurrenceKey == key }
                .forEach { occurrence ->
                    occurrenceDao.updateCompletion(
                        id = occurrence.id,
                        completedAt = null,
                        recordedAt = null,
                        backfilled = false,
                        batchId = null,
                        value = null,
                    )
                }
            hostingSessionDao.reopen(orderId, sessionIndex)
            if (order.completedAt != null) {
                orderDao.updateCompletedAt(orderId, null, now)
            }
            // Re-derives the active round/caches from the reopened row without touching
            // any later round or event.
            countSessions.reconcile(orderId, now)
        }
    }

    /**
     * H1: correct the historical completion of a task that was removed later.
     *
     * The round is the identity, so the write is attributed to the round's **own** game
     * date rather than to today; a retirement after that date leaves the responsibility
     * intact, and nothing after the retirement date can be recorded. The row is kept (a
     * retraction only clears the completion) and the shared service re-derives the round,
     * the order total and every cache.
     */
    override suspend fun setHistoricalRoundCompletion(
        orderId: String,
        sessionIndex: Int,
        taskId: String,
        completed: Boolean,
        expectedGeneration: Long?,
    ) {
        val now = PersistedInstant.normalize(ticker.now())
        database.withDatasetTransaction {
            DatasetExpectation.requireCurrent(expectedGeneration)
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING && order.hostingMode == HostingMode.COUNT_BASED) {
                "只有打卡式托管按轮次记录"
            }
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.orderId == orderId) { "该任务不属于此订单" }
            val retiredAt = requireNotNull(task.retiredAt) { "该任务仍在当前打卡列表，请直接打卡" }
            require(!task.isCumulativeNumeric()) {
                "累计任务请用「累计历史」更正对应记录，不能直接勾选"
            }
            // Only an already materialised round: a not-yet-written index has no history
            // to correct and must not be created by a correction.
            val round = countSessions.roundFor(orderId, sessionIndex)
                ?: throw IllegalArgumentException("打卡轮次不存在")
            require(round.period.orderId == orderId) { "该轮次不属于此订单" }
            require(task.periodId == round.period.id) { "该任务不属于该轮次的期次" }
            val retirementDate = GameCalendar.gameDate(retiredAt, refreshOf(round.period))
            require(
                CountHosting.isDueInRound(
                    date = round.attributedDate,
                    retirementGameDate = retirementDate,
                    activeFrom = task.activeFrom,
                    activeUntil = task.activeUntil,
                    recurrence = task.recurrence,
                    cumulative = false,
                ),
            ) {
                "该任务在本次轮次（${round.attributedDate}）已不应做，不能补记"
            }
            val key = CountHosting.occurrenceKey(sessionIndex)
            val existing = occurrenceDao.findByKey(taskId, key)
            if (completed) {
                require(existing?.completedAt == null) { "该轮次已有完成记录" }
                val occurrence = ensureOccurrence(
                    task = task,
                    slot = OccurrenceSlot(
                        key = key,
                        attributedDate = round.attributedDate,
                        windowStart = round.attributedDate,
                        windowEnd = round.attributedDate,
                    ),
                    now = now,
                )
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = now,
                    recordedAt = now,
                    backfilled = false,
                    batchId = null,
                    // Same completion-value semantics as the normal path: an ordinary
                    // numeric row completes at least at its target and never loses a
                    // higher recorded value; undo (below) resets to null like the
                    // normal path.
                    value = completedOccurrenceValue(task, occurrence),
                )
            } else {
                require(existing?.completedAt != null) { "该轮次没有可撤销的记录" }
                occurrenceDao.updateCompletion(
                    id = existing.id,
                    completedAt = null,
                    recordedAt = null,
                    backfilled = false,
                    batchId = null,
                    value = null,
                )
            }
            // Same derivation as every other write: round completion, order total, caches.
            countSessions.reconcile(orderId, now)
        }
    }

    override suspend fun undoBatch(batchId: String): UndoResult {
        val now = ticker.now()
        return database.withDatasetTransaction {
            val batch = batchDao.findById(batchId) ?: throw IllegalArgumentException("找不到该批量操作")
            if (batch.undoneAt != null) {
                return@withDatasetTransaction UndoResult(0, emptyList(), alreadyUndone = true)
            }
            val items = batchDao.findItems(batchId)
            // Undo is a write too: a stale screen must not mutate archived records.
            items.mapNotNull { it.taskDefinitionId }.distinct().forEach { taskId ->
                assertTaskWritable(taskId)
            }
            val conflicts = mutableListOf<UndoConflict>()
            var restored = 0
            val affectedShortOrders = mutableSetOf<String>()

            items.forEach { item ->
                when (item.itemType) {
                    BatchItemType.OCCURRENCE -> {
                        val occurrence = item.occurrenceId?.let { occurrenceDao.findById(it) }
                        if (occurrence == null) {
                            conflicts += UndoConflict(
                                description = "打卡记录",
                                reason = "记录已不存在",
                            )
                            return@forEach
                        }
                        val task = item.taskDefinitionId?.let { taskDao.findById(it) }
                        val label = "${task?.title ?: "打卡记录"} · ${occurrence.gameDate}"
                        val unchanged = occurrence.completedAt == item.appliedCompletedAt &&
                            occurrence.value == item.appliedValue &&
                            occurrence.completionBatchId == item.appliedCompletionBatchId
                        if (!unchanged) {
                            conflicts += UndoConflict(
                                description = label,
                                reason = "该记录在此批量完成后又被修改，已保留最新结果",
                            )
                            return@forEach
                        }
                        occurrenceDao.updateCompletion(
                            id = occurrence.id,
                            completedAt = item.previousCompletedAt,
                            recordedAt = item.previousRecordedAt,
                            backfilled = item.previousBackfilled ?: false,
                            batchId = item.previousCompletionBatchId,
                            value = item.previousValue,
                        )
                        restored += 1
                    }

                    BatchItemType.SHORT_TASK -> {
                        val taskId = item.taskDefinitionId
                        val task = taskId?.let { taskDao.findById(it) }
                        if (task == null) {
                            conflicts += UndoConflict(
                                description = "短期任务",
                                reason = "任务已不存在",
                            )
                            return@forEach
                        }
                        val unchanged = task.completedAt == item.appliedCompletedAt &&
                            task.currentValue == item.appliedValue &&
                            task.completionBatchId == item.appliedCompletionBatchId
                        if (!unchanged) {
                            conflicts += UndoConflict(
                                description = task.title,
                                reason = "该任务在此批量完成后又被修改，已保留最新结果",
                            )
                            return@forEach
                        }
                        taskDao.updateProgress(
                            taskId = task.id,
                            value = item.previousValue ?: task.currentValue,
                            completedAt = item.previousCompletedAt,
                            batchId = item.previousCompletionBatchId,
                            updatedAt = now,
                        )
                        affectedShortOrders += task.orderId
                        restored += 1
                    }
                }
            }

            affectedShortOrders.forEach { orderId -> refreshShortCompletion(orderId, now) }
            // Undo reopens exactly the count-based rounds whose work was restored; the
            // round rows stay as history, the total drops accordingly, and the order is
            // no longer "completed" unless the target is still reached.
            val restoredRounds = items.mapNotNull { item ->
                if (item.itemType != BatchItemType.OCCURRENCE) return@mapNotNull null
                val occurrence = item.occurrenceId?.let { occurrenceDao.findById(it) }
                    ?: return@mapNotNull null
                val index = CountHosting.sessionIndexOrNull(occurrence.occurrenceKey)
                    ?: return@mapNotNull null
                val orderId = item.taskDefinitionId?.let { taskDao.findById(it)?.orderId }
                    ?: return@mapNotNull null
                orderId to index
            }.distinct()
            restoredRounds.forEach { (orderId, index) ->
                countSessions.reopenIfIncomplete(orderId, index, now)
            }
            restoredRounds.map { it.first }.distinct().forEach { orderId ->
                countSessions.reconcile(orderId, now)
            }
            batchDao.markUndone(batchId, now)
            UndoResult(restored, conflicts, alreadyUndone = false)
        }
    }

    override suspend fun setHistoricalCompletion(
        orderId: String,
        periodId: String,
        taskId: String,
        date: LocalDate,
        completed: Boolean,
        weeklyWindowStart: LocalDate?,
    ): Boolean {
        val now = ticker.now()
        return database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.HOSTING) { "只有托管订单可以补录" }
            // Count-based work is identified by its round, not by a date, so a dated
            // backfill would corrupt the total; the round itself is the correction path.
            require(order.hostingMode != HostingMode.COUNT_BASED) {
                "打卡式托管按轮次记录，请直接在当前轮次打卡或撤销"
            }
            requireWritableAccount(order.accountId, null)
            val period = orderDao.findPeriod(periodId)
                ?: throw IllegalArgumentException("托管期次不存在")
            require(period.orderId == orderId) { "该期次不属于此订单" }
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.orderId == orderId && task.periodId == periodId) { "该任务不属于此订单期次" }
            val rules = refreshOf(period)
            val today = GameCalendar.gameDate(now, rules)

            // Temporal validity is enforced before any occurrence row can be created.
            require(!today.isBefore(period.startDate)) { "该托管期次尚未开始，不能记录完成" }
            require(!date.isAfter(today)) { "不能为未来游戏日期补录：$date 尚未开始" }
            require(!date.isBefore(period.startDate)) { "所选日期不在托管期内" }

            val model = task.toModel()
            val slot = if (model.recurrence == RecurrenceType.WEEKLY && weeklyWindowStart != null) {
                OccurrenceRules.slotForWeeklyWindow(
                    serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                    activeFrom = model.activeFrom, retiredAt = model.retiredAt,
                    windowStart = weeklyWindowStart,
                    date = date,
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    rules = rules,
                ) ?: throw IllegalArgumentException("所选游戏周与该日期不匹配")
            } else {
                OccurrenceRules.slotForDate(
                    recurrence = model.recurrence,
                    weekdays = model.weekdays,
                    everyNDays = model.everyNDays,
                    monthDay = model.monthDay,
                    monthDays = model.monthDays,
                    activeFrom = model.activeFrom,
                    activeUntil = model.activeUntil,
                    retiredAt = model.retiredAt,
                    serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                    periodStart = period.startDate,
                    periodEnd = period.endDate,
                    date = date,
                    rules = rules,
                ) ?: throw IllegalArgumentException("所选日期没有该任务，或不在托管期内")
            }
            if (model.recurrence == RecurrenceType.WEEKLY) {
                val window = slot.weeklyWindowStart
                    ?.let { GameCalendar.weeklyWindowStartingOn(it, rules) }
                require(window == null || !window.startInstant.isAfter(now)) {
                    "该游戏周尚未开始，不能提前记录"
                }
            }
            val occurrence = ensureOccurrence(task, slot, now)
            val backfilled = date.isBefore(today)
            if (completed) {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = now,
                    recordedAt = now,
                    backfilled = backfilled,
                    batchId = null,
                    value = if (model.progressType == ProgressType.NUMERIC) {
                        model.targetValue
                    } else {
                        occurrence.value
                    },
                )
            } else {
                occurrenceDao.updateCompletion(
                    id = occurrence.id,
                    completedAt = null,
                    recordedAt = null,
                    backfilled = backfilled,
                    batchId = null,
                    value = null,
                )
            }
            true
        }
    }

    override suspend fun renewHosting(
        orderId: String,
        operationId: String,
        durationDays: Int,
        priceCents: Long,
    ): RenewalResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(durationDays > 0) { "续期天数必须大于 0" }
        require(priceCents >= 0) { "续期价格不能为负数" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            orderDao.findPeriodByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction RenewalResult(
                    periodId = existing.id,
                    periodIndex = existing.periodIndex,
                    startDate = existing.startDate,
                    endDate = existing.endDate,
                    alreadyApplied = true,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.type == OrderType.HOSTING) { "只有托管订单可以续期" }
            requireWritableAccount(order.accountId, null)
            // Count-based renewal appends a new count period instead of inventing dates.
            // `durationDays` is reused as the agreed number of rounds for this old entry
            // point; the dedicated command exists for the explicit UI.
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                return@withDatasetTransaction renewCountPeriod(
                    order = order,
                    operationId = operationId,
                    targetSessions = durationDays,
                    priceCents = priceCents,
                    now = now,
                )
            }
            val periods = orderDao.findPeriods(orderId)
            require(periods.isNotEmpty()) { "托管期次不存在" }
            // Renewals always continue from the last *scheduled* period. Using the
            // currently effective period instead would overlap an already renewed
            // future period when the user renews twice.
            val lastScheduled = HostingPeriodSelection.latest(periods) { it.periodIndex }
                ?: throw IllegalArgumentException("托管期次不存在")
            // The order row carries the user's current refresh intent (it is what the
            // edit dialog shows and what `updateOrderRefresh` keeps in sync with the
            // scoped period snapshots). A new period starts from that intent.
            val rules = refreshOf(order)
            // "Has the last period ended?" is a question about that period, so it is
            // answered with that period's own snapshot. The two can only differ on an
            // order whose rules were edited after its final period ended.
            val today = GameCalendar.gameDate(now, refreshOf(lastScheduled))
            // Early renewal starts the day after the last scheduled period, while
            // post-expiry renewal starts on the current game date.
            val nextStartDate = HostingPeriodSelection.nextStartDate(lastScheduled.endDate, today)
            val nextIndex = lastScheduled.periodIndex + 1
            val endDate = if (lastScheduled.serviceDays != null) {
                GameCalendar.hostingEndDateForWeekdays(nextStartDate, durationDays, parseWeekdays(lastScheduled.serviceWeekdays))
            } else GameCalendar.hostingEndDate(nextStartDate, durationDays)
            orderDao.findPeriodByIndex(orderId, nextIndex)?.let { existing ->
                // Only this command's own retry may be reported as already applied. A
                // period created by another command means the screen is stale; silently
                // returning it would hide a different renewal from the user.
                if (existing.renewalOperationId == operationId) {
                    return@withDatasetTransaction RenewalResult(
                        periodId = existing.id,
                        periodIndex = existing.periodIndex,
                        startDate = existing.startDate,
                        endDate = existing.endDate,
                        alreadyApplied = true,
                    )
                }
                throw IllegalArgumentException("该订单已有后续期次，请刷新后再续期")
            }
            val newPeriodId = UUID.randomUUID().toString()
            orderDao.insertPeriod(
                HostingPeriodEntity(
                    id = newPeriodId,
                    orderId = orderId,
                    periodIndex = nextIndex,
                    startDate = nextStartDate,
                    endDate = endDate,
                    priceCents = priceCents,
                    gameZoneId = rules.zoneId.id,
                    dailyRefreshMinutes = rules.dailyRefreshMinutes,
                    weeklyRefreshDay = rules.weeklyRefreshDayIso,
                    weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                    createdAt = now,
                    renewalOperationId = operationId,
                    serviceDays = if (lastScheduled.serviceDays != null) durationDays else null,
                    serviceWeekdays = lastScheduled.serviceWeekdays,
                    monthlyRefreshDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                ),
            )
            // Task snapshots come from the last scheduled period, so appending a second
            // future period copies the same task set instead of an empty one.
            taskDao.findForPeriod(lastScheduled.id).filter { it.retiredAt == null }.forEach { task ->
                taskDao.insert(
                    task.copy(
                        id = UUID.randomUUID().toString(),
                        periodId = newPeriodId,
                        currentValue = task.startValue,
                        completedAt = null,
                        completionBatchId = null,
                        createdAt = now,
                        updatedAt = now,
                        activeFrom = null,
                    ),
                )
            }
            orderDao.touch(orderId, now)
            // The persisted dates are what the confirmation preview must have shown.
            RenewalResult(
                periodId = newPeriodId,
                periodIndex = nextIndex,
                startDate = nextStartDate,
                endDate = endDate,
                alreadyApplied = false,
            )
        }
    }

    override suspend fun renewCountHosting(
        orderId: String,
        operationId: String,
        targetSessions: Int,
        priceCents: Long,
        expectedGeneration: Long?,
    ): RenewalResult {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(targetSessions in 1..CountHosting.MAX_TARGET_SESSIONS) {
            "打卡次数需为 1–${CountHosting.MAX_TARGET_SESSIONS} 的整数"
        }
        require(priceCents >= 0) { "续期价格不能为负数" }
        val now = ticker.now()
        return database.withDatasetTransaction {
            DatasetExpectation.requireCurrent(expectedGeneration)
            orderDao.findPeriodByOperationId(operationId)?.let { existing ->
                return@withDatasetTransaction RenewalResult(
                    periodId = existing.id,
                    periodIndex = existing.periodIndex,
                    startDate = existing.startDate,
                    endDate = existing.endDate,
                    alreadyApplied = true,
                    countBased = true,
                    targetSessions = existing.serviceCount,
                )
            }
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.type == OrderType.HOSTING && order.hostingMode == HostingMode.COUNT_BASED) {
                "该订单不是打卡式托管"
            }
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            renewCountPeriod(order, operationId, targetSessions, priceCents, now)
        }
    }

    /**
     * Appends one count period: its own target, price, refresh snapshot and task
     * definitions. The dates are the same internal anchor on both ends (never a service
     * deadline), previous periods/rounds/events stay untouched, and no session row is
     * created for the new period until real work is recorded.
     */
    private suspend fun renewCountPeriod(
        order: OrderEntity,
        operationId: String,
        targetSessions: Int,
        priceCents: Long,
        now: Instant,
    ): RenewalResult {
        require(targetSessions in 1..CountHosting.MAX_TARGET_SESSIONS) {
            "打卡次数需为 1–${CountHosting.MAX_TARGET_SESSIONS} 的整数"
        }
        require(priceCents >= 0) { "续期价格不能为负数" }
        val periods = orderDao.findPeriods(order.id)
        val lastScheduled = HostingPeriodSelection.latest(periods) { it.periodIndex }
            ?: throw IllegalArgumentException("托管期次不存在")
        val nextIndex = lastScheduled.periodIndex + 1
        orderDao.findPeriodByIndex(order.id, nextIndex)?.let { existing ->
            if (existing.renewalOperationId == operationId) {
                return RenewalResult(
                    periodId = existing.id,
                    periodIndex = existing.periodIndex,
                    startDate = existing.startDate,
                    endDate = existing.endDate,
                    alreadyApplied = true,
                    countBased = true,
                    targetSessions = existing.serviceCount,
                )
            }
            throw IllegalArgumentException("该订单已有后续期次，请刷新后再续期")
        }
        val rules = refreshOf(order)
        // No calendar deadline exists in this mode. The date pair is a unique internal
        // anchor only (the schema also enforces (orderId, startDate) uniqueness), so two
        // renewals on the same day get distinct anchors; neither end is a service limit.
        val anchor = maxOf(
            GameCalendar.gameDate(now, rules),
            lastScheduled.startDate.plusDays(1),
        )
        val newPeriodId = UUID.randomUUID().toString()
        orderDao.insertPeriod(
            HostingPeriodEntity(
                id = newPeriodId,
                orderId = order.id,
                periodIndex = nextIndex,
                startDate = anchor,
                endDate = anchor,
                priceCents = priceCents,
                gameZoneId = rules.zoneId.id,
                dailyRefreshMinutes = rules.dailyRefreshMinutes,
                weeklyRefreshDay = rules.weeklyRefreshDayIso,
                weeklyRefreshMinutes = rules.weeklyRefreshMinutes,
                createdAt = now,
                renewalOperationId = operationId,
                serviceDays = null,
                serviceWeekdays = null,
                monthlyRefreshDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                serviceCount = targetSessions,
            ),
        )
        // Task snapshots come from the last scheduled period, so the new agreement keeps
        // the same work items with fresh progress. Its cumulative scopes start empty.
        taskDao.findForPeriod(lastScheduled.id).filter { it.retiredAt == null }.forEach { task ->
            taskDao.insert(
                task.copy(
                    id = UUID.randomUUID().toString(),
                    periodId = newPeriodId,
                    currentValue = task.startValue,
                    completedAt = null,
                    completionBatchId = null,
                    createdAt = now,
                    updatedAt = now,
                    activeFrom = null,
                ),
            )
        }
        // The new period has the highest periodIndex, so `orders.targetSessions` must mirror
        // its target immediately (F3). The mirror never governs other periods; it exists for
        // single-period tools and the backup shape.
        orderDao.updateTargetSessions(order.id, targetSessions, now)
        // A new period adds unmet work, so an order that looked complete must reopen.
        if (order.completedAt != null) {
            orderDao.updateCompletedAt(order.id, null, now)
        }
        orderDao.touch(order.id, now)
        // Receipts are recorded against this new period id by the normal payment flow;
        // no money row is invented here.
        return RenewalResult(
            periodId = newPeriodId,
            periodIndex = nextIndex,
            startDate = anchor,
            endDate = anchor,
            alreadyApplied = false,
            countBased = true,
            targetSessions = targetSessions,
        )
    }

    override suspend fun updateOrderDates(
        orderId: String, periodId: String?, startDate: LocalDate?, endDate: LocalDate?,
    ) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            // This mode has no business dates at all; editing them would invent terms.
            require(order.hostingMode != HostingMode.COUNT_BASED) { "打卡式托管没有起止日期，不能修改日期" }
            requireWritableAccount(order.accountId, null)
            val now = ticker.now()
            if (order.type == OrderType.SHORT_TASK) {
                require(periodId == null && startDate == null) { "短期单只需修改截止日期" }
                orderDao.updateDeadline(orderId, endDate, now)
            } else {
                val periods = orderDao.findPeriods(orderId)
                val period = periods.firstOrNull { it.id == periodId } ?: error("托管期次不存在")
                val start = requireNotNull(startDate) { "请选择开始日期" }
                val end = requireNotNull(endDate) { "请选择结束日期" }
                require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
                require(java.time.temporal.ChronoUnit.DAYS.between(start, end) < HostingOrderDraft.MAX_DURATION_DAYS) {
                    "托管天数最多 ${HostingOrderDraft.MAX_DURATION_DAYS} 天"
                }
                require(periods.filter { it.id != period.id }.none {
                    !end.isBefore(it.startDate) && !start.isAfter(it.endDate)
                }) { "修改后的日期不能与其他期次重叠" }
                require(periods.filter { it.periodIndex < period.periodIndex }.all { it.endDate.isBefore(start) } &&
                    periods.filter { it.periodIndex > period.periodIndex }.all { it.startDate.isAfter(end) }) {
                    "修改后的日期必须保持期次先后顺序"
                }
                val tasks = taskDao.findForOrder(orderId).filter { it.periodId == period.id }
                val taskIds = tasks.map { it.id }.toSet()
                val occurrences = occurrenceDao.findForOrder(orderId).filter { it.taskDefinitionId in taskIds }
                // Even undone/partial occurrences are history. Never orphan their dates,
                // shift every-N anchors, or rewrite persisted weekly window metadata.
                require(occurrences.isEmpty() || start == period.startDate) {
                    "本期已有打卡或进度记录，不能修改开始日期；可修改结束日期"
                }
                require(occurrences.all { !it.gameDate.isBefore(start) && !it.gameDate.isAfter(end) &&
                    (it.windowEnd == null || !it.windowEnd.isAfter(end)) }) {
                    "修改后的日期会排除已有打卡或进度记录，请保留这些服务日期"
                }
                if (period.serviceDays != null) {
                    val weekdays = parseWeekdays(period.serviceWeekdays)
                    val count = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.count { it.dayOfWeek in weekdays }
                    require(count in 1..HostingOrderDraft.MAX_DURATION_DAYS && end.dayOfWeek in weekdays) { "累计服务期结束日期需是服务日，且至少保留一天" }
                    orderDao.updateServiceDays(period.id, count)
                }
                orderDao.updatePeriodDates(period.id, start, end)
                orderDao.touch(orderId, now)
            }
        }
    }

    override suspend fun updateUnusedOrderIdentity(
        orderId: String, accountId: String, type: OrderType, start: LocalDate, end: LocalDate?,
    ) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能更正所属账号或类型" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            // Changing the identity of a count-based order would drop its container
            // period while its rounds belong to the same order; the mode is persistent.
            require(order.hostingMode != HostingMode.COUNT_BASED) {
                "打卡式托管不能移动所属账号或改为其他订单类型；请作废后重新建单"
            }
            requireWritableAccount(order.accountId, null)
            requireWritableAccount(accountId, null)
            val tasks = taskDao.findForOrder(orderId)
            val periods = orderDao.findPeriods(orderId)
            require(order.completedAt == null && periods.size <= 1 &&
                receiptDao.findForOrder(orderId).isEmpty() && occurrenceDao.findForOrder(orderId).isEmpty() &&
                attachmentDao.findForOrder(orderId).isEmpty() && orderDao.countBatchReferences(orderId) == 0 &&
                orderDao.findPriceChanges(orderId).isEmpty() &&
                tasks.all { it.completedAt == null && it.completionBatchId == null && it.currentValue.compareTo(it.startValue) == 0 }) {
                "订单已有收款、工作、续期或价格更正记录，不能移动所属账号或改变类型；请保留历史并使用其他更正入口"
            }
            val now = ticker.now()
            val previousPeriod = periods.singleOrNull()
            val price = if (order.type == OrderType.HOSTING) requireNotNull(previousPeriod) { "托管期次不存在" }.priceCents
                else requireNotNull(order.priceCents) { "订单价格不存在" }
            if (type == OrderType.HOSTING) {
                val finalEnd = requireNotNull(end) { "请选择托管结束日期" }
                require(!finalEnd.isBefore(start)) { "结束日期不能早于开始日期" }
                require(java.time.temporal.ChronoUnit.DAYS.between(start, finalEnd) < HostingOrderDraft.MAX_DURATION_DAYS * 7L) { "服务日期跨度过长" }
                val serviceDays = previousPeriod?.serviceDays?.let {
                    val weekdays = parseWeekdays(previousPeriod.serviceWeekdays)
                    val count = generateSequence(start) { date -> date.plusDays(1) }
                        .takeWhile { date -> !date.isAfter(finalEnd) }.count { date -> date.dayOfWeek in weekdays }
                    require(count in 1..HostingOrderDraft.MAX_DURATION_DAYS && finalEnd.dayOfWeek in weekdays) { "累计服务期结束日期需是服务日，且至少保留一天" }
                    count
                }
                require(serviceDays != null || java.time.temporal.ChronoUnit.DAYS.between(start, finalEnd) < HostingOrderDraft.MAX_DURATION_DAYS) { "托管天数最多 ${HostingOrderDraft.MAX_DURATION_DAYS} 天" }
                val periodId = previousPeriod?.id ?: UUID.randomUUID().toString()
                if (previousPeriod == null) {
                    orderDao.insertPeriod(HostingPeriodEntity(
                        id = periodId, orderId = orderId, periodIndex = 0, startDate = start, endDate = finalEnd,
                        priceCents = price, gameZoneId = order.gameZoneId, dailyRefreshMinutes = order.dailyRefreshMinutes,
                        weeklyRefreshDay = order.weeklyRefreshDay, weeklyRefreshMinutes = order.weeklyRefreshMinutes,
                        createdAt = now, renewalOperationId = null,
                    ))
                    tasks.forEach { task -> taskDao.updateTask(task.copy(
                        periodId = periodId, recurrence = if (task.recurrence == RecurrenceType.ONE_TIME) RecurrenceType.DAILY else task.recurrence,
                        activeFrom = null, updatedAt = now,
                    )) }
                } else {
                    orderDao.updatePeriodDates(periodId, start, finalEnd)
                    serviceDays?.let { orderDao.updateServiceDays(periodId, it) }
                }
                orderDao.updateUnusedIdentity(orderId, accountId, type, null, null, now)
            } else {
                if (order.type == OrderType.HOSTING) {
                    tasks.forEach { task -> taskDao.updateTask(task.copy(
                        periodId = null, recurrence = RecurrenceType.ONE_TIME, weekdays = null,
                        everyNDays = null, monthDay = null, monthDays = null, activeFrom = null, updatedAt = now,
                    )) }
                    orderDao.deletePeriods(orderId)
                }
                orderDao.updateUnusedIdentity(orderId, accountId, type, price, end, now)
            }
        }
    }

    override suspend fun archiveExpiredHostingOrder(orderId: String) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.type == OrderType.HOSTING && order.cancelledAt == null) { "仅正常到期的托管订单可以不续期归档" }
            requireWritableAccount(order.accountId, null)
            if (order.archivedAt != null) return@withDatasetTransaction
            val now = ticker.now()
            if (order.hostingMode == HostingMode.COUNT_BASED) {
                // No calendar expiry exists in this mode: only reaching every count
                // period's own agreed number of rounds makes the order archivable.
                val sessions = hostingSessionDao.findForOrder(orderId).map { it.toModel() }
                val countPeriods = orderDao.findPeriods(orderId).filter { it.serviceCount != null }
                val complete = if (countPeriods.isEmpty()) {
                    CountHosting.isComplete(sessions, order.targetSessions)
                } else {
                    countPeriods.all { period ->
                        CountHosting.periodCompletedCount(sessions, period.id) >=
                            (period.serviceCount ?: 0)
                    }
                }
                require(complete) {
                    "打卡式托管还没完成约定次数，完成或作废后才能归档"
                }
                orderDao.setArchived(orderId, now, now)
                return@withDatasetTransaction
            }
            val periods = orderDao.findPeriods(orderId)
            require(periods.isNotEmpty() && periods.all { GameCalendar.gameDate(now, refreshOf(it)).isAfter(it.endDate) }) {
                "订单仍有当前或未来托管期，全部期次到期后才能选择不续期"
            }
            orderDao.setArchived(orderId, now, now)
        }
    }

    override suspend fun restoreArchivedOrder(orderId: String) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能恢复为正常订单" }
            requireWritableAccount(order.accountId, null)
            if (order.archivedAt != null) orderDao.setArchived(orderId, null, ticker.now())
        }
    }

    override suspend fun deleteUnusedOrder(orderId: String) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: return@withDatasetTransaction
            val tasks = taskDao.findForOrder(orderId)
            require(order.completedAt == null && orderDao.findPriceChanges(orderId).isEmpty() && receiptDao.findForOrder(orderId).isEmpty() &&
                attachmentDao.findForOrder(orderId).isEmpty() &&
                occurrenceDao.findForOrder(orderId).isEmpty() &&
                orderDao.countBatchReferences(orderId) == 0 &&
                tasks.all { it.completedAt == null && it.currentValue.compareTo(it.startValue) == 0 } &&
                orderDao.findPeriods(orderId).size <= 1) {
                "订单已有业务记录，请使用作废订单以保留历史"
            }
            require(order.cancelledAt == null) { "已作废订单保留历史，不能删除" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(hostingSessionDao.countForOrder(orderId) <= 1) {
                "订单已有打卡记录，请使用作废订单以保留历史"
            }
            orderDao.deleteCreationIdentity(orderId)
            hostingSessionDao.deleteForOrder(orderId)
            orderDao.deleteTasks(orderId)
            orderDao.deletePeriods(orderId)
            orderDao.deleteOrder(orderId)
        }
    }

    override suspend fun cancelOrder(orderId: String) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            if (order.cancelledAt == null) orderDao.cancel(orderId, ticker.now())
        }
    }

    override suspend fun deleteCancelledOrder(orderId: String): OrderDeletionSummary =
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId)
                ?: return@withDatasetTransaction OrderDeletionSummary(
                    orderId, 0, 0, 0L, 0, 0,
                )
            require(order.cancelledAt != null) {
                "只能删除已作废的订单；误建订单请用「删除误建订单」，已有记录的订单请先作废"
            }
            deleteOrderCascade(
                orderId = orderId,
                orderDao = orderDao,
                taskDao = taskDao,
                occurrenceDao = occurrenceDao,
                receiptDao = receiptDao,
                attachmentDao = attachmentDao,
                completionBatchDao = batchDao,
                hostingSessionDao = hostingSessionDao,
            )
        }

    override suspend fun updateOrderTitle(orderId: String, title: String) {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "请填写订单名称" }
        require(!com.daigan.record.data.model.ShortOrderDraft.titleExceedsLimit(trimmed)) {
            "订单名称最多 ${com.daigan.record.data.model.ShortOrderDraft.MAX_TITLE_LENGTH} 个字符"
        }
        database.withDatasetTransaction {
            orderDao.findById(orderId) ?: error("订单不存在")
            orderDao.updateTitle(orderId, trimmed, ticker.now())
        }
    }

    /**
     * Corrects the game-refresh rules for the period in effect today and every period
     * that has not started yet. Ended periods keep their snapshot, because their
     * occurrence keys, dates and screenshots are history (UF-013).
     *
     * The database row on `orders` alone was not enough: hosting work is computed from
     * `hosting_periods`, so a saved rule used to change nothing. A change that would
     * re-identify already recorded work (time zone or weekly refresh day/time) is
     * rejected with an explanation and writes nothing at all.
     */
    override suspend fun updateOrderRefresh(orderId: String, rules: GameRefreshRules) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能修改刷新规则" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            val now = ticker.now()
            val scoped = orderDao.findPeriods(orderId).filter { period ->
                !period.endDate.isBefore(GameCalendar.gameDate(now, refreshOf(period)))
            }
            val recordedTaskIds = occurrenceDao.findForOrder(orderId).map { it.taskDefinitionId }.toSet()
            scoped.forEach { period ->
                val changesIdentity = rules.zoneId.id != period.gameZoneId ||
                    rules.weeklyRefreshDayIso != period.weeklyRefreshDay ||
                    rules.weeklyRefreshMinutes != period.weeklyRefreshMinutes ||
                    rules.monthlyRefreshDays !=
                    (parseMonthlyDates(period.monthlyRefreshDays) ?: emptySet<Int>())
                require(!changesIdentity || taskDao.findForPeriod(period.id).none { it.id in recordedTaskIds }) {
                    "第 ${period.periodIndex + 1} 期已有打卡记录，不能更改其时区或每周刷新规则；请保留原规则。"
                }
            }
            orderDao.updateRefreshRules(
                orderId = orderId,
                zoneId = rules.zoneId.id,
                dailyMinutes = rules.dailyRefreshMinutes,
                weeklyDay = rules.weeklyRefreshDayIso,
                weeklyMinutes = rules.weeklyRefreshMinutes,
                monthlyDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                updatedAt = now,
            )
            orderDao.touch(orderId, now)
            scoped.forEach { period ->
                orderDao.updatePeriodRefreshRules(
                    periodId = period.id,
                    zoneId = rules.zoneId.id,
                    dailyMinutes = rules.dailyRefreshMinutes,
                    weeklyDay = rules.weeklyRefreshDayIso,
                    weeklyMinutes = rules.weeklyRefreshMinutes,
                    monthlyDays = encodeMonthlyDates(rules.monthlyRefreshDays),
                )
            }
        }
    }

    override suspend fun updateServiceSchedule(orderId: String, periodId: String, serviceDays: Int?, weekdays: Set<DayOfWeek>) {
        require(serviceDays == null || serviceDays in 1..HostingOrderDraft.MAX_DURATION_DAYS) { "服务天数需为 1–3650 的整数" }
        require(serviceDays == null || weekdays.isNotEmpty()) { "请至少选择一个服务星期" }
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.type == OrderType.HOSTING && order.cancelledAt == null) { "只有有效托管订单可以更正服务计划" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            require(order.hostingMode != HostingMode.COUNT_BASED) {
                "打卡式托管没有服务日计划，不能修改服务计划"
            }
            requireWritableAccount(order.accountId, null)
            val periods = orderDao.findPeriods(orderId)
            val period = periods.firstOrNull { it.id == periodId } ?: error("期次不存在")
            val now = ticker.now()
            require(!period.endDate.isBefore(GameCalendar.gameDate(now, refreshOf(period)))) { "已结束期次保留原服务计划" }
            val selected = if (serviceDays == null) emptySet() else weekdays
            val end = serviceDays?.let { GameCalendar.hostingEndDateForWeekdays(period.startDate, it, selected) } ?: period.endDate
            require(periods.filter { it.id != periodId }.none { !end.isBefore(it.startDate) && !period.startDate.isAfter(it.endDate) }) { "更正后的服务计划与其他期次重叠" }
            val ids = taskDao.findForPeriod(period.id).map { it.id }.toSet()
            val recorded = occurrenceDao.findForOrder(orderId).filter { it.taskDefinitionId in ids }
            require(recorded.all { it.gameDate <= end && (it.windowEnd == null || it.windowEnd <= end) }) { "结束日期不能排除已有记录" }
            if (selected != parseWeekdays(period.serviceWeekdays) && selected.isNotEmpty()) {
                require(recorded.all { it.gameDate.dayOfWeek in selected }) { "新的服务星期会排除已有打卡日期，请保留原星期或更正未来期次" }
            }
            orderDao.updatePeriodDates(period.id, period.startDate, end)
            orderDao.updateServicePlan(period.id, serviceDays, encodeWeekdays(selected))
            orderDao.touch(orderId, now)
        }
    }

    override suspend fun updateTaskDetails(orderId: String, tasks: List<TaskDraft>) {
        require(tasks.isNotEmpty() && tasks.size <= ShortOrderDraft.MAX_TASKS) { "请保留 1–${ShortOrderDraft.MAX_TASKS} 项任务" }
        require(tasks.mapNotNull { it.id }.distinct().size == tasks.mapNotNull { it.id }.size) { "任务不能重复" }
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能修改任务" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            val now = ticker.now()
            val all = taskDao.findForOrder(orderId)
            val periodIds = tasks.mapNotNull { it.periodId }.distinct()
            require(periodIds.size <= 1) { "每次只能更正一个期次" }
            val period = if (order.type == OrderType.HOSTING) {
                (periodIds.firstOrNull()?.let { orderDao.findPeriod(it) }
                    ?: activePeriodForToday(orderDao.findPeriods(orderId), now)
                    ?: orderDao.findPeriods(orderId).maxByOrNull { it.periodIndex })
                    ?.also { p ->
                        require(p.orderId == orderId) { "期次不属于此订单" }
                        // 打卡式托管 owns one container row whose dates are bookkeeping
                        // only (the creation game date), so it never "ends" and its task
                        // list stays editable; recorded rounds are guarded separately by
                        // the caller that changes the agreed count.
                        if (order.hostingMode != HostingMode.COUNT_BASED) {
                            require(!p.endDate.isBefore(GameCalendar.gameDate(now, refreshOf(p)))) {
                                "已结束期次只保留历史，不能修改任务定义"
                            }
                        }
                    } ?: error("托管期次不存在")
            } else { require(periodIds.isEmpty()) { "短期任务不能归属于托管期次" }; null }
            val scope = all.filter { it.periodId == period?.id && it.retiredAt == null }
            val known = scope.associateBy { it.id }
            tasks.forEach { draft ->
                require(draft.id == null || known.containsKey(draft.id)) { "任务已变化或不属于所选期次，请重新打开" }
            }
            val validation = if (period != null) {
                HostingOrderDraft(ownerId = "validation", accountId = order.accountId, title = order.title,
                    startDate = period.startDate, endDate = period.endDate, priceYuan = "1", refresh = refreshOf(period), tasks = tasks).validate()
            } else ShortOrderDraft(ownerId = "validation", accountId = order.accountId, title = order.title,
                priceYuan = "1", tasks = tasks).validate()
            require(validation.isEmpty()) { validation.values.firstOrNull().orEmpty() }
            val occurrences = occurrenceDao.findForOrder(orderId).map { it.taskDefinitionId }.toSet()
            val attachments = attachmentDao.findForOrder(orderId).mapNotNull { it.taskDefinitionId }.toSet()
            val kept = tasks.mapNotNull { it.id }.toSet()
            scope.filter { it.id !in kept }.forEach { taskDao.retire(it.id, now) }
            tasks.forEachIndexed { index, draft ->
                val old = draft.id?.let(known::get)
                val fresh = draft.toEntity(orderId, period?.id, index, now)
                val sameSemantics = old != null && old.progressType == fresh.progressType &&
                    old.recurrence == fresh.recurrence && old.weekdays == fresh.weekdays &&
                    old.everyNDays == fresh.everyNDays && old.toModel().monthDays == fresh.toModel().monthDays
                val recorded = old != null && (old.id in occurrences || old.id in attachments ||
                    old.completedAt != null || old.completionBatchId != null ||
                    old.currentValue.compareTo(old.startValue) != 0 ||
                    eventDao.countForTask(old.id) > 0)
                if (old != null && recorded &&
                    old.numericMode.effective() != fresh.numericMode.effective()
                ) {
                    // Mode switches rewrite history semantics, so they are only allowed on
                    // tasks without any recorded data; create a new task to keep old history.
                    throw IllegalStateException("已有进度记录的任务不能修改数值口径，请新建任务")
                }
                when {
                    sameSemantics -> {
                        val previous = requireNotNull(old)
                        val actual = if (!recorded) fresh.startValue else previous.currentValue
                        val completed = if (previous.progressType == ProgressType.NUMERIC && period == null) {
                            if (actual >= fresh.targetValue) previous.completedAt ?: now else null
                        } else previous.completedAt
                        taskDao.updateTask(previous.copy(title = fresh.title, unit = fresh.unit,
                            startValue = fresh.startValue, targetValue = fresh.targetValue, currentValue = actual,
                            activeFrom = fresh.activeFrom ?: previous.activeFrom,
                            activeUntil = fresh.activeUntil,
                            numericMode = fresh.numericMode,
                            colorArgb = fresh.colorArgb ?: previous.colorArgb,
                            completedAt = completed,
                            completionBatchId = if (completed == previous.completedAt &&
                                previous.startValue.compareTo(fresh.startValue) == 0 &&
                                previous.targetValue.compareTo(fresh.targetValue) == 0) previous.completionBatchId else null,
                            sortOrder = index, updatedAt = now))
                        if (period != null && previous.progressType == ProgressType.NUMERIC &&
                            previous.targetValue.compareTo(fresh.targetValue) != 0) {
                            occurrenceDao.findForTask(previous.id).forEach { occurrence ->
                                val value = occurrence.value ?: previous.startValue
                                val completion = if (value >= fresh.targetValue) occurrence.completedAt ?: now else null
                                occurrenceDao.updateCompletion(occurrence.id, completion, occurrence.recordedAt,
                                    occurrence.backfilled,
                                    null, // A corrected target invalidates an older batch undo snapshot.
                                    occurrence.value)
                            }
                        }
                    }
                    old != null && !recorded -> taskDao.updateTask(fresh.copy(id = old.id, createdAt = old.createdAt,
                        activeFrom = fresh.activeFrom ?: old.activeFrom
                            ?: period?.let { GameCalendar.gameDate(now, refreshOf(it)) }))
                    else -> {
                        old?.let { taskDao.retire(it.id, now) }
                        taskDao.insert(
                            fresh.copy(
                                activeFrom = fresh.activeFrom
                                    ?: period?.let { GameCalendar.gameDate(now, refreshOf(it)) },
                            ),
                        )
                    }
                }
            }
            orderDao.touch(orderId, now)
            if (order.type == OrderType.SHORT_TASK) refreshShortCompletion(orderId, now)
        }
    }

    override suspend fun updateTaskNumericMode(
        taskId: String,
        mode: NumericMode,
        expectedGeneration: Long?,
    ) {
        database.withDatasetTransaction {
            DatasetExpectation.requireCurrent(expectedGeneration)
            val task = taskDao.findById(taskId) ?: throw IllegalArgumentException("任务不存在")
            require(task.retiredAt == null) { "任务已更正，请刷新后再操作" }
            require(task.progressType == ProgressType.NUMERIC) { "只有数值任务可以选择数值口径" }
            val order = orderDao.findById(task.orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
            require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(order.accountId, null)
            require(task.numericMode.effective() != mode) { "数值口径没有变化" }
            val hasOccurrence = occurrenceDao.findForTask(taskId).isNotEmpty()
            val hasEvent = eventDao.countForTask(taskId) > 0
            require(!hasOccurrence && !hasEvent) {
                "该任务已有打卡或进度记录，不能切换数值口径；请新建任务保留历史"
            }
            taskDao.updateTask(task.copy(numericMode = mode, updatedAt = ticker.now()))
        }
    }

    override suspend fun updateOrderNotes(orderId: String, notes: String?) {
        val normalized = notes?.trim()?.ifBlank { null }
        require((normalized?.length ?: 0) <= 1000) { "订单备注最多 1000 个字符" }
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能修改备注" }
            requireWritableAccount(order.accountId, null)
            orderDao.updateNotes(orderId, normalized, ticker.now())
        }
    }

    override suspend fun updatePaymentMode(orderId: String, mode: PaymentMode) {
        database.withDatasetTransaction {
            val order = orderDao.findById(orderId) ?: throw IllegalArgumentException("订单不存在")
            require(order.cancelledAt == null) { "已作废订单不能修改收款方式" }
            requireWritableAccount(order.accountId, null)
            // Payment mode describes how the order should be settled going forward.
            // It is editable after receipts too; existing receipt rows are immutable
            // history and are never rewritten by this setting change.
            orderDao.updatePaymentMode(orderId, mode, ticker.now())
        }
    }

    override suspend fun setOrderColor(orderId: String, colorArgb: Int?) {
        database.withDatasetTransaction {
            orderDao.findById(orderId) ?: error("订单不存在")
            // Colour is presentation metadata, not business history: it stays editable on
            // cancelled/archived orders so a list can still be filtered after archiving.
            orderDao.updateOrderColor(orderId, colorArgb, ticker.now())
        }
    }

    /**
     * Unified order edit (UF-030).
     *
     * One draft, one transaction. The steps below are the same repository routines the
     * six former dialogs used — same validation, same history guards, same price audit —
     * but they run inside a single transaction so a rejection in any later step rolls
     * the earlier writes back. Nested `withDatasetTransaction` calls re-enter the
     * dataset permit and Room's transaction instead of opening a second one.
     */
    override suspend fun applyOrderEdit(draft: OrderEditDraft) {
        val title = draft.title.trim()
        require(title.isNotEmpty()) { "请填写订单名称" }
        require(!ShortOrderDraft.titleExceedsLimit(title)) {
            "订单名称最多 ${ShortOrderDraft.MAX_TITLE_LENGTH} 个字符"
        }
        require(draft.notes.trim().length <= OrderEditDraft.NOTES_MAX_LENGTH) {
            "备注最多 ${OrderEditDraft.NOTES_MAX_LENGTH} 个字符"
        }
        require(draft.priceReason.trim().length <= com.daigan.record.data.model.RECEIPT_REASON_MAX_LENGTH) {
            "修改原因最多 ${com.daigan.record.data.model.RECEIPT_REASON_MAX_LENGTH} 个字符"
        }
        require(draft.operationId.isNotBlank()) { "缺少修改操作标识，请重新打开编辑页" }
        val priceCents = requireNotNull(draft.priceCentsOrNull) { "约定价格需为不超过两位小数的非负数字" }
        val refresh = requireNotNull(draft.refreshOrNull) { "刷新规则格式不正确，例如时区 Asia/Shanghai、时间 HH:MM" }
        require(draft.tasks.isNotEmpty() && draft.tasks.size <= ShortOrderDraft.MAX_TASKS) {
            "请保留 1–${ShortOrderDraft.MAX_TASKS} 项任务"
        }

        database.withDatasetTransaction {
            val stored = orderDao.findById(draft.orderId) ?: error("订单不存在")
            require(stored.cancelledAt == null) { "已作废订单不能修改" }
            require(stored.archivedAt == null) { "订单已归档，请先恢复订单" }
            requireWritableAccount(stored.accountId, null)
            val now = ticker.now()

            // 1. Identity correction first: it may create/remove the period and re-home
            //    the task rows, so every later step must read the new shape. The routine
            //    keeps its own guard that only an order without any history qualifies.
            val identityChanged = stored.accountId != draft.accountId || stored.type != draft.type
            if (identityChanged) {
                requireWritableAccount(draft.accountId, null)
                updateUnusedOrderIdentity(
                    orderId = draft.orderId,
                    accountId = draft.accountId,
                    type = draft.type,
                    start = draft.startDate ?: GameCalendar.gameDate(now, refreshOf(stored)),
                    end = if (draft.type == OrderType.HOSTING) {
                        requireNotNull(draft.endDate) { "请选择托管结束日期" }
                    } else {
                        draft.deadlineDate
                    },
                )
            }

            val order = orderDao.findById(draft.orderId) ?: error("订单不存在")
            val hosting = order.type == OrderType.HOSTING
            val countBased = order.hostingMode == HostingMode.COUNT_BASED
            val periods = orderDao.findPeriods(draft.orderId)
            val period = draft.periodId?.let { id -> periods.firstOrNull { it.id == id } }
                ?: periods.minByOrNull { it.periodIndex }

            // 2. Only real changes are written. An unchanged field must not bump the order,
            //    rewrite its tasks or append a price-correction row — otherwise merely
            //    renaming an order would look like a price edit and strip the order of its
            //    "no history yet" rights (identity correction, mistaken-order deletion).
            val normalizedNotes = draft.notes.trim().ifBlank { null }
            if (order.title != title) updateOrderTitle(draft.orderId, title)
            if (order.notes != normalizedNotes) updateOrderNotes(draft.orderId, draft.notes)
            if (order.paymentMode != draft.paymentMode) updatePaymentMode(draft.orderId, draft.paymentMode)

            val refreshChanged = order.gameZoneId != refresh.zoneId.id ||
                order.dailyRefreshMinutes != refresh.dailyRefreshMinutes ||
                order.weeklyRefreshDay != refresh.weeklyRefreshDayIso ||
                order.weeklyRefreshMinutes != refresh.weeklyRefreshMinutes
            if (refreshChanged) {
                updateOrderRefresh(draft.orderId, refresh)
                // Count-based hosting resolves today's round from its container period's
                // snapshot. That period is not "current" by date, so the scoped update
                // above never touches it; keep the snapshot in step explicitly.
                if (countBased && period != null) {
                    orderDao.updatePeriodRefreshRules(
                        periodId = period.id,
                        zoneId = refresh.zoneId.id,
                        dailyMinutes = refresh.dailyRefreshMinutes,
                        weeklyDay = refresh.weeklyRefreshDayIso,
                        weeklyMinutes = refresh.weeklyRefreshMinutes,
                        monthlyDays = encodeMonthlyDates(refresh.monthlyRefreshDays),
                    )
                }
            }

            // 3. Mode-specific terms.
            when {
                hosting && countBased -> {
                    val container = requireNotNull(period) { "打卡式托管缺少价格期次" }
                    val target = requireNotNull(draft.targetSessions) { "请填写打卡次数" }
                    require(target in 1..CountHosting.MAX_TARGET_SESSIONS) {
                        "打卡次数需为 1–${CountHosting.MAX_TARGET_SESSIONS} 的整数"
                    }
                    // Recorded rounds are history: this period's target may grow, but it
                    // can never be lowered below the rounds that already counted for it.
                    val completed = CountHosting.periodCompletedCount(
                        hostingSessionDao.findForOrder(draft.orderId).map { it.toModel() },
                        container.id,
                    )
                    require(target >= completed) {
                        "本期已完成 $completed 次打卡，约定次数不能少于已完成次数"
                    }
                    val storedTasks = taskDao.findForPeriod(container.id).filter { it.retiredAt == null }
                    val tasksEdited = tasksDiffer(draft.tasks, storedTasks, container.id, now)
                    val targetChanged = container.serviceCount != target
                    if (tasksEdited) {
                        // No dates exist in this mode, so only the task list is corrected.
                        updateTaskDetails(draft.orderId, draft.tasks.map { it.copy(periodId = container.id) })
                    }
                    if (targetChanged) {
                        // Period-local target first. The order-level column is a mirror of
                        // the **latest** count period (highest periodIndex), so it is
                        // recomputed from the period rows instead of assuming "this edited
                        // period is the only one". Editing an older period never moves it.
                        orderDao.updatePeriodServiceCount(container.id, target)
                        orderDao.findPeriods(draft.orderId)
                            .filter { it.serviceCount != null }
                            .maxWithOrNull(compareBy({ it.periodIndex }, { it.id }))
                            ?.serviceCount
                            ?.let { mirror -> orderDao.updateTargetSessions(draft.orderId, mirror, now) }
                    }
                    // Completion follows the agreed count in the same transaction: raising
                    // it above the completed rounds clears completedAt and makes the
                    // remaining work available, lowering it to the completed rounds marks
                    // the order complete. Rounds and partial progress are never invented
                    // or removed here.
                    if (targetChanged) countSessions.reconcile(draft.orderId, now)
                    if (container.priceCents != priceCents) {
                        updatePeriodPrice(draft.orderId, container.id, priceCents, draft.operationId, draft.priceReason)
                    }
                }

                hosting -> {
                    val targetPeriod = requireNotNull(period) { "托管期次不存在" }
                    val planChanged = if (draft.serviceDays == null && targetPeriod.serviceDays == null) {
                        false
                    } else {
                        targetPeriod.serviceDays != draft.serviceDays ||
                            parseWeekdays(targetPeriod.serviceWeekdays) != draft.serviceWeekdays
                    }
                    val datesChanged = targetPeriod.startDate != draft.startDate ||
                        targetPeriod.endDate != draft.endDate
                    require(!datesChanged || (draft.startDate != null && draft.endDate != null)) {
                        "请选择开始日期和结束日期"
                    }
                    // An ended period keeps its service plan and task list as history: both
                    // routines reject it themselves and are only called when the draft
                    // really changed them, so a name/price/date correction on an expired
                    // order still works.
                    if (planChanged) {
                        updateServiceSchedule(
                            orderId = draft.orderId,
                            periodId = targetPeriod.id,
                            serviceDays = draft.serviceDays,
                            weekdays = draft.serviceWeekdays,
                        )
                    }
                    if (datesChanged) {
                        updateOrderDates(draft.orderId, targetPeriod.id, draft.startDate, draft.endDate)
                    }
                    if (targetPeriod.priceCents != priceCents) {
                        updatePeriodPrice(draft.orderId, targetPeriod.id, priceCents, draft.operationId, draft.priceReason)
                    }
                    val storedTasks = taskDao.findForPeriod(targetPeriod.id).filter { it.retiredAt == null }
                    if (tasksDiffer(draft.tasks, storedTasks, targetPeriod.id, now)) {
                        updateTaskDetails(draft.orderId, draft.tasks.map { it.copy(periodId = targetPeriod.id) })
                    }
                }

                else -> {
                    if (order.deadlineDate != draft.deadlineDate) {
                        updateOrderDates(draft.orderId, null, null, draft.deadlineDate)
                    }
                    if ((order.priceCents ?: 0L) != priceCents) {
                        updateShortOrderPrice(draft.orderId, priceCents, draft.operationId, draft.priceReason)
                    }
                    val storedTasks = taskDao.findForOrder(draft.orderId)
                        .filter { it.periodId == null && it.retiredAt == null }
                    if (tasksDiffer(draft.tasks, storedTasks, null, now)) {
                        updateTaskDetails(draft.orderId, draft.tasks.map { it.copy(periodId = null) })
                    }
                }
            }
        }
    }

    /**
     * True when the editor's task list would change [stored] (a period's active tasks).
     *
     * The editor always resubmits every task, so "the draft is present" must not mean
     * "the task changed": identity and the normalized values from `toEntity` are compared
     * instead. A removal, addition, reorder or any field edit counts as a change.
     */
    private fun tasksDiffer(
        drafts: List<TaskDraft>,
        stored: List<TaskDefinitionEntity>,
        periodId: String?,
        now: Instant,
    ): Boolean {
        if (stored.isEmpty()) {
            // Nothing persisted to protect: only real input (an id or a filled title)
            // counts as an addition, not the blank placeholder row of a new draft.
            return drafts.any { it.id != null || it.title.isNotBlank() }
        }
        if (drafts.size != stored.size) return true
        val byId = stored.associateBy { it.id }
        return drafts.withIndex().any { (index, draft) ->
            val existing = draft.id?.let(byId::get) ?: return true
            draft.differsFrom(existing, periodId, index, now)
        }
    }

    private fun TaskDraft.differsFrom(
        stored: TaskDefinitionEntity,
        periodId: String?,
        sortOrder: Int,
        now: Instant,
    ): Boolean {
        val fresh = toEntity(stored.orderId, periodId, sortOrder, now)
        return stored.title != fresh.title ||
            stored.progressType != fresh.progressType ||
            stored.startValue.compareTo(fresh.startValue) != 0 ||
            stored.targetValue.compareTo(fresh.targetValue) != 0 ||
            stored.unit != fresh.unit ||
            stored.recurrence != fresh.recurrence ||
            stored.weekdays != fresh.weekdays ||
            stored.monthDay != fresh.monthDay ||
            stored.monthDays != fresh.monthDays ||
            stored.everyNDays != fresh.everyNDays ||
            stored.numericMode.effective() != fresh.numericMode.effective() ||
            stored.activeFrom != fresh.activeFrom ||
            stored.activeUntil != fresh.activeUntil ||
            stored.colorArgb != fresh.colorArgb ||
            stored.sortOrder != fresh.sortOrder
    }

    override suspend fun updateShortOrderPrice(
        orderId: String,
        priceCents: Long,
        operationId: String,
        reason: String?,
    ) {
        updateAgreedPrice(
            orderId = orderId,
            periodId = null,
            priceCents = priceCents,
            operationId = operationId,
            reason = reason,
        )
    }

    override suspend fun updatePeriodPrice(
        orderId: String,
        periodId: String,
        priceCents: Long,
        operationId: String,
        reason: String?,
    ) {
        updateAgreedPrice(
            orderId = orderId,
            periodId = periodId,
            priceCents = priceCents,
            operationId = operationId,
            reason = reason,
        )
    }

    /**
     * Shared price-correction core (UF-012). The agreed price changes on exactly one
     * payable item — a short order or one hosting period — and a matching audit row is
     * written in the same transaction. Actual receipt rows are never rewritten, so an
     * already-collected amount that now exceeds the price simply shows as 多收.
     * Retrying the same [operationId] is a no-op.
     */
    private suspend fun updateAgreedPrice(
        orderId: String,
        periodId: String?,
        priceCents: Long,
        operationId: String,
        reason: String?,
    ) {
        require(operationId.isNotBlank()) { "操作标识不能为空" }
        require(priceCents in 0..Money.MAX_CENTS) { "金额需为 0 到 ${Money.MAX_CENTS} 分之间" }
        require((reason?.trim()?.length ?: 0) <= com.daigan.record.data.model.RECEIPT_REASON_MAX_LENGTH) {
            "更正原因最多 ${com.daigan.record.data.model.RECEIPT_REASON_MAX_LENGTH} 个字符"
        }
        val normalizedReason = reason?.trim()?.ifBlank { null }
        database.withDatasetTransaction {
            orderDao.findPriceChangeByOperationId(operationId)?.let { return@withDatasetTransaction }
            val order = orderDao.findById(orderId) ?: error("订单不存在")
            require(order.cancelledAt == null) { "该订单已作废，不能修改价格" }
            val previous = if (periodId == null) {
                require(order.type == OrderType.SHORT_TASK) { "托管订单请修改期次价格" }
                order.priceCents ?: 0L
            } else {
                require(order.type == OrderType.HOSTING) { "短期订单没有托管期次" }
                val period = orderDao.findPeriod(periodId)
                    ?: error("托管期次不存在")
                require(period.orderId == orderId) { "所选期次不属于该订单" }
                period.priceCents
            }
            if (previous != priceCents) {
                if (periodId == null) {
                    orderDao.updateShortPrice(orderId, priceCents, ticker.now())
                } else {
                    orderDao.updatePeriodPrice(periodId, priceCents)
                    orderDao.touch(orderId, ticker.now())
                }
            }
            orderDao.insertPriceChange(
                com.daigan.record.data.local.entity.OrderPriceChangeEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = operationId,
                    orderId = orderId,
                    periodId = periodId,
                    previousPriceCents = previous,
                    newPriceCents = priceCents,
                    reason = normalizedReason,
                    createdAt = PersistedInstant.normalize(ticker.now()),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun requireWritableAccount(accountId: String, expectedOwnerId: String?): AccountOwner {
        val account = accountDao.findById(accountId)
            ?: throw IllegalArgumentException("游戏账号不存在")
        if (expectedOwnerId != null) {
            require(account.ownerId == expectedOwnerId) { "游戏账号不属于该老板" }
        }
        val owner = ownerDao.findById(account.ownerId)
            ?: throw IllegalArgumentException("老板不存在")
        require(owner.archivedAt == null) { ARCHIVED_OWNER_ORDER_MESSAGE }
        require(account.archivedAt == null) { ARCHIVED_ACCOUNT_ORDER_MESSAGE }
        return AccountOwner(account, owner)
    }

    /**
     * Rejects a write when the task's account or owner is archived. Missing rows are
     * left to the per-item conflict path so undo can still explain them.
     */
    private suspend fun assertTaskWritable(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        val order = orderDao.findById(task.orderId) ?: return
        require(order.cancelledAt == null) { "该订单已作废，不能再修改工作记录" }
        require(order.archivedAt == null) { "订单已归档，请先恢复订单" }
        val account = accountDao.findById(order.accountId) ?: return
        val owner = ownerDao.findById(account.ownerId) ?: return
        require(owner.archivedAt == null) { ARCHIVED_OWNER_WORK_MESSAGE }
        require(account.archivedAt == null) { ARCHIVED_ACCOUNT_ORDER_MESSAGE }
    }

    private suspend fun refreshShortCompletion(orderId: String, now: Instant) {
        val tasks = taskDao.findForOrder(orderId).filter { it.retiredAt == null }
        if (tasks.isEmpty()) return
        val allCompleted = tasks.all { it.completedAt != null }
        val order = orderDao.findById(orderId) ?: return
        val target = if (allCompleted) order.completedAt ?: now else null
        if (order.completedAt != target) {
            orderDao.updateCompletedAt(orderId, target, now)
        }
    }

    /**
     * 批量完成会跳过的累计任务数（UF-038）：未达标的累计目标需要用户输入真实总值，
     * 不能被批量操作推进。返回值用于明确提示「跳过 N 项」，绝不静默丢弃。
     */
    private suspend fun pendingCumulativeCount(periodId: String): Int =
        taskDao.findForPeriod(periodId)
            .filter { it.retiredAt == null }
            .map { it.toModel() }
            .count { task ->
                task.progressType == ProgressType.NUMERIC &&
                    task.numericMode.effective() == NumericMode.CUMULATIVE &&
                    task.currentValue < task.targetValue
            }

    private suspend fun collectPendingForOrder(
        period: HostingPeriodEntity,
        now: Instant,
    ): List<PendingOccurrence> {
        val rules = refreshOf(period)
        val today = GameCalendar.gameDate(now, rules)
        if (GameCalendar.hostingStatus(today, period.startDate, period.endDate) != HostingStatus.ACTIVE) {
            return emptyList()
        }
        val changes = mutableListOf<PendingOccurrence>()
        taskDao.findForPeriod(period.id).filter { it.retiredAt == null }.forEach { taskEntity ->
            val task = taskEntity.toModel()
            // 契约（UF-038）：批量完成不得为累计目标虚构数值；未达标的累计任务跳过，
            // 由用户在累计面板里输入真实的当前总值。
            if (task.progressType == ProgressType.NUMERIC &&
                task.numericMode.effective() == NumericMode.CUMULATIVE
            ) {
                return@forEach
            }
            val slot = OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                monthDay = task.monthDay,
                monthDays = task.monthDays,
                activeFrom = task.activeFrom,
                activeUntil = task.activeUntil,
                retiredAt = task.retiredAt,
                serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            ) ?: return@forEach
            val occurrence = ensureOccurrence(taskEntity, slot, now)
            if (occurrence.completedAt != null) return@forEach
            val appliedValue = if (task.progressType == ProgressType.NUMERIC) {
                maxOf(occurrence.value ?: task.startValue, task.targetValue)
            } else {
                occurrence.value
            }
            changes += PendingOccurrence(
                occurrence = occurrence,
                previousCompletedAt = occurrence.completedAt,
                previousRecordedAt = occurrence.recordedAt,
                previousBackfilled = occurrence.backfilled,
                previousValue = occurrence.value,
                previousBatchId = occurrence.completionBatchId,
                appliedCompletedAt = now,
                appliedValue = appliedValue,
            )
        }
        return changes
    }

    /**
     * Pending work of one round, one occurrence per due task of **that round's own
     * period** under the round key `N:<index>`. Completing all of them completes exactly
     * one round; an empty due set yields no change and therefore never fabricates a
     * completion.
     */
    private suspend fun collectPendingForCountRound(
        round: CountRound,
        now: Instant,
    ): List<PendingOccurrence> {
        val today = GameCalendar.gameDate(now, refreshOf(round.period))
        val slot = OccurrenceSlot(
            key = round.key,
            attributedDate = today,
            windowStart = today,
            windowEnd = today,
        )
        val changes = mutableListOf<PendingOccurrence>()
        round.tasks.forEach { taskEntity ->
            val task = taskEntity.toModel()
            // 契约（UF-038）：一轮达标不等于所有轮次达标；累计任务必须按轮次输入真实
            // 总值，批量完成不得推进它。
            if (taskEntity.isCumulativeNumeric()) return@forEach
            if (task.retiredAt != null) return@forEach
            val occurrence = ensureOccurrence(taskEntity, slot, now)
            if (occurrence.completedAt != null) return@forEach
            val appliedValue = if (task.progressType == ProgressType.NUMERIC) {
                maxOf(occurrence.value ?: task.startValue, task.targetValue)
            } else {
                occurrence.value
            }
            changes += PendingOccurrence(
                occurrence = occurrence,
                previousCompletedAt = occurrence.completedAt,
                previousRecordedAt = occurrence.recordedAt,
                previousBackfilled = occurrence.backfilled,
                previousValue = occurrence.value,
                previousBatchId = occurrence.completionBatchId,
                appliedCompletedAt = now,
                appliedValue = appliedValue,
            )
        }
        return changes
    }

    private suspend fun commitBatch(
        scope: BatchScope,
        targetId: String?,
        gameDate: LocalDate?,
        operationId: String,
        changes: List<PendingOccurrence>,
        now: Instant,
    ): BulkCompletionResult {
        val batchId = UUID.randomUUID().toString()
        batchDao.insertBatch(
            CompletionBatchEntity(
                id = batchId,
                operationId = operationId,
                scope = scope,
                targetId = targetId,
                gameDate = gameDate,
                createdAt = now,
                undoneAt = null,
            ),
        )
        if (changes.isNotEmpty()) {
            batchDao.insertItems(
                changes.map { change ->
                    CompletionBatchItemEntity(
                        id = UUID.randomUUID().toString(),
                        batchId = batchId,
                        itemType = BatchItemType.OCCURRENCE,
                        occurrenceId = change.occurrence.id,
                        taskDefinitionId = change.occurrence.taskDefinitionId,
                        previousCompletedAt = change.previousCompletedAt,
                        previousRecordedAt = change.previousRecordedAt,
                        previousBackfilled = change.previousBackfilled,
                        previousValue = change.previousValue,
                        previousCompletionBatchId = change.previousBatchId,
                        appliedCompletedAt = change.appliedCompletedAt,
                        appliedRecordedAt = change.appliedCompletedAt,
                        appliedBackfilled = false,
                        appliedValue = change.appliedValue,
                        appliedCompletionBatchId = batchId,
                    )
                },
            )
            changes.forEach { change ->
                occurrenceDao.updateCompletion(
                    id = change.occurrence.id,
                    completedAt = change.appliedCompletedAt,
                    recordedAt = change.appliedCompletedAt,
                    backfilled = false,
                    batchId = batchId,
                    value = change.appliedValue,
                )
            }
        }
        return BulkCompletionResult(
            batchId = batchId,
            completedCount = changes.size,
            skippedCount = 0,
            alreadyApplied = false,
        )
    }

    private suspend fun ensureOccurrence(
        task: TaskDefinitionEntity,
        slot: OccurrenceSlot,
        now: Instant,
    ): TaskOccurrenceEntity {
        occurrenceDao.findByKey(task.id, slot.key)?.let { return it }
        val entity = TaskOccurrenceEntity(
            id = UUID.randomUUID().toString(),
            taskDefinitionId = task.id,
            occurrenceKey = slot.key,
            gameDate = slot.attributedDate,
            windowStart = slot.windowStart,
            windowEnd = slot.windowEnd,
            completedAt = null,
            recordedAt = null,
            backfilled = false,
            completionBatchId = null,
            value = null,
            createdAt = now,
        )
        occurrenceDao.insertIgnore(entity)
        return occurrenceDao.findByKey(task.id, slot.key) ?: entity
    }

    private fun refreshOf(order: OrderEntity): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = ZoneId.of(order.gameZoneId),
        dailyRefreshMinutes = order.dailyRefreshMinutes,
        weeklyRefreshDayIso = order.weeklyRefreshDay,
        weeklyRefreshMinutes = order.weeklyRefreshMinutes,
        monthlyRefreshDays = parseMonthlyDates(order.monthlyRefreshDays) ?: emptySet(),
    )

    /**
     * The period that owns work on [now]'s game date, or null when today is outside
     * every period. Uses each period's own stored refresh snapshot, like the Today page,
     * so a refresh-rule correction applies per period rather than globally.
     */
    private fun activePeriodForToday(
        periods: List<HostingPeriodEntity>,
        now: Instant,
    ): HostingPeriodEntity? = HostingPeriodClock.active(
        periods = periods,
        now = now,
        rulesOf = { refreshOf(it) },
        startDate = { it.startDate },
        endDate = { it.endDate },
        periodIndex = { it.periodIndex },
    )?.period

    /**
     * Read-side twin of [activePeriodForToday]: the period a screen must display plus the
     * game date it must display it at. Both come from the same per-period snapshot.
     */
    private fun resolvePeriod(
        periods: List<HostingPeriodEntity>,
        now: Instant,
    ): ResolvedHostingPeriod<HostingPeriodEntity>? = HostingPeriodClock.resolve(
        periods = periods,
        now = now,
        rulesOf = { refreshOf(it) },
        startDate = { it.startDate },
        endDate = { it.endDate },
        periodIndex = { it.periodIndex },
    )

    private fun refreshOf(period: HostingPeriodEntity): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = ZoneId.of(period.gameZoneId),
        dailyRefreshMinutes = period.dailyRefreshMinutes,
        weeklyRefreshDayIso = period.weeklyRefreshDay,
        weeklyRefreshMinutes = period.weeklyRefreshMinutes,
        monthlyRefreshDays = parseMonthlyDates(period.monthlyRefreshDays) ?: emptySet(),
    )

    /**
     * Rebuilds the occurrence identities a screen must have been showing for [displayed]
     * to still be current, then compares them. A period change, a crossed daily refresh
     * or a crossed weekly window all reject the write with [StaleOccurrenceException].
     * Runs inside the caller's transaction before any pending change is committed, so a
     * rejection never leaves a partial batch behind.
     */
    private suspend fun assertSnapshotCurrent(
        displayed: DisplayedHostingSnapshot,
        period: HostingPeriodEntity,
        now: Instant,
    ) {
        val rules = refreshOf(period)
        val today = GameCalendar.gameDate(now, rules)
        if (displayed.periodId != period.id) {
            throw StaleOccurrenceException("期次已变化，请刷新后再试")
        }
        if (displayed.gameDate != today) {
            throw StaleOccurrenceException("已跨过每日刷新时间，展示的游戏日已过期")
        }
        if (GameCalendar.hostingStatus(today, period.startDate, period.endDate) != HostingStatus.ACTIVE) {
            throw StaleOccurrenceException("该期次已不在进行中，展示的任务已过期")
        }
        val expected = taskDao.findForPeriod(period.id).filter { it.retiredAt == null }.mapNotNull { taskEntity ->
            val task = taskEntity.toModel()
            OccurrenceRules.slotForNow(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                monthDay = task.monthDay,
                monthDays = task.monthDays,
                activeFrom = task.activeFrom,
                activeUntil = task.activeUntil,
                retiredAt = task.retiredAt,
                serviceWeekdays = parseWeekdays(period.serviceWeekdays),
                periodStart = period.startDate,
                periodEnd = period.endDate,
                today = today,
                rules = rules,
                now = now,
            )?.let { taskEntity.id to it.key }
        }.toMap()
        if (displayed.occurrenceKeys != expected) {
            throw StaleOccurrenceException("已跨过刷新时间，展示的任务窗口已过期")
        }
    }

    /**
     * Stale-write guard for count-based orders. Their identity is the round, not a game
     * date, so the comparison is the round's period + the exact round keys the screen
     * showed. A newer round on the same day still rejects a stale screen.
     */
    private suspend fun assertCountSnapshotCurrent(
        displayed: DisplayedHostingSnapshot,
        order: OrderEntity,
        round: CountRound,
    ) {
        if (displayed.periodId != round.period.id) {
            throw StaleOccurrenceException("期次已变化，请刷新后再试")
        }
        val expected = round.tasks
            .filter { it.retiredAt == null }
            .associate { it.id to round.key }
        if (displayed.occurrenceKeys != expected) {
            throw StaleOccurrenceException("打卡轮次已变化，请刷新后再试")
        }
        val current = countSessions.activeRound(order.id)
        if (current == null || current.key != round.key || current.period.id != round.period.id) {
            throw StaleOccurrenceException("打卡轮次已变化，请刷新后再试")
        }
    }

    private fun com.daigan.record.data.model.TaskDraft.toEntity(
        orderId: String,
        periodId: String?,
        sortOrder: Int,
        now: Instant,
    ): TaskDefinitionEntity {
        val start = startDecimal() ?: BigDecimal.ZERO
        val target = targetDecimal() ?: BigDecimal.ONE
        return TaskDefinitionEntity(
            id = UUID.randomUUID().toString(),
            orderId = orderId,
            periodId = periodId,
            title = title.trim(),
            progressType = progressType,
            startValue = start,
            targetValue = target,
            currentValue = start,
            unit = unit.trim().ifBlank { null },
            recurrence = recurrence,
            weekdays = encodeWeekdays(weekdays),
            monthDay = if (recurrence == com.daigan.record.data.model.RecurrenceType.MONTHLY) monthDayOrNull() else null,
            monthDays = if (recurrence == com.daigan.record.data.model.RecurrenceType.MONTHLY) com.daigan.record.data.model.encodeMonthlyDates(monthDaysOrNull().orEmpty()) else null,
            everyNDays = if (recurrence == com.daigan.record.data.model.RecurrenceType.EVERY_N_DAYS) {
                everyNOrNull()
            } else {
                null
            },
            sortOrder = sortOrder,
            completedAt = null,
            completionBatchId = null,
            createdAt = now,
            updatedAt = now,
            activeFrom = activeFromOrNull(),
            activeUntil = activeUntilOrNull(),
            numericMode = numericMode?.takeIf { progressType == com.daigan.record.data.model.ProgressType.NUMERIC },
            colorArgb = colorArgb,
        )
    }

    private fun NumericMode?.effective(): NumericMode = this ?: NumericMode.PER_OCCURRENCE

    /** 实体层的累计数值判定，供批量完成跳过逻辑使用（与模型层同定义）。 */
    private fun TaskDefinitionEntity.isCumulativeNumeric(): Boolean =
        progressType == ProgressType.NUMERIC && numericMode.effective() == NumericMode.CUMULATIVE

    private fun GameRefreshRulesDomain.toCore(): GameRefreshRules = GameRefreshRules.fromMinutes(
        zoneId = zoneId,
        dailyRefreshMinutes = dailyRefreshMinutes,
        weeklyRefreshDayIso = weeklyRefreshDayIso,
        weeklyRefreshMinutes = weeklyRefreshMinutes,
    )
}

/**
 * Shared permanent-deletion core (UF-007/UF-009). Must run inside one
 * `withDatasetTransaction`; rows are removed in foreign-key dependency order so a
 * failure rolls back everything — never an order without its receipts or vice versa.
 * Attachment *files* are not touched here; `cleanupOrphans()` (app start and after
 * deletions) removes files whose database references are gone, so a failed file pass
 * can always recover.
 */
internal suspend fun deleteOrderCascade(
    orderId: String,
    orderDao: OrderDao,
    taskDao: TaskDao,
    occurrenceDao: OccurrenceDao,
    receiptDao: ReceiptDao,
    attachmentDao: AttachmentDao,
    completionBatchDao: CompletionBatchDao,
    hostingSessionDao: HostingSessionDao,
): OrderDeletionSummary {
    val occurrences = occurrenceDao.findForOrder(orderId)
    val receipts = receiptDao.findForOrder(orderId)
    val attachments = attachmentDao.findForOrder(orderId)
    val periods = orderDao.findPeriods(orderId)
    val summary = OrderDeletionSummary(
        orderId = orderId,
        occurrenceCount = occurrences.size,
        receiptCount = receipts.size,
        receiptSumCents = receipts
            .filter { it.status == com.daigan.record.data.model.ReceiptStatus.ACTIVE }
            .sumOf { it.amountCents },
        attachmentCount = attachments.size,
        periodCount = periods.size,
    )
    attachmentDao.deleteForOrder(orderId)
    receiptDao.deleteCorrectionsForOrder(orderId)
    receiptDao.deleteForOrder(orderId)
    completionBatchDao.deleteItemsForOrder(orderId)
    completionBatchDao.deleteBatchesForOrder(orderId)
    completionBatchDao.deleteEmptyHostingAllBatches()
    occurrenceDao.deleteForOrder(orderId)
    // Count-based rounds reference the order, so they go before the order row.
    hostingSessionDao.deleteForOrder(orderId)
    orderDao.deletePriceChanges(orderId)
    orderDao.deleteTasks(orderId)
    orderDao.deletePeriods(orderId)
    orderDao.deleteCreationIdentity(orderId)
    orderDao.deleteOrder(orderId)
    return summary
}
