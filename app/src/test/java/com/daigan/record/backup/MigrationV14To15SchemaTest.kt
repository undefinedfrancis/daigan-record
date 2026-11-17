package com.daigan.record.backup

import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 14→15 迁移的**静态**核对：把产品自己的列目录与 Room 真实导出的 `15.json` 对齐。
 *
 * 这不是执行级迁移测试（那需要一个真实 SQLite/Room 运行环境，见交接文档的
 * "未验证"章节），但它能证明：
 * - 版本号只在一处声明；
 * - 每条 `ALTER TABLE ... ADD COLUMN` 的列在真实 schema 里存在且可空性正确；
 * - 重建后的 `hosting_sessions.periodId` 是 NOT NULL；
 * - 新表与索引名与迁移脚本一致；
 * - 导出/导入的列目录与 Room 的物理 schema 完全一致（不会悄悄错列）。
 */
class MigrationV14To15SchemaTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun schemaFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val current = directory ?: return@repeat
            val candidate = File(
                current,
                "schemas/com.daigan.record.data.local.RecordDatabase/15.json",
            )
            if (candidate.isFile) return candidate
            val nested = File(
                current,
                "app/schemas/com.daigan.record.data.local.RecordDatabase/15.json",
            )
            if (nested.isFile) return nested
            directory = current.parentFile
        }
        throw IllegalStateException("找不到 15.json：请从 app 模块运行该测试")
    }

    private fun entities(): Map<String, JsonObject> {
        val root = json.parseToJsonElement(schemaFile().readText()) as JsonObject
        val database = root["database"] as JsonObject
        val array = database["entities"] as JsonArray
        return array.associate { element ->
            val entity = element as JsonObject
            (entity["tableName"] as JsonPrimitive).content to entity
        }
    }

    @Test
    fun `exported schema version matches the single source of truth`() {
        val root = json.parseToJsonElement(schemaFile().readText()) as JsonObject
        val database = root["database"] as JsonObject
        assertEquals(15, (database["version"] as JsonPrimitive).content.toInt())
        assertEquals(15, RECORD_DATABASE_VERSION)
        assertEquals(15, BackupTableCatalog.PORTABLE_SCHEMA_VERSION)
    }

    @Test
    fun `catalog columns match the real Room schema exactly`() {
        val byTable = entities()
        BackupTableCatalog.tables.forEach { table ->
            val entity = byTable[table.name]
            assertNotNull("15.json 缺少表 ${table.name}", entity)
            val fields = (entity!!["fields"] as JsonArray).map { it as JsonObject }
            val names = fields.map { (it["columnName"] as JsonPrimitive).content }
            // legacy-only 列只存在于历史**文件**里，物理 schema 不应有它们。
            assertEquals("表 ${table.name} 列顺序不一致", table.writtenColumnNames, names)
            table.columns.filterNot { it.legacyOnly }.forEachIndexed { index, column ->
                val field = fields[index]
                // Room 只在 true 时写出 notNull；缺失即"可空"。
                val notNull = (field["notNull"] as? JsonPrimitive)?.content?.toBoolean() ?: false
                assertEquals(
                    "表 ${table.name}.${column.name} 可空性与真实 schema 不一致",
                    !column.nullable,
                    notNull,
                )
            }
        }
    }

    @Test
    fun `hosting_sessions is rebuilt with a NOT NULL owning period`() {
        val entity = entities()["hosting_sessions"]
        assertNotNull(entity)
        val field = (entity!!["fields"] as JsonArray)
            .map { it as JsonObject }
            .single { (it["columnName"] as JsonPrimitive).content == "periodId" }
        assertTrue((field["notNull"] as? JsonPrimitive)?.content?.toBoolean() ?: false)
        val createSql = (entity["createSql"] as JsonPrimitive).content
        assertTrue("periodId 必须是 NOT NULL", createSql.contains("`periodId` TEXT NOT NULL"))
        // 原有业务列一个也不能丢。
        listOf("id", "orderId", "sessionIndex", "createdAt", "completedAt", "completionBatchId")
            .forEach { assertTrue("重建后缺少 $it", createSql.contains("`$it`")) }
    }

    @Test
    fun `new tables and their indexes exist in the real schema`() {
        val byTable = entities()
        val categories = checkNotNull(byTable["account_categories"])
        val categoryIndexes = (categories["indices"] as JsonArray).map { it as JsonObject }
        assertTrue(
            categoryIndexes.any {
                (it["name"] as JsonPrimitive).content == "index_account_categories_name" &&
                    ((it["unique"] as? JsonPrimitive)?.content?.toBoolean() ?: false)
            },
        )
        assertTrue(
            categoryIndexes.any {
                (it["name"] as JsonPrimitive).content == "index_account_categories_sortOrder"
            },
        )

        val events = checkNotNull(byTable["task_progress_events"])
        val eventIndexes = (events["indices"] as JsonArray).map { it as JsonObject }
        listOf(
            "index_task_progress_events_taskDefinitionId",
            "index_task_progress_events_operationId",
            "index_task_progress_events_taskDefinitionId_sequence",
            "index_task_progress_events_scopePeriodId",
            "index_task_progress_events_scopeSessionId",
            "index_task_progress_events_gameDate",
            "index_task_progress_events_correctsEventId",
        ).forEach { name ->
            assertTrue("缺少索引 $name", eventIndexes.any { (it["name"] as JsonPrimitive).content == name })
        }
    }

    @Test
    fun `columns added by the migration are nullable except the round owner`() {
        val byTable = entities()
        fun nullable(table: String, column: String): Boolean {
            val fields = (checkNotNull(byTable[table])["fields"] as JsonArray).map { it as JsonObject }
            val field = fields.single { (it["columnName"] as JsonPrimitive).content == column }
            return !((field["notNull"] as? JsonPrimitive)?.content?.toBoolean() ?: false)
        }
        listOf(
            "game_accounts" to "categoryId",
            "game_templates" to "serviceWeekdays",
            "game_templates" to "defaultOrderTitle",
            "template_categories" to "hostingMode",
            "template_categories" to "targetSessions",
            "template_categories" to "serviceWeekdays",
            "template_tasks" to "activeFrom",
            "template_tasks" to "activeUntil",
            "template_tasks" to "numericMode",
            "orders" to "monthlyRefreshDays",
            "hosting_periods" to "monthlyRefreshDays",
            "hosting_periods" to "serviceCount",
            "task_definitions" to "activeUntil",
            "task_definitions" to "numericMode",
            "task_definitions" to "colorArgb",
        ).forEach { (table, column) ->
            assertTrue("$table.$column 必须是可空列（历史行不存在该值）", nullable(table, column))
        }
        // 归属期次是唯一非空新增列。
        assertTrue(!nullable("hosting_sessions", "periodId"))
    }

    @Test
    fun `every shipped migration is still registered`() {
        val migrations = com.daigan.record.data.local.RecordDatabase.ALL_MIGRATIONS
        val pairs = migrations.map { "${it.startVersion}->${it.endVersion}" }
        assertEquals(
            listOf(
                "1->2", "2->3", "3->4", "4->5", "5->6", "6->7", "7->8", "8->9",
                "9->10", "10->11", "11->12", "12->13", "13->14", "14->15",
            ),
            pairs,
        )
    }
}
