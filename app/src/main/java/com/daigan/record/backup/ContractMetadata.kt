package com.daigan.record.backup

import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressEventKind
import com.daigan.record.data.model.ProgressScopeType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.ReceiptCorrectionKind
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 产品级契约元数据（供 `tools/interop/check-contract.mjs` parity 模式直连）。
 *
 * 全部内容来自运行时真实使用的声明：[BackupTableCatalog]（导出/导入的列目录）与
 * 真实的 Kotlin 枚举。**不是**从 `contract.json` 复制，因此任何产品与契约的偏移都会被
 * 检查器如实报出，而不是被这里"补齐"。
 */
object ContractMetadata {

    /** 模板文件与便携 schema 的版本号来自产品自身的常量。 */
    const val PORTABLE_SCHEMA_VERSION = BackupTableCatalog.PORTABLE_SCHEMA_VERSION

    /** 本应用声明写入的模板格式。 */
    const val TEMPLATE_FORMAT_VERSION = BackupTableCatalog.TEMPLATE_FORMAT_VERSION

    /** UI 文案与诊断里使用，但检查器只把它当作说明字段。 */
    const val PRODUCT_NAME = "daigan-record-android"

    /**
     * 枚举声明：直接读取 Kotlin 枚举的**真实**取值与声明顺序。
     *
     * `OCCURRENCE_KEY_PREFIXES` 不是枚举：D/S/E/M/W 由 occurrence 规则产生，C 是历史
     * 网页的旧键，N 是计次轮次键（[com.daigan.record.data.model.CountHosting]）。
     */
    fun enums(): Map<String, List<String>> = linkedMapOf(
        "ORDER_TYPES" to OrderType.entries.map { it.name },
        "PAYMENT_MODES" to PaymentMode.entries.map { it.name },
        "LOGIN_METHODS" to LoginMethod.entries.map { it.name },
        "PROGRESS_TYPES" to ProgressType.entries.map { it.name },
        "BATCH_ITEM_TYPES" to BatchItemType.entries.map { it.name },
        "RECURRENCES" to RecurrenceType.entries.map { it.name },
        "RECEIPT_CHANNELS" to PaymentChannel.entries.map { it.name },
        "RECEIPT_STATUSES" to ReceiptStatus.entries.map { it.name },
        "CORRECTION_KINDS" to ReceiptCorrectionKind.entries.map { it.name },
        "BATCH_SCOPES" to BatchScope.entries.map { it.name },
        "TEMPLATE_ORIGINS" to TemplateOrigin.entries.map { it.name },
        "HOSTING_MODES" to HostingMode.entries.map { it.name },
        "NUMERIC_MODES" to NumericMode.entries.map { it.name },
        "PROGRESS_EVENT_KINDS" to ProgressEventKind.entries.map { it.name },
        "PROGRESS_SCOPE_TYPES" to ProgressScopeType.entries.map { it.name },
        "OCCURRENCE_KEY_PREFIXES" to listOf("D", "S", "E", "M", "W", "C", "N"),
        "WEEKDAY_TOKENS" to listOf(
            "MONDAY",
            "TUESDAY",
            "WEDNESDAY",
            "THURSDAY",
            "FRIDAY",
            "SATURDAY",
            "SUNDAY",
        ),
    )

    fun render(productVersion: String): JsonObject {
        val tables = JsonArray(
            BackupTableCatalog.tables.map { table ->
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(table.name),
                        "included" to JsonPrimitive(table.included),
                        "singleton" to JsonPrimitive(table.singleton),
                        "primaryKey" to JsonArray(table.primaryKey.map(::JsonPrimitive)),
                        "unique" to JsonArray(
                            table.unique.map { group -> JsonArray(group.map(::JsonPrimitive)) },
                        ),
                        "indexes" to JsonArray(
                            table.indexes.map { group -> JsonArray(group.map(::JsonPrimitive)) },
                        ),
                        "foreignKeys" to JsonArray(
                            table.foreignKeys.map { fk ->
                                JsonObject(
                                    linkedMapOf(
                                        "columns" to JsonArray(fk.columns.map(::JsonPrimitive)),
                                        "references" to JsonObject(
                                            linkedMapOf(
                                                "table" to JsonPrimitive(fk.targetTable),
                                                "columns" to JsonArray(fk.targetColumns.map(::JsonPrimitive)),
                                            ),
                                        ),
                                        "onDelete" to JsonPrimitive(fk.onDelete),
                                        "onUpdate" to JsonPrimitive(fk.onUpdate),
                                    ),
                                )
                            },
                        ),
                        "columns" to JsonArray(
                            table.columns.filterNot { it.legacyOnly }.map { column ->
                                JsonObject(
                                    linkedMapOf(
                                        "name" to JsonPrimitive(column.name),
                                        "type" to JsonPrimitive(
                                            if (column.type == BackupTableCatalog.ColumnType.TEXT) {
                                                "TEXT"
                                            } else {
                                                "INT"
                                            },
                                        ),
                                        "nullable" to JsonPrimitive(column.nullable),
                                        "enum" to (column.enumName?.let(::JsonPrimitive) ?: JsonNull),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            },
        )

        // 默认值只对**契约内的表**声明：本机表（app_settings / reminder_deliveries）
        // 不属于便携契约，给它们写默认值会被检查器判为"契约未定义"。
        val defaults = linkedMapOf<String, JsonObject>()
        BackupTableCatalog.tables.filter { it.included }.forEach { table ->
            table.columns.filterNot { it.legacyOnly }.forEach { column ->
                val key = "${table.name}.${column.name}"
                defaults[key] = if (!column.declaresDefault) {
                    JsonObject(linkedMapOf("declared" to JsonPrimitive(false)))
                } else {
                    JsonObject(
                        linkedMapOf(
                            "declared" to JsonPrimitive(true),
                            "value" to (column.defaultValue?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                }
            }
        }

        return JsonObject(
            linkedMapOf(
                "platform" to JsonPrimitive("android"),
                "extractor" to JsonPrimitive("app/src/main/java/com/daigan/record/backup/ContractMetadata.kt"),
                "extractorMode" to JsonPrimitive("product"),
                "product" to JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(PRODUCT_NAME),
                        "version" to JsonPrimitive(productVersion),
                    ),
                ),
                "portableSchemaVersion" to JsonPrimitive(PORTABLE_SCHEMA_VERSION),
                "templateFormatVersion" to JsonPrimitive(TEMPLATE_FORMAT_VERSION),
                "templateMinReadableVersion" to JsonPrimitive(
                    BackupTableCatalog.TEMPLATE_MIN_READABLE_VERSION,
                ),
                "envelopeFormatVersion" to JsonPrimitive(BackupTableCatalog.ENVELOPE_FORMAT_VERSION),
                "legacyOptionalTables" to JsonArray(
                    BackupTableCatalog.legacyOptionalTables.sorted().map(::JsonPrimitive),
                ),
                "tables" to tables,
                "enums" to JsonObject(enums().mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }),
                "defaults" to JsonObject(defaults),
            ),
        )
    }

    fun renderToString(productVersion: String): String =
        kotlinx.serialization.json.Json { prettyPrint = true }
            .encodeToString(JsonObject.serializer(), render(productVersion))
}
