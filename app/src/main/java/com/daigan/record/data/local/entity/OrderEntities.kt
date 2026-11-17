package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * An order belongs to exactly one game account. Renewal never changes the order id;
 * a hosting order owns a sequence of [HostingPeriodEntity] snapshots instead.
 *
 * [priceCents] is the negotiated total for a short order and null for hosting orders,
 * where each period stores its own price. Short orders keep [completedAt] in sync with
 * their tasks; hosting completion lives on occurrence records.
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = GameAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId"], name = "index_orders_accountId"),
        Index(value = ["accountId", "type"], name = "index_orders_accountId_type"),
        Index(value = ["type", "completedAt"], name = "index_orders_type_completedAt"),
        Index(value = ["archivedAt"], name = "index_orders_archivedAt"),
    ],
)
data class OrderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: OrderType,
    val title: String,
    val priceCents: Long?,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String?,
    val gameZoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    /**
     * Truthful creation provenance copied from the selected template at creation.
     *
     * Plain text, no foreign key: the template may later be edited, replaced or deleted
     * and this label must stay exactly as the order was created. Legacy orders (and
     * orders created without a template) keep NULL — no historical version is invented.
     */
    val sourceTemplateId: String? = null,
    val sourceTemplateName: String? = null,
    val sourceTemplateVersion: String? = null,
    val cancelledAt: Instant? = null,
    val archivedAt: Instant? = null,
    /**
     * Persisted hosting mode (schema 13). NULL means "written before the mode existed"
     * and reads as date-based hosting; no historical order is reinterpreted.
     */
    val hostingMode: HostingMode? = null,
    /** Agreed number of check-in rounds; NULL unless the order is count-based. */
    val targetSessions: Int? = null,
    /**
     * Optional business colour of **this order** (UF-028), stored as a packed ARGB int.
     *
     * Colour belongs to the order, not to the game account: two orders on the same
     * account may carry different colours, and NULL means "no colour chosen" (the UI
     * then falls back to a neutral, order-derived tint). Schema 14; schema 13 rows read
     * as NULL and are never assigned a colour from the old account-level preference.
     */
    val orderColor: Int? = null,
    /**
     * 每月刷新日（逗号分隔 1–31）。Schema 15 起由便携契约承载：网页端一直有该字段，
     * NULL 表示按周刷新。
     */
    val monthlyRefreshDays: String? = null,
)

/**
 * One check-in round of a count-based hosting order.
 *
 * `sessionIndex` is the durable identity of the round (1-based, unique per order), which
 * is why repeated taps or concurrent completion can never count the same round twice.
 * Deleting an order removes its rounds in the same transaction; reopening a round keeps
 * the row and only clears [completedAt].
 */
@Entity(
    tableName = "hosting_sessions",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_hosting_sessions_orderId"),
        Index(
            value = ["orderId", "sessionIndex"],
            unique = true,
            name = "index_hosting_sessions_orderId_sessionIndex",
        ),
        Index(value = ["periodId"], name = "index_hosting_sessions_periodId"),
    ],
)
data class HostingSessionEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val sessionIndex: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
    val completionBatchId: String? = null,
    /**
     * 归属期次（schema 15，NOT NULL）。必须属于同一订单且该期次 serviceCount 非空；
     * Android 13/14 的计次订单恰好只有一个期次，因此历史行可由 14→15 迁移证明归属。
     */
    val periodId: String,
)

/**
 * One hosting period. Dates may be explicitly corrected with history guards.
 * The price and refresh rules are immutable snapshots: a later
 * order-level change or a renewal must never rewrite an older period's terms.
 */
@Entity(
    tableName = "hosting_periods",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_hosting_periods_orderId"),
        Index(
            value = ["orderId", "periodIndex"],
            unique = true,
            name = "index_hosting_periods_orderId_periodIndex",
        ),
        Index(
            value = ["orderId", "startDate"],
            unique = true,
            name = "index_hosting_periods_orderId_startDate",
        ),
        Index(
            value = ["renewalOperationId"],
            unique = true,
            name = "index_hosting_periods_renewalOperationId",
        ),
    ],
)
data class HostingPeriodEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val periodIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val priceCents: Long,
    val gameZoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val createdAt: Instant,
    val renewalOperationId: String?,
    val serviceDays: Int? = null,
    val serviceWeekdays: String? = null,
    /** 每月刷新日（逗号分隔 1–31）；NULL 表示按周刷新。Schema 15。 */
    val monthlyRefreshDays: String? = null,
    /** 按次数模式：本**期次**的总次数目标。Schema 15；NULL 表示按日期/工作日模式。 */
    val serviceCount: Int? = null,
)

/**
 * A task definition snapshot. Short-order tasks have a null [periodId]; every hosting
 * period gets independent task rows, so renewal never mutates the previous period.
 */
