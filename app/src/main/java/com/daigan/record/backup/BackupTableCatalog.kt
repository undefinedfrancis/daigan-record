package com.daigan.record.backup

/**
 * The durable tables of the current schema, in dependency order (parents first).
 *
 * The list is explicit instead of derived from `sqlite_master`: a backup must contain a
 * known, reviewable set of tables, and a restore must reject a file that does not match
 * it exactly. Adding a table in a later phase is therefore a visible change here.
 *
 * Two independent roles are served by this single declaration:
 * 1. the export/import column catalog (what a `.dgbk` contains, in order);
 * 2. the **product-side contract metadata** emitted by [ContractMetadata] for the
 *    interop checker. Types, nullability, primary keys, foreign keys, unique constraints,
 *    indexes, enums and defaults therefore cannot drift from what the product really
 *    reads and writes.
 *
 * `app_settings` (dataset identity and restore commit marker) and `reminder_deliveries`
 * (scheduling de-duplication log) are **not** part of a backup: both are reconstructible
 * local state, and restoring them would let an old installation identity or an old
 * "already notified" marker leak into the restored dataset. Everything the user can
 * observe — including reminder preferences — is included.
 */
object BackupTableCatalog {

    enum class ColumnType { TEXT, INTEGER }

    data class Column(
        val name: String,
        val type: ColumnType,
        val nullable: Boolean,
        /** Enum declared by the interop contract for this column, when any. */
        val enumName: String? = null,
        /** Non-null default declared by the contract (null for nullable columns). */
        val defaultValue: String? = null,
        /**
         * True for retired legacy-extension columns (web schema 12 `serviceMode` /
         * `serviceCount`). They are accepted when *reading* an older portable schema and
         * consumed by the normalizer; they are never written back.
         */
        val legacyOnly: Boolean = false,
    ) {
        /** A nullable column's documented default is `null`; a non-null one only when declared. */
        val declaresDefault: Boolean get() = nullable || defaultValue != null
    }

    data class ForeignKey(
        val columns: List<String>,
        val targetTable: String,
        val targetColumns: List<String>,
        val onDelete: String = "RESTRICT",
        val onUpdate: String = "CASCADE",
    )

    data class Table(
        val name: String,
        val columns: List<Column>,
        val primaryKey: List<String>,
        /** Whether rows of this table are written to a backup archive. */
        val included: Boolean = true,
        /**
         * True for the two single-row settings tables. Their primary key is a fixed id,
         * so a restored file may not smuggle in a second row under another id.
         */
        val singleton: Boolean = false,
        val unique: List<List<String>> = emptyList(),
        val indexes: List<List<String>> = emptyList(),
        val foreignKeys: List<ForeignKey> = emptyList(),
        /**
         * Portable schema below which the table may be absent entirely: a file written by
         * an older build simply has no such table and must be restored as empty.
         */
        val legacyOptionalTableUntil: Int? = null,
    ) {
        val columnNames: List<String> get() = columns.map { it.name }

        /** Columns written to a new export (retired legacy columns are excluded). */
        val writtenColumnNames: List<String> get() = columns.filterNot { it.legacyOnly }.map { it.name }

        fun column(name: String): Column? = columns.firstOrNull { it.name == name }
    }

    private fun c(
        name: String,
        type: ColumnType,
        nullable: Boolean,
        enumName: String? = null,
        defaultValue: String? = null,
        legacyOnly: Boolean = false,
    ) = Column(name, type, nullable, enumName, defaultValue, legacyOnly)

