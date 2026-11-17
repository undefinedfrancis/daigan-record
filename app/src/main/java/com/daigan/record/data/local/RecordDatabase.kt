package com.daigan.record.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daigan.record.data.local.converter.BigDecimalConverters
import com.daigan.record.data.local.converter.InstantConverters
import com.daigan.record.data.local.converter.LocalDateConverters
import com.daigan.record.data.local.converter.LoginMethodConverters
import com.daigan.record.data.local.converter.OrderEnumConverters
import com.daigan.record.data.local.dao.AccountCategoryDao
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.CompletionBatchDao
import com.daigan.record.data.local.dao.CreationOperationDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.HostingSessionDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.dao.ReminderDeliveryDao
import com.daigan.record.data.local.dao.ReminderSettingsDao
import com.daigan.record.data.local.dao.ServerOptionDao
import com.daigan.record.data.local.dao.AppSettingsDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.dao.TaskProgressEventDao
import com.daigan.record.data.local.dao.TemplateDao
import com.daigan.record.data.local.entity.AccountCategoryEntity
import com.daigan.record.data.local.entity.AppSettingsEntity
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.HostingSessionEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OrderPriceChangeEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.OwnerNumberSequenceEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.ReminderDeliveryEntity
import com.daigan.record.data.local.entity.ReminderSettingsEntity
import com.daigan.record.data.local.entity.ServerOptionEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.entity.TaskProgressEventEntity
import com.daigan.record.data.local.entity.TemplateCategoryEntity
import com.daigan.record.data.local.entity.TemplatePricePresetEntity
import com.daigan.record.data.local.entity.TemplateSeedStateEntity
import com.daigan.record.data.local.entity.TemplateTaskEntity

/**
 * Current schema version, defined next to the migrations so tests do not hard code it.
 *
 * 1 -> 2 added the archive batch provenance columns.
 * 2 -> 3 adds orders, hosting periods, task snapshots, occurrences, completion batch
 *       audit rows and screenshot attachment metadata. No existing table is rewritten.
 * 3 -> 4 adds receipts and receipt corrections. Existing orders and periods keep no
 *       receipt rows: a paid amount is never inferred from completion or from a price.
 * 4 -> 5 adds `order_creation_operations`, the stored result of a whole create-order
 *       command. Existing orders keep no operation row: they were created before the
 *       command was idempotent, and inventing identities for them would be dishonest.
 * 5 -> 6 adds the editable game-template library, the built-in seed markers, an
 *       optional account default template and nullable order provenance columns.
 *       Existing orders, periods, tasks, occurrences, receipts, corrections and
 *       creation-operation rows are untouched: legacy rows keep NULL provenance and
 *       no template is invented for them.
 * 6 -> 7 adds the persisted reminder preferences, the local-only dataset identity used
 *       by the restore commit point, and the reminder delivery log. Existing rows are
 *       untouched; reminders stay disabled until the user enables them.
 * 7 -> 8 adds nullable order cancellation and monthly task day fields, retaining legacy rows.
 * 8 -> 9 adds the optional account login device, per-game server options and the
 *       order price-change audit table. Existing rows are untouched; legacy accounts
 *       keep a NULL device and both new tables start empty.
 * 9 -> 10 adds the optional per-template price presets. Existing rows are untouched;
 *       legacy templates simply have no presets, which reads as an empty list.
 * 12 -> 13 adds the explicit hosting mode and agreed round count (打卡式托管, UF-033),
 *       the same defaults on templates (UF-034) and the `hosting_sessions` round table.
 *       All new columns are nullable, so legacy orders/templates restore as date-based
 *       hosting; no historical order, period, task, receipt or screenshot is rewritten.
 * 13 -> 14 adds the optional order-level colour (UF-028). Nullable, so shipped schema 13
 *       rows read as "not coloured"; the old account-level preference is deliberately
 *       not copied onto orders.
 * 14 -> 15 aligns Android with the shared portable contract (UF-036/037/038):
 *       account categories + account assignment, template/category service defaults,
 *       per-period count targets, order-global round ownership, monthly refresh days,
 *       cumulative numeric mode + append-only progress events, and task/order colours.
 *       Every added column except `hosting_sessions.periodId` is nullable, so legacy rows
 *       keep their exact meaning; `periodId` is filled from the provable single period of
 *       a count order (see `android-count-period-backfill`). No occurrence value is
 *       reinterpreted as a cumulative event, and no row is deleted.
 */
