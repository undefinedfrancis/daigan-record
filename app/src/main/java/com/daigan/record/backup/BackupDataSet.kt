package com.daigan.record.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement as JsonElementAlias

/**
 * The complete structured payload of a backup: one entry per durable table, in parent
 * order, with values in the catalog's column order.
 *
 * Rows are arrays rather than objects on purpose. A duplicated column or table key is
 * then impossible in a generated file and detectable in a foreign one, and the parser
 * can validate every value against the declared column type before anything is written
 * to a database.
 */
class BackupDataSet(val tables: List<TableRows>) {

    data class TableRows(
        val table: BackupTableCatalog.Table,
        /** One value per catalog column: a JSON primitive, or null for SQL NULL. */
        val rows: List<List<JsonElement?>>,
        /**
         * The column list the source file actually declared, or null when the table was
         * absent from it.
         *
         * Kept because the decoder fills missing optional columns with NULL: without this,
         * "column did not exist yet" and "stored NULL" are indistinguishable, and the
         * level-specific service-default migration could not be applied honestly.
         */
        val presentColumns: List<String>? = null,
    ) {
        val rowCount: Int get() = rows.size

        fun hadColumn(name: String): Boolean = presentColumns?.contains(name) ?: false
    }

    fun rows(tableName: String): List<List<JsonElement?>> =
        tables.firstOrNull { it.table.name == tableName }?.rows ?: emptyList()

    fun tableRows(tableName: String): TableRows? = tables.firstOrNull { it.table.name == tableName }

    fun count(tableName: String): Int = rows(tableName).size

    fun isEmpty(): Boolean = tables.all { it.rows.isEmpty() }