    val tables: List<Table> = listOf(

        Table(
            name = "owners",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("number", ColumnType.INTEGER, false),
                c("nickname", ColumnType.TEXT, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
                c("archivedAt", ColumnType.INTEGER, true),
                c("archiveBatchId", ColumnType.TEXT, true),
            ),
            unique = listOf(listOf("number")),
            indexes = listOf(listOf("number"), listOf("archivedAt")),
        ),
        Table(
            name = "owner_number_sequence",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.INTEGER, false),
                c("nextNumber", ColumnType.INTEGER, false),
            ),
            singleton = true,
        ),
        Table(
            name = "account_categories",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("name", ColumnType.TEXT, false),
                c("sortOrder", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("name")),
            indexes = listOf(listOf("sortOrder")),
            legacyOptionalTableUntil = 14,
        ),
        Table(
            name = "game_accounts",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("ownerId", ColumnType.TEXT, false),
                c("gameName", ColumnType.TEXT, false),
                c("server", ColumnType.TEXT, false),
                c("uid", ColumnType.TEXT, false),
                c("loginAccount", ColumnType.TEXT, false),
                c("loginMethod", ColumnType.TEXT, false, enumName = "LOGIN_METHODS"),
                c("password", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
                c("archivedAt", ColumnType.INTEGER, true),
                c("archiveBatchId", ColumnType.TEXT, true),
                c("defaultTemplateId", ColumnType.TEXT, true),
                c("loginDevice", ColumnType.TEXT, true),
                c("resourceNotes", ColumnType.TEXT, true),
                c("categoryId", ColumnType.TEXT, true),
            ),
            indexes = listOf(listOf("ownerId"), listOf("ownerId", "archivedAt"), listOf("categoryId")),
            foreignKeys = listOf(
            ForeignKey(listOf("ownerId"), "owners", listOf("id")),
            ),
        ),
        Table(
            name = "game_server_options",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("gameName", ColumnType.TEXT, false),
                c("name", ColumnType.TEXT, false),
                c("sortOrder", ColumnType.INTEGER, false),
            ),
            indexes = listOf(listOf("gameName", "sortOrder")),
            legacyOptionalTableUntil = 8,
        ),
        Table(
            name = "game_templates",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("gameName", ColumnType.TEXT, false),
                c("templateVersion", ColumnType.TEXT, false),
                c("gameVersionNotes", ColumnType.TEXT, true),
                c("zoneId", ColumnType.TEXT, false),
                c("dailyRefreshMinutes", ColumnType.INTEGER, false),
                c("weeklyRefreshDay", ColumnType.INTEGER, false),
                c("weeklyRefreshMinutes", ColumnType.INTEGER, false),
                c("origin", ColumnType.TEXT, false, enumName = "TEMPLATE_ORIGINS"),
                c("builtInKey", ColumnType.TEXT, true),
                c("sortOrder", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
                c("hostingMode", ColumnType.TEXT, true, enumName = "HOSTING_MODES"),
                c("targetSessions", ColumnType.INTEGER, true),
                c("serviceWeekdays", ColumnType.TEXT, true),
                c("defaultOrderTitle", ColumnType.TEXT, true),
                c("serviceMode", ColumnType.TEXT, true, legacyOnly = true),
                c("serviceCount", ColumnType.INTEGER, true, legacyOnly = true),
            ),
            unique = listOf(listOf("builtInKey")),
            indexes = listOf(listOf("gameName")),
        ),
        Table(
            name = "template_categories",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("templateId", ColumnType.TEXT, false),
                c("name", ColumnType.TEXT, false),
                c("sortOrder", ColumnType.INTEGER, false),
                c("hostingMode", ColumnType.TEXT, true, enumName = "HOSTING_MODES"),
                c("targetSessions", ColumnType.INTEGER, true),
                c("serviceWeekdays", ColumnType.TEXT, true),
                c("serviceMode", ColumnType.TEXT, true, legacyOnly = true),
                c("serviceCount", ColumnType.INTEGER, true, legacyOnly = true),
            ),
            indexes = listOf(listOf("templateId")),
            foreignKeys = listOf(
            ForeignKey(listOf("templateId"), "game_templates", listOf("id")),
            ),
        ),
        Table(
            name = "template_tasks",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("templateId", ColumnType.TEXT, false),
                c("categoryId", ColumnType.TEXT, false),
                c("title", ColumnType.TEXT, false),
                c("progressType", ColumnType.TEXT, false, enumName = "PROGRESS_TYPES"),
                c("startValue", ColumnType.TEXT, false),
                c("targetValue", ColumnType.TEXT, false),
                c("unit", ColumnType.TEXT, true),
                c("recurrence", ColumnType.TEXT, false, enumName = "RECURRENCES"),
                c("weekdays", ColumnType.TEXT, true),
                c("everyNDays", ColumnType.INTEGER, true),
                c("sortOrder", ColumnType.INTEGER, false),
                c("monthDay", ColumnType.INTEGER, true),
                c("monthDays", ColumnType.TEXT, true),
                c("activeFrom", ColumnType.TEXT, true),
                c("activeUntil", ColumnType.TEXT, true),
                c("numericMode", ColumnType.TEXT, true, enumName = "NUMERIC_MODES"),
            ),
            indexes = listOf(listOf("templateId"), listOf("categoryId")),
            foreignKeys = listOf(
            ForeignKey(listOf("templateId"), "game_templates", listOf("id")),
            ForeignKey(listOf("categoryId"), "template_categories", listOf("id")),
            ),
        ),
        Table(
            name = "template_price_presets",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("templateId", ColumnType.TEXT, false),
                c("amountCents", ColumnType.INTEGER, false),
                c("label", ColumnType.TEXT, true),
                c("sortOrder", ColumnType.INTEGER, false),
            ),
            indexes = listOf(listOf("templateId")),
            foreignKeys = listOf(
            ForeignKey(listOf("templateId"), "game_templates", listOf("id")),
            ),
            legacyOptionalTableUntil = 8,
        ),
        Table(
            name = "template_seed_state",
            primaryKey = listOf("seedKey"),
            columns = listOf(
                c("seedKey", ColumnType.TEXT, false),
                c("appliedAt", ColumnType.INTEGER, false),
            ),
        ),
        Table(
            name = "completion_batches",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("operationId", ColumnType.TEXT, false),
                c("scope", ColumnType.TEXT, false, enumName = "BATCH_SCOPES"),
                c("targetId", ColumnType.TEXT, true),
                c("gameDate", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
                c("undoneAt", ColumnType.INTEGER, true),
            ),
            unique = listOf(listOf("operationId")),
            indexes = listOf(listOf("operationId")),
        ),
        Table(
            name = "orders",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("accountId", ColumnType.TEXT, false),
                c("type", ColumnType.TEXT, false, enumName = "ORDER_TYPES"),
                c("title", ColumnType.TEXT, false),
                c("priceCents", ColumnType.INTEGER, true),
                c("paymentMode", ColumnType.TEXT, false, enumName = "PAYMENT_MODES"),
                c("deadlineDate", ColumnType.TEXT, true),
                c("notes", ColumnType.TEXT, true),
                c("gameZoneId", ColumnType.TEXT, false),
                c("dailyRefreshMinutes", ColumnType.INTEGER, false),
                c("weeklyRefreshDay", ColumnType.INTEGER, false),
                c("weeklyRefreshMinutes", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
                c("completedAt", ColumnType.INTEGER, true),
                c("sourceTemplateId", ColumnType.TEXT, true),
                c("sourceTemplateName", ColumnType.TEXT, true),
                c("sourceTemplateVersion", ColumnType.TEXT, true),
                c("cancelledAt", ColumnType.INTEGER, true),
                c("archivedAt", ColumnType.INTEGER, true),
                c("hostingMode", ColumnType.TEXT, true, enumName = "HOSTING_MODES"),
                c("targetSessions", ColumnType.INTEGER, true),
                c("orderColor", ColumnType.INTEGER, true),
                c("monthlyRefreshDays", ColumnType.TEXT, true),
            ),
            indexes = listOf(listOf("accountId"), listOf("accountId", "type"), listOf("type", "completedAt"), listOf("archivedAt")),
            foreignKeys = listOf(
            ForeignKey(listOf("accountId"), "game_accounts", listOf("id")),
            ),
        ),
        Table(
            name = "hosting_periods",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("periodIndex", ColumnType.INTEGER, false),
                c("startDate", ColumnType.TEXT, false),
                c("endDate", ColumnType.TEXT, false),
                c("priceCents", ColumnType.INTEGER, false),
                c("gameZoneId", ColumnType.TEXT, false),
                c("dailyRefreshMinutes", ColumnType.INTEGER, false),
                c("weeklyRefreshDay", ColumnType.INTEGER, false),
                c("weeklyRefreshMinutes", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("renewalOperationId", ColumnType.TEXT, true),
                c("serviceDays", ColumnType.INTEGER, true),
                c("serviceWeekdays", ColumnType.TEXT, true),
                c("monthlyRefreshDays", ColumnType.TEXT, true),
                c("serviceCount", ColumnType.INTEGER, true),
            ),
            unique = listOf(listOf("orderId", "periodIndex"), listOf("orderId", "startDate"), listOf("renewalOperationId")),
            indexes = listOf(listOf("orderId"), listOf("renewalOperationId")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ),
        ),
        Table(
            name = "hosting_sessions",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("sessionIndex", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
                c("completedAt", ColumnType.INTEGER, true),
                c("completionBatchId", ColumnType.TEXT, true),
                c("periodId", ColumnType.TEXT, false),
            ),
            unique = listOf(listOf("orderId", "sessionIndex")),
            indexes = listOf(listOf("orderId"), listOf("periodId")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ),
            legacyOptionalTableUntil = 12,
        ),
        Table(
            name = "order_price_changes",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("operationId", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("periodId", ColumnType.TEXT, true),
                c("previousPriceCents", ColumnType.INTEGER, false),
                c("newPriceCents", ColumnType.INTEGER, false),
                c("reason", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("operationId")),
            indexes = listOf(listOf("orderId"), listOf("periodId")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ForeignKey(listOf("periodId"), "hosting_periods", listOf("id")),
            ),
            legacyOptionalTableUntil = 8,
        ),
        Table(
            name = "task_definitions",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("periodId", ColumnType.TEXT, true),
                c("title", ColumnType.TEXT, false),
                c("progressType", ColumnType.TEXT, false, enumName = "PROGRESS_TYPES"),
                c("startValue", ColumnType.TEXT, false),
                c("targetValue", ColumnType.TEXT, false),
                c("currentValue", ColumnType.TEXT, false),
                c("unit", ColumnType.TEXT, true),
                c("recurrence", ColumnType.TEXT, false, enumName = "RECURRENCES"),
                c("weekdays", ColumnType.TEXT, true),
                c("everyNDays", ColumnType.INTEGER, true),
                c("sortOrder", ColumnType.INTEGER, false),
                c("completedAt", ColumnType.INTEGER, true),
                c("completionBatchId", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
                c("monthDay", ColumnType.INTEGER, true),
                c("monthDays", ColumnType.TEXT, true),
                c("retiredAt", ColumnType.INTEGER, true),
                c("activeFrom", ColumnType.TEXT, true),
                c("activeUntil", ColumnType.TEXT, true),
                c("numericMode", ColumnType.TEXT, true, enumName = "NUMERIC_MODES"),
                c("colorArgb", ColumnType.INTEGER, true),
            ),
            indexes = listOf(listOf("orderId"), listOf("periodId"), listOf("completionBatchId")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ForeignKey(listOf("periodId"), "hosting_periods", listOf("id")),
            ForeignKey(listOf("completionBatchId"), "completion_batches", listOf("id")),
            ),
        ),
        Table(
            name = "task_occurrences",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("taskDefinitionId", ColumnType.TEXT, false),
                c("occurrenceKey", ColumnType.TEXT, false),
                c("gameDate", ColumnType.TEXT, false),
                c("windowStart", ColumnType.TEXT, true),
                c("windowEnd", ColumnType.TEXT, true),
                c("completedAt", ColumnType.INTEGER, true),
                c("recordedAt", ColumnType.INTEGER, true),
                c("backfilled", ColumnType.INTEGER, false, defaultValue = "0"),
                c("completionBatchId", ColumnType.TEXT, true),
                c("value", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("taskDefinitionId", "occurrenceKey")),
            indexes = listOf(listOf("taskDefinitionId"), listOf("gameDate"), listOf("completionBatchId")),
            foreignKeys = listOf(
            ForeignKey(listOf("taskDefinitionId"), "task_definitions", listOf("id")),
            ForeignKey(listOf("completionBatchId"), "completion_batches", listOf("id")),
            ),
        ),
        Table(
            name = "task_progress_events",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("taskDefinitionId", ColumnType.TEXT, false),
                c("operationId", ColumnType.TEXT, false),
                c("kind", ColumnType.TEXT, false, enumName = "PROGRESS_EVENT_KINDS"),
                c("scopeType", ColumnType.TEXT, false, enumName = "PROGRESS_SCOPE_TYPES"),
                c("scopePeriodId", ColumnType.TEXT, true),
                c("scopeSessionId", ColumnType.TEXT, true),
                c("gameDate", ColumnType.TEXT, false),
                c("totalValue", ColumnType.TEXT, true),
                c("sequence", ColumnType.INTEGER, false),
                c("correctsEventId", ColumnType.TEXT, true),
                c("recordedAt", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("operationId"), listOf("taskDefinitionId", "sequence")),
            indexes = listOf(listOf("taskDefinitionId"), listOf("scopePeriodId"), listOf("scopeSessionId"), listOf("gameDate"), listOf("correctsEventId")),
            foreignKeys = listOf(
            ForeignKey(listOf("taskDefinitionId"), "task_definitions", listOf("id")),
            ForeignKey(listOf("correctsEventId"), "task_progress_events", listOf("id")),
            ),
            legacyOptionalTableUntil = 14,
        ),
        Table(
            name = "completion_batch_items",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("batchId", ColumnType.TEXT, false),
                c("itemType", ColumnType.TEXT, false, enumName = "BATCH_ITEM_TYPES"),
                c("occurrenceId", ColumnType.TEXT, true),
                c("taskDefinitionId", ColumnType.TEXT, true),
                c("previousCompletedAt", ColumnType.INTEGER, true),
                c("previousRecordedAt", ColumnType.INTEGER, true),
                c("previousBackfilled", ColumnType.INTEGER, true),
                c("previousValue", ColumnType.TEXT, true),
                c("previousCompletionBatchId", ColumnType.TEXT, true),
                c("appliedCompletedAt", ColumnType.INTEGER, true),
                c("appliedRecordedAt", ColumnType.INTEGER, true),
                c("appliedBackfilled", ColumnType.INTEGER, true),
                c("appliedValue", ColumnType.TEXT, true),
                c("appliedCompletionBatchId", ColumnType.TEXT, true),
            ),
            indexes = listOf(listOf("batchId"), listOf("occurrenceId"), listOf("taskDefinitionId")),
            foreignKeys = listOf(
            ForeignKey(listOf("batchId"), "completion_batches", listOf("id")),
            ForeignKey(listOf("occurrenceId"), "task_occurrences", listOf("id")),
            ForeignKey(listOf("taskDefinitionId"), "task_definitions", listOf("id")),
            ),
        ),
        Table(
            name = "attachments",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("taskDefinitionId", ColumnType.TEXT, true),
                c("occurrenceId", ColumnType.TEXT, true),
                c("relativePath", ColumnType.TEXT, false),
                c("mimeType", ColumnType.TEXT, false),
                c("sizeBytes", ColumnType.INTEGER, false),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            indexes = listOf(listOf("taskDefinitionId"), listOf("occurrenceId")),
            foreignKeys = listOf(
            ForeignKey(listOf("taskDefinitionId"), "task_definitions", listOf("id")),
            ForeignKey(listOf("occurrenceId"), "task_occurrences", listOf("id")),
            ),
        ),
        Table(
            name = "receipts",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("operationId", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("periodId", ColumnType.TEXT, true),
                c("amountCents", ColumnType.INTEGER, false),
                c("receivedAt", ColumnType.INTEGER, false),
                c("channel", ColumnType.TEXT, false, enumName = "RECEIPT_CHANNELS"),
                c("note", ColumnType.TEXT, true),
                c("status", ColumnType.TEXT, false, enumName = "RECEIPT_STATUSES"),
                c("replacedByReceiptId", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("operationId")),
            indexes = listOf(listOf("orderId"), listOf("periodId"), listOf("receivedAt")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ForeignKey(listOf("periodId"), "hosting_periods", listOf("id")),
            ),
        ),
        Table(
            name = "receipt_corrections",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.TEXT, false),
                c("operationId", ColumnType.TEXT, false),
                c("receiptId", ColumnType.TEXT, false),
                c("kind", ColumnType.TEXT, false, enumName = "CORRECTION_KINDS"),
                c("reason", ColumnType.TEXT, true),
                c("previousAmountCents", ColumnType.INTEGER, false),
                c("previousReceivedAt", ColumnType.INTEGER, false),
                c("previousChannel", ColumnType.TEXT, false, enumName = "RECEIPT_CHANNELS"),
                c("previousNote", ColumnType.TEXT, true),
                c("newReceiptId", ColumnType.TEXT, true),
                c("newAmountCents", ColumnType.INTEGER, true),
                c("newReceivedAt", ColumnType.INTEGER, true),
                c("newChannel", ColumnType.TEXT, true, enumName = "RECEIPT_CHANNELS"),
                c("newNote", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("operationId")),
            indexes = listOf(listOf("receiptId"), listOf("newReceiptId")),
            foreignKeys = listOf(
            ForeignKey(listOf("receiptId"), "receipts", listOf("id")),
            ForeignKey(listOf("newReceiptId"), "receipts", listOf("id")),
            ),
        ),
        Table(
            name = "order_creation_operations",
            primaryKey = listOf("operationId"),
            columns = listOf(
                c("operationId", ColumnType.TEXT, false),
                c("orderType", ColumnType.TEXT, false, enumName = "ORDER_TYPES"),
                c("fingerprint", ColumnType.TEXT, false),
                c("orderId", ColumnType.TEXT, false),
                c("receiptId", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            unique = listOf(listOf("operationId")),
            indexes = listOf(listOf("orderId")),
            foreignKeys = listOf(
            ForeignKey(listOf("orderId"), "orders", listOf("id")),
            ),
        ),
        Table(
            name = "reminder_settings",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.INTEGER, false),
                c("shortDeadlineEnabled", ColumnType.INTEGER, false),
                c("shortDeadlineMinutes", ColumnType.INTEGER, false),
                c("hostingExpiryEnabled", ColumnType.INTEGER, false),
                c("hostingExpiryMinutes", ColumnType.INTEGER, false),
                c("unfinishedTasksEnabled", ColumnType.INTEGER, false),
                c("unfinishedTasksMinutes", ColumnType.INTEGER, false),
                c("updatedAt", ColumnType.INTEGER, false),
            ),
            singleton = true,
        ),
        Table(
            name = "app_settings",
            primaryKey = listOf("id"),
            columns = listOf(
                c("id", ColumnType.INTEGER, false),
                c("datasetInstanceId", ColumnType.TEXT, false),
                c("lastRestoreId", ColumnType.TEXT, true),
                c("createdAt", ColumnType.INTEGER, false),
            ),
            singleton = true,
            included = false,
        ),
        Table(
            name = "reminder_deliveries",
            primaryKey = listOf("category", "day"),
            columns = listOf(
                c("category", ColumnType.TEXT, false),
                c("day", ColumnType.TEXT, false),
                c("deliveredAt", ColumnType.INTEGER, false),
            ),
            included = false,
        ),
    )

    /** Tables in the archive, parents first. */
    val includedTables: List<Table> = tables.filter { it.included }

    /** Tables that exist in the schema but are intentionally local-only. */
    val localOnlyTables: List<Table> = tables.filterNot { it.included }

    /**
     * Tables introduced after the oldest restorable backup generation. A file that
     * predates them decodes with these tables empty instead of being rejected; every
     * other table must be present with its exact column list.
     */
    val legacyOptionalTables: Set<String> =
        tables.filter { it.legacyOptionalTableUntil != null }.map { it.name }.toSet()

    /**
     * Columns added after the oldest restorable backup generation, per table.
     *
     * A file written before a column existed may omit exactly these columns; they are
     * restored as NULL (all of them are nullable) and then filled by the deterministic
     * normalizer. Every other column must be present in the exported order, so a
     * truncated or reordered file is still rejected.
     */
    val legacyOptionalColumns: Map<String, Set<String>> = mapOf(
        "game_accounts" to setOf("loginDevice", "resourceNotes", "categoryId"),
        "game_templates" to setOf(
            "hostingMode",
            "targetSessions",
            "serviceWeekdays",
            "defaultOrderTitle",
            // legacy-only: consumed by `category-service-defaults`, never written back.
            "serviceMode",
            "serviceCount",
        ),
        "template_categories" to setOf(
            "hostingMode",
            "targetSessions",
            "serviceWeekdays",
            "serviceMode",
            "serviceCount",
        ),
        "template_tasks" to setOf("monthDay", "monthDays", "activeFrom", "activeUntil", "numericMode"),
        "orders" to setOf(
            "cancelledAt",
            "archivedAt",
            "hostingMode",
            "targetSessions",
            "orderColor",
            "monthlyRefreshDays",
        ),
        "hosting_periods" to setOf(
            "serviceDays",
            "serviceWeekdays",
            "monthlyRefreshDays",
            "serviceCount",
        ),
        "hosting_sessions" to setOf("periodId"),
        "task_definitions" to setOf(
            "monthDay",
            "monthDays",
            "retiredAt",
            "activeFrom",
            "activeUntil",
            "numericMode",
            "colorArgb",
        ),
        "completion_batch_items" to setOf("itemType"),
    )

    /** Delete order: children before parents, so `RESTRICT` foreign keys stay valid. */
    val deletionOrder: List<String> = includedTables.asReversed().map { it.name }

    fun table(name: String): Table? = tables.firstOrNull { it.name == name }

    /** The portable schema this build writes (Room's schema number is the same value). */
    const val PORTABLE_SCHEMA_VERSION = 15

    /** The template exchange format this build writes. */
    const val TEMPLATE_FORMAT_VERSION = 4

    const val TEMPLATE_MIN_READABLE_VERSION = 1

    const val ENVELOPE_FORMAT_VERSION = 1
}