const val RECORD_DATABASE_VERSION = 15

/**
 * The single source of truth for business data.
 *
 * Version 7 is Phase 5: reminder preferences, the restore commit marker/dataset identity
 * and the reminder delivery log. Business records keep exactly the Phase 1–4 shape.
 */
@Database(
    entities = [
        OwnerEntity::class,
        GameAccountEntity::class,
        OwnerNumberSequenceEntity::class,
        OrderEntity::class,
        HostingPeriodEntity::class,
        HostingSessionEntity::class,
        TaskDefinitionEntity::class,
        TaskOccurrenceEntity::class,
        CompletionBatchEntity::class,
        CompletionBatchItemEntity::class,
        AttachmentEntity::class,
        ReceiptEntity::class,
        ReceiptCorrectionEntity::class,
        OrderCreationOperationEntity::class,
        GameTemplateEntity::class,
        TemplateCategoryEntity::class,
        TemplateTaskEntity::class,
        TemplatePricePresetEntity::class,
        TemplateSeedStateEntity::class,
        ReminderSettingsEntity::class,
        AppSettingsEntity::class,
        ReminderDeliveryEntity::class,
        ServerOptionEntity::class,
        OrderPriceChangeEntity::class,
        AccountCategoryEntity::class,
        TaskProgressEventEntity::class,
    ],
    version = RECORD_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(
    InstantConverters::class,
    LoginMethodConverters::class,
    LocalDateConverters::class,
    BigDecimalConverters::class,
    OrderEnumConverters::class,
)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao

    abstract fun gameAccountDao(): GameAccountDao

    abstract fun orderDao(): OrderDao

    abstract fun taskDao(): TaskDao

    abstract fun occurrenceDao(): OccurrenceDao

    abstract fun completionBatchDao(): CompletionBatchDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun receiptDao(): ReceiptDao

    abstract fun creationOperationDao(): CreationOperationDao

    abstract fun serverOptionDao(): ServerOptionDao

    abstract fun templateDao(): TemplateDao

    abstract fun reminderSettingsDao(): ReminderSettingsDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun reminderDeliveryDao(): ReminderDeliveryDao

    abstract fun hostingSessionDao(): HostingSessionDao

    abstract fun accountCategoryDao(): AccountCategoryDao

    abstract fun taskProgressEventDao(): TaskProgressEventDao

    companion object {
        const val NAME = "daigan-record.db"

        /**
         * Adds the archive batch columns introduced by the phase 1 fix.
         *
         * Both columns are nullable and stay NULL for rows written by version 1. That
         * is deliberate: the old schema cannot tell an account archived together with
         * its owner apart from an account archived on its own at the same millisecond,
         * so no provenance is invented during the migration. The conservative policy
         * for such legacy rows is documented in docs/data-model.md.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owners ADD COLUMN archiveBatchId TEXT")
                db.execSQL("ALTER TABLE game_accounts ADD COLUMN archiveBatchId TEXT")
            }
        }

        /**
         * Phase 2 tables. Existing owner/account rows and every archive column are
         * untouched. The DDL mirrors the Room entities exactly (names, nullability,
         * foreign keys and explicitly named indices) so schema validation is strict.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `orders` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `priceCents` INTEGER,
                        `paymentMode` TEXT NOT NULL,
                        `deadlineDate` TEXT,
                        `notes` TEXT,
                        `gameZoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`accountId`) REFERENCES `game_accounts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_accountId` ON `orders` (`accountId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_accountId_type` " +
                        "ON `orders` (`accountId`, `type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_type_completedAt` " +
                        "ON `orders` (`type`, `completedAt`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hosting_periods` (
                        `id` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodIndex` INTEGER NOT NULL,
                        `startDate` TEXT NOT NULL,
                        `endDate` TEXT NOT NULL,
                        `priceCents` INTEGER NOT NULL,
                        `gameZoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `renewalOperationId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hosting_periods_orderId` " +
                        "ON `hosting_periods` (`orderId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_orderId_periodIndex` " +
                        "ON `hosting_periods` (`orderId`, `periodIndex`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_orderId_startDate` " +
                        "ON `hosting_periods` (`orderId`, `startDate`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_renewalOperationId` " +
                        "ON `hosting_periods` (`renewalOperationId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `completion_batches` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `targetId` TEXT,
                        `gameDate` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `undoneAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_completion_batches_operationId` " +
                        "ON `completion_batches` (`operationId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_definitions` (
                        `id` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodId` TEXT,
                        `title` TEXT NOT NULL,
                        `progressType` TEXT NOT NULL,
                        `startValue` TEXT NOT NULL,
                        `targetValue` TEXT NOT NULL,
                        `currentValue` TEXT NOT NULL,
                        `unit` TEXT,
                        `recurrence` TEXT NOT NULL,
                        `weekdays` TEXT,
                        `everyNDays` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `completionBatchId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`periodId`) REFERENCES `hosting_periods`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`completionBatchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_orderId` " +
                        "ON `task_definitions` (`orderId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_periodId` " +
                        "ON `task_definitions` (`periodId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_completionBatchId` " +
                        "ON `task_definitions` (`completionBatchId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_occurrences` (
                        `id` TEXT NOT NULL,
                        `taskDefinitionId` TEXT NOT NULL,
                        `occurrenceKey` TEXT NOT NULL,
                        `gameDate` TEXT NOT NULL,
                        `windowStart` TEXT,
                        `windowEnd` TEXT,
                        `completedAt` INTEGER,
                        `recordedAt` INTEGER,
                        `backfilled` INTEGER NOT NULL,
                        `completionBatchId` TEXT,
                        `value` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`completionBatchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_task_occurrences_taskDefinitionId` " +
                        "ON `task_occurrences` (`taskDefinitionId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_task_occurrences_taskDefinitionId_occurrenceKey` " +
                        "ON `task_occurrences` (`taskDefinitionId`, `occurrenceKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_occurrences_gameDate` " +
                        "ON `task_occurrences` (`gameDate`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_occurrences_completionBatchId` " +
                        "ON `task_occurrences` (`completionBatchId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `completion_batch_items` (
                        `id` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `occurrenceId` TEXT,
                        `taskDefinitionId` TEXT,
                        `previousCompletedAt` INTEGER,
                        `previousRecordedAt` INTEGER,
                        `previousBackfilled` INTEGER,
                        `previousValue` TEXT,
                        `previousCompletionBatchId` TEXT,
                        `appliedCompletedAt` INTEGER,
                        `appliedRecordedAt` INTEGER,
                        `appliedBackfilled` INTEGER,
                        `appliedValue` TEXT,
                        `appliedCompletionBatchId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`batchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `task_occurrences`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_completion_batch_items_batchId` " +
                        "ON `completion_batch_items` (`batchId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_completion_batch_items_occurrenceId` " +
                        "ON `completion_batch_items` (`occurrenceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_completion_batch_items_taskDefinitionId` " +
                        "ON `completion_batch_items` (`taskDefinitionId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachments` (
                        `id` TEXT NOT NULL,
                        `taskDefinitionId` TEXT,
                        `occurrenceId` TEXT,
                        `relativePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `task_occurrences`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_taskDefinitionId` " +
                        "ON `attachments` (`taskDefinitionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_occurrenceId` " +
                        "ON `attachments` (`occurrenceId`)",
                )
            }
        }

        /**
         * Phase 3 receipts. Only two new tables are created; every Phase 2 row stays
         * exactly as it was. No receipt is fabricated for an existing order or period:
         * a price is a promise, not a payment.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `receipts` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodId` TEXT,
                        `amountCents` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `channel` TEXT NOT NULL,
                        `note` TEXT,
                        `status` TEXT NOT NULL,
                        `replacedByReceiptId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`periodId`) REFERENCES `hosting_periods`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_operationId` " +
                        "ON `receipts` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_orderId` " +
                        "ON `receipts` (`orderId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_periodId` " +
                        "ON `receipts` (`periodId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_receivedAt` " +
                        "ON `receipts` (`receivedAt`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `receipt_corrections` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `receiptId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `reason` TEXT,
                        `previousAmountCents` INTEGER NOT NULL,
                        `previousReceivedAt` INTEGER NOT NULL,
                        `previousChannel` TEXT NOT NULL,
                        `previousNote` TEXT,
                        `newReceiptId` TEXT,
                        `newAmountCents` INTEGER,
                        `newReceivedAt` INTEGER,
                        `newChannel` TEXT,
                        `newNote` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`newReceiptId`) REFERENCES `receipts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_receipt_corrections_operationId` " +
                        "ON `receipt_corrections` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipt_corrections_receiptId` " +
                        "ON `receipt_corrections` (`receiptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipt_corrections_newReceiptId` " +
                        "ON `receipt_corrections` (`newReceiptId`)",
                )
            }
        }

        /**
         * Phase 3 repair: persistent identity of a whole create-order command.
         *
         * Only one new table is created; orders, periods, receipts and every Phase 2
         * row stay untouched. Existing orders get no synthetic operation row, so their
         * historical creation cannot be mistaken for a retry of a new command.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `order_creation_operations` (
                        `operationId` TEXT NOT NULL,
                        `orderType` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `receiptId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`operationId`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_order_creation_operations_operationId` " +
                        "ON `order_creation_operations` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_order_creation_operations_orderId` " +
                        "ON `order_creation_operations` (`orderId`)",
                )
            }
        }

        /**
         * Phase 4 template library. Only new tables and nullable columns are added.
         *
         * No template content is inserted by the migration: built-ins are seeded through
         * the repository with a durable seed marker, so an upgrade never re-creates a
         * template the user already deleted and never overwrites an edited one. Existing
         * orders keep NULL provenance and existing accounts keep no default template;
         * no historical template version is fabricated.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `game_templates` (
                        `id` TEXT NOT NULL,
                        `gameName` TEXT NOT NULL,
                        `templateVersion` TEXT NOT NULL,
                        `gameVersionNotes` TEXT,
                        `zoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `origin` TEXT NOT NULL,
                        `builtInKey` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_game_templates_builtInKey` ON `game_templates` (`builtInKey`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_categories` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`templateId`) REFERENCES `game_templates`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_categories_templateId` " +
                        "ON `template_categories` (`templateId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_tasks` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `progressType` TEXT NOT NULL,
                        `startValue` TEXT NOT NULL,
                        `targetValue` TEXT NOT NULL,
                        `unit` TEXT,
                        `recurrence` TEXT NOT NULL,
                        `weekdays` TEXT,
                        `everyNDays` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`templateId`) REFERENCES `game_templates`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`categoryId`) REFERENCES `template_categories`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_tasks_templateId` " +
                        "ON `template_tasks` (`templateId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_tasks_categoryId` " +
                        "ON `template_tasks` (`categoryId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_seed_state` (
                        `seedKey` TEXT NOT NULL,
                        `appliedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`seedKey`)
                    )
                    """.trimIndent(),
                )

                db.execSQL("ALTER TABLE `game_accounts` ADD COLUMN `defaultTemplateId` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateId` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateName` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateVersion` TEXT")
            }
        }

        /**
         * Phase 5 local state. Only new tables are created; no business row is rewritten
         * and no reminder is enabled on the user's behalf.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminder_settings` (
                        `id` INTEGER NOT NULL,
                        `shortDeadlineEnabled` INTEGER NOT NULL,
                        `shortDeadlineMinutes` INTEGER NOT NULL,
                        `hostingExpiryEnabled` INTEGER NOT NULL,
                        `hostingExpiryMinutes` INTEGER NOT NULL,
                        `unfinishedTasksEnabled` INTEGER NOT NULL,
                        `unfinishedTasksMinutes` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_settings` (
                        `id` INTEGER NOT NULL,
                        `datasetInstanceId` TEXT NOT NULL,
                        `lastRestoreId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminder_deliveries` (
                        `category` TEXT NOT NULL,
                        `day` TEXT NOT NULL,
                        `deliveredAt` INTEGER NOT NULL,
                        PRIMARY KEY(`category`, `day`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v1.6 adds nullable fields; existing business records keep their identity. */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `cancelledAt` INTEGER")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `monthDay` INTEGER")
                db.execSQL("ALTER TABLE `template_tasks` ADD COLUMN `monthDay` INTEGER")
            }
        }

        /**
         * v1.7 adds the account login device, per-game server options and the price
         * audit table. Only new surface: no business row is rewritten, and historical
         * accounts keep a NULL device.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_accounts` ADD COLUMN `loginDevice` TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `game_server_options` (
                        `id` TEXT NOT NULL,
                        `gameName` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_game_server_options_gameName_sortOrder` " +
                        "ON `game_server_options` (`gameName`, `sortOrder`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `order_price_changes` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodId` TEXT,
                        `previousPriceCents` INTEGER NOT NULL,
                        `newPriceCents` INTEGER NOT NULL,
                        `reason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`periodId`) REFERENCES `hosting_periods`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_order_price_changes_orderId` " +
                        "ON `order_price_changes` (`orderId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_order_price_changes_periodId` " +
                        "ON `order_price_changes` (`periodId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_order_price_changes_operationId` " +
                        "ON `order_price_changes` (`operationId`)",
                )
            }
        }

        /**
         * v1.7 adds the optional per-template price presets. Only new surface: no
         * business row is rewritten, and a legacy template simply has no presets.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_price_presets` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `amountCents` INTEGER NOT NULL,
                        `label` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`templateId`) REFERENCES `game_templates`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_template_price_presets_templateId` " +
                        "ON `template_price_presets` (`templateId`)",
                )
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_accounts` ADD COLUMN `resourceNotes` TEXT")
            }
        }

        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hosting_periods` ADD COLUMN `serviceDays` INTEGER")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `archivedAt` INTEGER")
                db.execSQL("ALTER TABLE `hosting_periods` ADD COLUMN `serviceWeekdays` TEXT")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `monthDays` TEXT")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `retiredAt` INTEGER")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `activeFrom` TEXT")
                db.execSQL("ALTER TABLE `template_tasks` ADD COLUMN `monthDays` TEXT")
                db.execSQL("UPDATE `task_definitions` SET `monthDays` = CAST(`monthDay` AS TEXT) WHERE `monthDay` IS NOT NULL")
                db.execSQL("UPDATE `template_tasks` SET `monthDays` = CAST(`monthDay` AS TEXT) WHERE `monthDay` IS NOT NULL")
            }
        }

        /**
         * v1.8.4 Stage 1: count-based hosting (UF-033/UF-034).
         *
         * Only nullable columns and one new table. Schema 12 has shipped, so nothing is
         * rewritten: every existing order/template keeps NULL mode (date-based) and the
         * new table starts empty.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `hostingMode` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `targetSessions` INTEGER")
                db.execSQL("ALTER TABLE `game_templates` ADD COLUMN `hostingMode` TEXT")
                db.execSQL("ALTER TABLE `game_templates` ADD COLUMN `targetSessions` INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hosting_sessions` (
                        `id` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `sessionIndex` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `completionBatchId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hosting_sessions_orderId` " +
                        "ON `hosting_sessions` (`orderId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_sessions_orderId_sessionIndex` " +
                        "ON `hosting_sessions` (`orderId`, `sessionIndex`)",
                )
            }
        }

        /**
         * v1.8.4 Stage 2: optional order-level colour (UF-028).
         *
         * One nullable column. Schema 13 has shipped, so nothing is rewritten: existing
         * orders keep NULL ("no colour") and are never recoloured from the removed
         * account-level Today preference.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `orderColor` INTEGER")
            }
        }

        /**
         * v1.8.5 (portable schema 15): the shared interop contract.
         *
         * One migration for the whole contract delta. Nothing is destroyed:
         * - new tables `account_categories` and `task_progress_events` start empty;
         * - every other change is a nullable column add, except `hosting_sessions.periodId`;
         * - `hosting_sessions` is rebuilt with `periodId TEXT NOT NULL` because SQLite
         *   cannot add a NOT NULL column without a default, and a fabricated default
         *   would invent ownership;
         * - per-period count targets are backfilled only where ownership is provable
         *   (a valid Android count order owns exactly one period).
         *
         * Any ambiguity aborts the migration, so the transaction rolls back and the
         * pre-migration database stays usable. There is no destructive fallback.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 0. Refuse ambiguous round ownership before touching any schema.
                val unresolvable = mutableListOf<String>()
                db.query(
                    "SELECT o.id, " +
                        "(SELECT COUNT(*) FROM hosting_periods p WHERE p.orderId = o.id), " +
                        "(SELECT COUNT(*) FROM hosting_sessions s WHERE s.orderId = o.id) " +
                        "FROM orders o WHERE o.hostingMode = 'COUNT_BASED'",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val periodCount = cursor.getInt(1)
                        val sessionCount = cursor.getInt(2)
                        if (sessionCount > 0 && periodCount != 1) {
                            unresolvable += cursor.getString(0)
                        }
                    }
                }
                if (unresolvable.isNotEmpty()) {
                    throw IllegalStateException(
                        "打卡轮次归属无法证明（订单 ${unresolvable.joinToString()}），" +
                            "迁移已中止，原始资料保持不变。",
                    )
                }

                // 1. Global account categories (UF-037). No rows are created.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `account_categories` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_categories_name` " +
                        "ON `account_categories` (`name`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_account_categories_sortOrder` " +
                        "ON `account_categories` (`sortOrder`)",
                )

                // 2. Nullable column additions (all read as "not set" for legacy rows).
                db.execSQL("ALTER TABLE `game_accounts` ADD COLUMN `categoryId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_game_accounts_categoryId` " +
                        "ON `game_accounts` (`categoryId`)",
                )
                db.execSQL("ALTER TABLE `game_templates` ADD COLUMN `serviceWeekdays` TEXT")
                db.execSQL("ALTER TABLE `game_templates` ADD COLUMN `defaultOrderTitle` TEXT")
                db.execSQL("ALTER TABLE `template_categories` ADD COLUMN `hostingMode` TEXT")
                db.execSQL("ALTER TABLE `template_categories` ADD COLUMN `targetSessions` INTEGER")
                db.execSQL("ALTER TABLE `template_categories` ADD COLUMN `serviceWeekdays` TEXT")
                db.execSQL("ALTER TABLE `template_tasks` ADD COLUMN `activeFrom` TEXT")
                db.execSQL("ALTER TABLE `template_tasks` ADD COLUMN `activeUntil` TEXT")
                db.execSQL("ALTER TABLE `template_tasks` ADD COLUMN `numericMode` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `monthlyRefreshDays` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_archivedAt` " +
                        "ON `orders` (`archivedAt`)",
                )
                db.execSQL("ALTER TABLE `hosting_periods` ADD COLUMN `monthlyRefreshDays` TEXT")
                db.execSQL("ALTER TABLE `hosting_periods` ADD COLUMN `serviceCount` INTEGER")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `activeUntil` TEXT")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `numericMode` TEXT")
                db.execSQL("ALTER TABLE `task_definitions` ADD COLUMN `colorArgb` INTEGER")

                // 3. Per-period count target (android-count-period-backfill, step 2).
                //    Only count orders are touched; a DATE_RANGE period never gets a target.
                db.execSQL(
                    "UPDATE `hosting_periods` SET `serviceCount` = (" +
                        "SELECT o.`targetSessions` FROM `orders` o " +
                        "WHERE o.`id` = `hosting_periods`.`orderId`) " +
                        "WHERE `orderId` IN " +
                        "(SELECT `id` FROM `orders` WHERE `hostingMode` = 'COUNT_BASED')",
                )

                // 4. Rebuild hosting_sessions with periodId NOT NULL, preserving every
                //    business value and copying the provable owning period.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hosting_sessions_new` (" +
                        "`id` TEXT NOT NULL, `orderId` TEXT NOT NULL, " +
                        "`sessionIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, `completionBatchId` TEXT, " +
                        "`periodId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "INSERT INTO `hosting_sessions_new` " +
                        "(`id`, `orderId`, `sessionIndex`, `createdAt`, `completedAt`, " +
                        "`completionBatchId`, `periodId`) " +
                        "SELECT s.`id`, s.`orderId`, s.`sessionIndex`, s.`createdAt`, " +
                        "s.`completedAt`, s.`completionBatchId`, " +
                        "(SELECT p.`id` FROM `hosting_periods` p " +
                        "WHERE p.`orderId` = s.`orderId` " +
                        "ORDER BY p.`periodIndex` ASC, p.`id` ASC LIMIT 1) " +
                        "FROM `hosting_sessions` s",
                )
                db.execSQL("DROP TABLE `hosting_sessions`")
                db.execSQL("ALTER TABLE `hosting_sessions_new` RENAME TO `hosting_sessions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hosting_sessions_orderId` " +
                        "ON `hosting_sessions` (`orderId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_sessions_orderId_sessionIndex` " +
                        "ON `hosting_sessions` (`orderId`, `sessionIndex`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hosting_sessions_periodId` " +
                        "ON `hosting_sessions` (`periodId`)",
                )

                // 5. Append-only cumulative events. No rows are invented: existing
                //    occurrence values are never reinterpreted as increments or totals.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_progress_events` (" +
                        "`id` TEXT NOT NULL, `taskDefinitionId` TEXT NOT NULL, " +
                        "`operationId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`scopeType` TEXT NOT NULL, `scopePeriodId` TEXT, " +
                        "`scopeSessionId` TEXT, `gameDate` TEXT NOT NULL, " +
                        "`totalValue` TEXT, `sequence` INTEGER NOT NULL, " +
                        "`correctsEventId` TEXT, `recordedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`correctsEventId`) REFERENCES `task_progress_events`(`id`) " +
                        "ON UPDATE CASCADE ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_progress_events_taskDefinitionId` " +
                        "ON `task_progress_events` (`taskDefinitionId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_task_progress_events_operationId` " +
                        "ON `task_progress_events` (`operationId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_task_progress_events_taskDefinitionId_sequence` " +
                        "ON `task_progress_events` (`taskDefinitionId`, `sequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_progress_events_scopePeriodId` " +
                        "ON `task_progress_events` (`scopePeriodId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_progress_events_scopeSessionId` " +
                        "ON `task_progress_events` (`scopeSessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_progress_events_gameDate` " +
                        "ON `task_progress_events` (`gameDate`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_progress_events_correctsEventId` " +
                        "ON `task_progress_events` (`correctsEventId`)",
                )
            }
        }

        /** Every migration the app must run; the list is also used by tests. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        )
    }
}