    /** Attachment metadata rows, decoded from the `attachments` table. */
    data class AttachmentRow(
        val id: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    fun attachmentRows(): List<AttachmentRow> {
        val spec = checkNotNull(BackupTableCatalog.table("attachments"))
        fun index(name: String) = spec.columnNames.indexOf(name)
        val idIndex = index("id")
        val pathIndex = index("relativePath")
        val mimeIndex = index("mimeType")
        val sizeIndex = index("sizeBytes")
        return rows("attachments").map { row ->
            AttachmentRow(
                id = (row[idIndex] as? JsonPrimitive)?.content
                    ?: throw BackupException("备份中的截图记录缺少编号，无法恢复。"),
                relativePath = (row[pathIndex] as? JsonPrimitive)?.content
                    ?: throw BackupException("备份中的截图记录缺少文件名，无法恢复。"),
                mimeType = (row[mimeIndex] as? JsonPrimitive)?.content.orEmpty(),
                sizeBytes = (row[sizeIndex] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: throw BackupException("备份中的截图记录大小无效，无法恢复。"),
            )
        }
    }

    companion object {
        const val DATA_FORMAT = "daigan-backup-data"
        const val DATA_FORMAT_VERSION = 1

        /** Maximum JSON nesting accepted from a file (defensive bound). */
        const val MAX_JSON_DEPTH = 32
    }
}

/**
 * Encodes and strictly decodes [BackupDataSet]. Decoding never trusts the file:
 * the format marker, version, the exact table set, the exact column list of every table
 * and every value's JSON type are all verified.
 */
object BackupDataCodec {

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowTrailingComma = false
    }

    fun encode(dataSet: BackupDataSet): String {
        val tables = JsonArray(
            dataSet.tables.filter { it.table.included }.map { tableRows ->
                val table = tableRows.table
                // A new export always writes the complete portable schema, without any
                // retired legacy-extension column.
                val written = table.writtenColumnNames
                val indexes = written.map { table.columnNames.indexOf(it) }
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(table.name),
                        "columns" to JsonArray(written.map { JsonPrimitive(it) }),
                        "rows" to JsonArray(
                            tableRows.rows.map { row ->
                                JsonArray(indexes.map { position -> row.getOrNull(position) ?: JsonNull })
                            },
                        ),
                    ),
                )
            },
        )
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(BackupDataSet.DATA_FORMAT),
                "formatVersion" to JsonPrimitive(BackupDataSet.DATA_FORMAT_VERSION),
                "schemaVersion" to JsonPrimitive(BackupTableCatalog.PORTABLE_SCHEMA_VERSION),
                "tables" to tables,
            ),
        )
        return json.encodeToString(JsonElementAlias.serializer(), root)
    }

    /**
     * Decodes `data.json`.
     *
     * [schemaVersion] is the portable schema declared by the manifest; it decides whether
     * retired legacy columns are still readable and whether missing optional columns may
     * be filled with NULL.
     */
    fun decode(text: String, schemaVersion: Int = BackupTableCatalog.PORTABLE_SCHEMA_VERSION): BackupDataSet {
        JsonSafety.requireBoundedDepth(text, BackupDataSet.MAX_JSON_DEPTH)
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw BackupException("备份的数据内容已损坏，无法恢复。")
        if (root.string("format") != BackupDataSet.DATA_FORMAT) {
            throw BackupException("备份的数据格式无法识别，无法恢复。")
        }
        val formatVersion = root.int("formatVersion")
        if (formatVersion != BackupDataSet.DATA_FORMAT_VERSION) {
            throw BackupException("备份的数据格式版本（$formatVersion）不受支持，无法恢复。")
        }
        val declaredSchema = root["schemaVersion"]
        if (declaredSchema != null) {
            val declared = (declaredSchema as? JsonPrimitive)?.content?.toIntOrNull()
            if (declared != schemaVersion) {
                throw BackupException("数据文件声明的版本与清单不一致，无法恢复。")
            }
        }
        val tableArray = root["tables"] as? JsonArray
            ?: throw BackupException("备份缺少数据表清单，无法恢复。")

        val expected = BackupTableCatalog.includedTables
        val byName = LinkedHashMap<String, BackupDataSet.TableRows>()
        tableArray.forEach { element ->
            val tableObject = element as? JsonObject
                ?: throw BackupException("备份的数据表清单格式无效，无法恢复。")
            val name = tableObject.string("name")
                ?: throw BackupException("备份的数据表缺少名称，无法恢复。")
            if (byName.containsKey(name)) {
                throw BackupException("备份中重复出现数据表「$name」，无法恢复。")
            }
            val spec = expected.firstOrNull { it.name == name }
                ?: throw BackupException("备份包含不受支持的数据表「$name」，无法恢复。")
            val columnsElement = tableObject["columns"] as? JsonArray
                ?: throw BackupException("备份的数据表「$name」缺少列说明，无法恢复。")
            val columns = columnsElement.map { (it as? JsonPrimitive)?.content }
            // 退役列只在读取更老的便携版本时可接受。
            val retired = columns.filter { column ->
                column != null && spec.column(column)?.legacyOnly == true
            }
            if (retired.isNotEmpty() && schemaVersion >= BackupTableCatalog.PORTABLE_SCHEMA_VERSION) {
                throw BackupException(
                    "备份的数据表「$name」包含 schema " +
                        "${BackupTableCatalog.PORTABLE_SCHEMA_VERSION} 已退役的列，无法恢复。",
                )
            }
            // A backup written before a nullable column existed may omit it; the value is
            // restored as NULL and filled by the deterministic normalizer. Unknown columns,
            // missing required columns and reordered columns are still rejected.
            val optionalColumns = if (schemaVersion < BackupTableCatalog.PORTABLE_SCHEMA_VERSION) {
                BackupTableCatalog.legacyOptionalColumns[name].orEmpty()
            } else {
                emptySet()
            }
            val knownColumns = spec.columnNames.toSet()
            val unknownColumns = columns.filter { it == null || it !in knownColumns }
            val missingColumns = spec.writtenColumnNames.filter { it !in columns }
            val orderPreserved = columns == spec.columnNames.filter { it in columns }
            if (unknownColumns.isNotEmpty() ||
                missingColumns.any { it !in optionalColumns } ||
                !orderPreserved
            ) {
                throw BackupException("备份的数据表「$name」列不符合当前版本，无法恢复。")
            }
            if (columns.filterNotNull().toSet().size != columns.size) {
                throw BackupException("备份的数据表「$name」列清单重复，无法恢复。")
            }
            val rowsElement = tableObject["rows"] as? JsonArray
                ?: throw BackupException("备份的数据表「$name」缺少记录，无法恢复。")
            if (spec.singleton && rowsElement.size > 1) {
                throw BackupException("备份中的单例设置重复，无法恢复。")
            }
            val rows = rowsElement.map { rowElement ->
                val rowArray = rowElement as? JsonArray
                    ?: throw BackupException("备份的数据表「$name」记录格式无效，无法恢复。")
                if (rowArray.size != columns.size) {
                    throw BackupException("备份的数据表「$name」记录列数不正确，无法恢复。")
                }
                val row = spec.columns.map { column ->
                    val sourceIndex = columns.indexOf(column.name)
                    if (sourceIndex < 0) null else decodeValue(column, rowArray[sourceIndex], spec.name)
                }
                if (spec.singleton && (row[spec.columnNames.indexOf("id")] as? JsonPrimitive)?.content != "0") {
                    throw BackupException("备份中的单例设置编号无效，无法恢复。")
                }
                row
            }
            byName[name] = BackupDataSet.TableRows(
                table = spec,
                rows = rows,
                presentColumns = columns.filterNotNull(),
            )
        }
        val missing = expected.filter { it.name !in byName }
        val requiredMissing = missing.filter { it.name !in BackupTableCatalog.legacyOptionalTables }
        if (requiredMissing.isNotEmpty()) {
            throw BackupException(
                "备份缺少数据表「${requiredMissing.first().name}」，无法恢复。",
            )
        }
        // Tables added after the backup's generation default to empty rows.
        return BackupDataSet(
            expected.map { table ->
                byName[table.name] ?: BackupDataSet.TableRows(table, emptyList(), presentColumns = null)
            },
        )
    }

    private fun decodeValue(
        column: BackupTableCatalog.Column,
        value: JsonElement,
        table: String,
    ): JsonElement? {
        if (value is JsonNull) {
            // 显式 null 落在非空列上就是对象级错误：绝不臆造金额、时间或渠道。
            // "历史缺列"走的是另一条路径（根本不调用本函数），因此不会误判。
            if (!column.nullable) {
                throw BackupException(
                    "备份的数据表「$table」的「${column.name}」不允许为空，无法恢复。",
                )
            }
            return null
        }
        val primitive = value as? JsonPrimitive
            ?: throw BackupException("备份的数据表「$table」包含无效值，无法恢复。")
        return when (column.type) {
            BackupTableCatalog.ColumnType.TEXT -> {
                if (!primitive.isString) {
                    throw BackupException("备份的数据表「$table」文本字段类型不正确，无法恢复。")
                }
                primitive
            }

            BackupTableCatalog.ColumnType.INTEGER -> {
                if (primitive.isString) {
                    throw BackupException("备份的数据表「$table」数字字段类型不正确，无法恢复。")
                }
                val raw = primitive.content
                val booleanColumn = (table == "reminder_settings" && column.name.endsWith("Enabled")) ||
                    (table == "task_occurrences" && column.name == "backfilled") ||
                    (table == "completion_batch_items" &&
                        column.name in setOf("previousBackfilled", "appliedBackfilled"))
                if (booleanColumn && raw !in setOf("0", "1")) {
                    throw BackupException("备份中的开关字段无效，无法恢复。")
                }
                if (raw.toLongOrNull() == null) {
                    throw BackupException("备份的数据表「$table」数字字段超出范围，无法恢复。")
                }
                // Room reads some columns into an Int property; a value that only fits in a
                // Long would silently truncate there, so it is rejected up front.
                if (column.name in INT_COLUMNS && raw.toIntOrNull() == null) {
                    throw BackupException("备份中的整数字段超出支持范围，无法恢复。")
                }
                primitive
            }
        }
    }

    /**
     * Columns whose Room property is an `Int` (indices, minute-of-day, small counters,
     * packed colours). Kept explicit so a wide value cannot silently truncate on write.
     */
    private val INT_COLUMNS = setOf(
        "id",
        "number",
        "nextNumber",
        "sortOrder",
        "dailyRefreshMinutes",
        "weeklyRefreshDay",
        "weeklyRefreshMinutes",
        "everyNDays",
        "monthDay",
        "serviceDays",
        "serviceCount",
        "targetSessions",
        "sessionIndex",
        "sequence",
        "shortDeadlineEnabled",
        "shortDeadlineMinutes",
        "hostingExpiryEnabled",
        "hostingExpiryMinutes",
        "unfinishedTasksEnabled",
        "unfinishedTasksMinutes",
        "orderColor",
        "colorArgb",
        "periodIndex",
        "backfilled",
        "previousBackfilled",
        "appliedBackfilled",
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}

/**
 * Two cheap structural guards used before a JSON parser or a ZIP reader sees a file.
 * Both operate in one pass and never allocate on the input's behalf.
 */
object JsonSafety {

    /**
     * Rejects documents nested deeper than [maxDepth]. A bounded depth keeps a hostile
     * (but size-legal) document from exhausting the parser stack. Brackets inside string
     * literals are ignored.
     */
    fun requireBoundedDepth(text: String, maxDepth: Int) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > maxDepth) {
                        throw BackupException("备份的数据结构过于复杂，无法恢复。")
                    }
                }
                '}', ']' -> depth -= 1
                else -> Unit
            }
        }
    }
}
