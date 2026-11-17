package com.daigan.record.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 合成数据集构造器：按**列目录**对齐，未给出的列一律 null。
 *
 * [TableInput.presentColumns] 用来模拟"文件里根本没有这一列"（历史缺列），
 * 这是 `category-service-defaults` 分层映射的前提条件；默认值代表 vnext 的完整列集。
 */
object BackupFixtures {

    class TableInput(
        val name: String,
        val rows: List<Map<String, Any?>>,
        val presentColumns: List<String>? = null,
    )

    fun table(
        name: String,
        rows: List<Map<String, Any?>>,
        presentColumns: List<String>? = null,
    ): TableInput = TableInput(name, rows, presentColumns)

    fun build(vararg inputs: TableInput): BackupDataSet {
        val byName = inputs.associateBy { it.name }
        return BackupDataSet(
            BackupTableCatalog.includedTables.map { table ->
                val input = byName[table.name]
                val rows = input?.rows.orEmpty().map { row -> toRow(table, row) }
                val present = input?.presentColumns ?: table.writtenColumnNames
                BackupDataSet.TableRows(table, rows, presentColumns = present)
            },
        )
    }

    private fun toRow(
        table: BackupTableCatalog.Table,
        row: Map<String, Any?>,
    ): List<JsonElement?> = table.columns.map { column ->
        when (val value = row[column.name]) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(if (value) 1 else 0)
            is JsonElement -> value
            else -> throw IllegalArgumentException("fixture 不支持的值类型 ${value::class}")
        }
    }

    /** 便捷：只声明"存在这些列"，用于历史文件。 */
    fun legacyColumns(table: String, include: Set<String>): List<String> {
        val spec = checkNotNull(BackupTableCatalog.table(table))
        // 用完整列（含 legacy-only），才能模拟"当年这一列确实存在"的历史文件。
        return spec.columnNames.filter { it in include }
    }
}