@Entity(
    tableName = "task_definitions",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HostingPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["periodId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["completionBatchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_task_definitions_orderId"),
        Index(value = ["periodId"], name = "index_task_definitions_periodId"),
        Index(value = ["completionBatchId"], name = "index_task_definitions_completionBatchId"),
    ],
)
data class TaskDefinitionEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val periodId: String?,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val currentValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    /** ISO day-of-week numbers (1 = Monday) joined with commas; null when unused. */
    val weekdays: String?,
    val everyNDays: Int?,
    val sortOrder: Int,
    val completedAt: Instant?,
    val completionBatchId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val monthDay: Int? = null,
    val monthDays: String? = null,
    val retiredAt: Instant? = null,
    val activeFrom: LocalDate? = null,
    /** 任务生效截止日（schema 15）；NULL 表示随期次结束。 */
    val activeUntil: LocalDate? = null,
    /** 数值口径（schema 15）；NULL 读作 PER_OCCURRENCE。 */
    val numericMode: NumericMode? = null,
    /** 任务自身颜色（不透明 ARGB）；NULL 表示继承订单色。 */
    val colorArgb: Int? = null,
)

/**
 * One persisted occurrence. The unique `(taskDefinitionId, occurrenceKey)` index makes
 * repeated generation safe: a daily task key includes the game date, a weekly task key
 * includes the weekly window start, so tapping twice or reloading cannot duplicate work.
 */
@Entity(
    tableName = "task_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["completionBatchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskDefinitionId"], name = "index_task_occurrences_taskDefinitionId"),
        Index(
            value = ["taskDefinitionId", "occurrenceKey"],
            unique = true,
            name = "index_task_occurrences_taskDefinitionId_occurrenceKey",
        ),
        Index(value = ["gameDate"], name = "index_task_occurrences_gameDate"),
        Index(value = ["completionBatchId"], name = "index_task_occurrences_completionBatchId"),
    ],
)
data class TaskOccurrenceEntity(
    @PrimaryKey val id: String,
    val taskDefinitionId: String,
    val occurrenceKey: String,
    /** Game date for daily/weekday/every-N tasks; window start for weekly tasks. */
    val gameDate: LocalDate,
    val windowStart: LocalDate?,
    val windowEnd: LocalDate?,
    val completedAt: Instant?,
    val recordedAt: Instant?,
    val backfilled: Boolean,
    val completionBatchId: String?,
    val value: BigDecimal?,
    val createdAt: Instant,
)

/** Audit header for a bulk completion. [operationId] makes retries idempotent. */
@Entity(
    tableName = "completion_batches",
    indices = [
        Index(
            value = ["operationId"],
            unique = true,
            name = "index_completion_batches_operationId",
        ),
    ],
)
data class CompletionBatchEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val scope: BatchScope,
    val targetId: String?,
    val gameDate: LocalDate?,
    val createdAt: Instant,
    val undoneAt: Instant?,
)

/**
 * Exact per-entity change set of a batch. Undo compares the *applied* values with the
 * current row; a later independent edit is a conflict and is never overwritten.
 */
@Entity(
    tableName = "completion_batch_items",
    foreignKeys = [
        ForeignKey(
            entity = CompletionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batchId"], name = "index_completion_batch_items_batchId"),
        Index(value = ["occurrenceId"], name = "index_completion_batch_items_occurrenceId"),
        Index(
            value = ["taskDefinitionId"],
            name = "index_completion_batch_items_taskDefinitionId",
        ),
    ],
)
data class CompletionBatchItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val itemType: BatchItemType,
    val occurrenceId: String?,
    val taskDefinitionId: String?,
    val previousCompletedAt: Instant?,
    val previousRecordedAt: Instant?,
    val previousBackfilled: Boolean?,
    val previousValue: BigDecimal?,
    val previousCompletionBatchId: String?,
    val appliedCompletedAt: Instant?,
    val appliedRecordedAt: Instant?,
    val appliedBackfilled: Boolean?,
    val appliedValue: BigDecimal?,
    val appliedCompletionBatchId: String?,
)

/**
 * Screenshot metadata. The image itself lives in application-private storage; only the
 * relative path is stored, never Base64. Exactly one of the two owner columns is set.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["taskDefinitionId"],
            name = "index_attachments_taskDefinitionId",
        ),
        Index(value = ["occurrenceId"], name = "index_attachments_occurrenceId"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val taskDefinitionId: String?,
    val occurrenceId: String?,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

/**
 * Audit row for one agreed-price correction (UF-012). The old and new amounts are
 * snapshots; actual receipt rows are never rewritten by a price change. Rows stay
 * RESTRICT-attached to their order and period so an audited order cannot vanish
 * silently; deletion paths must remove the audit rows first inside their transaction.
 */
@Entity(
    tableName = "order_price_changes",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HostingPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["periodId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["orderId"], name = "index_order_price_changes_orderId"),
        Index(value = ["periodId"], name = "index_order_price_changes_periodId"),
        Index(
            value = ["operationId"],
            unique = true,
            name = "index_order_price_changes_operationId",
        ),
    ],
)
data class OrderPriceChangeEntity(
    @PrimaryKey val id: String,
    /** Idempotency key of the price-edit command. */
    val operationId: String,
    val orderId: String,
    /** Null for a short-order price; set for a hosting period price. */
    val periodId: String?,
    val previousPriceCents: Long,
    val newPriceCents: Long,
    val reason: String?,
    val createdAt: Instant,
)
