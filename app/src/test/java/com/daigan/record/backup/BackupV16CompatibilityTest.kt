package com.daigan.record.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BackupV16CompatibilityTest {
    /**
     * Columns that did not exist in the oldest restorable generation, per table.
     *
     * `game_accounts` accumulated two: loginDevice (schema 9) and resourceNotes
     * (schema 11). A legacy file may carry neither, either, or both, so the legacy
     * shape is "every current column minus this set".
     */
    private val added = mapOf(
        // schema 13 adds the hosting mode and agreed round count, schema 14 the order colour.
        "orders" to setOf(
            "cancelledAt", "archivedAt", "hostingMode", "targetSessions", "orderColor",
            "monthlyRefreshDays",
        ),
        "hosting_periods" to setOf(
            "serviceDays", "serviceWeekdays", "monthlyRefreshDays", "serviceCount",
        ),
        "task_definitions" to setOf(
            "monthDay", "monthDays", "retiredAt", "activeFrom",
            "activeUntil", "numericMode", "colorArgb",
        ),
        "template_tasks" to setOf(
            "monthDay", "monthDays", "activeFrom", "activeUntil", "numericMode",
        ),
        "template_categories" to setOf("hostingMode", "targetSessions", "serviceWeekdays"),
        "game_accounts" to setOf("loginDevice", "resourceNotes", "categoryId"),
        "game_templates" to setOf(
            "hostingMode", "targetSessions", "serviceWeekdays", "defaultOrderTitle",
        ),
    )

    /**
     * 合成一个"旧代次文件"：内容按旧列集裁剪，并声明它当年真实的便携 schema。
     * 解码器只对低于当前版本的输入放行缺列，因此这里必须同时改声明版本。
     */
    private fun encodeLegacy(dataSet: BackupDataSet, schema: Int): String =
        BackupDataCodec.encode(dataSet).replace("\"schemaVersion\":15", "\"schemaVersion\":$schema")

    private fun legacyColumns(table: BackupTableCatalog.Table): List<BackupTableCatalog.Column> =
        table.columns.filter { it.name !in added[table.name].orEmpty() }
    private val v17NewTables = setOf(
        "game_server_options", "order_price_changes",
        // schema 15 新增：旧文件里整张表都不存在。
        "account_categories", "hosting_sessions", "task_progress_events",
    )
    private val legacyRows: Map<String, Map<String, Any?>> = mapOf(
        "orders" to linkedMapOf(
            "id" to "order-old", "accountId" to "account-old", "type" to "SHORT_TASK",
            "title" to "旧订单", "priceCents" to 12345L, "paymentMode" to "INSTALLMENTS",
            "deadlineDate" to "2026-10-30", "notes" to "旧备注", "gameZoneId" to "Asia/Shanghai",
            "dailyRefreshMinutes" to 240L, "weeklyRefreshDay" to 2L, "weeklyRefreshMinutes" to 300L,
            "createdAt" to 1700000000000L, "updatedAt" to 1700000001000L, "completedAt" to null,
            "sourceTemplateId" to "template-old", "sourceTemplateName" to "旧模板", "sourceTemplateVersion" to "v7",
        ),
        "task_definitions" to linkedMapOf(
            "id" to "task-old", "orderId" to "order-old", "periodId" to null,
            "title" to "旧任务", "progressType" to "NUMBER", "startValue" to "2.5",
            "targetValue" to "99.5", "currentValue" to "5.5", "unit" to "个",
            "recurrence" to "EVERY_N_DAYS", "weekdays" to null, "everyNDays" to 3L,
            "sortOrder" to 7L, "completedAt" to null, "completionBatchId" to null,
            "createdAt" to 1700000002000L, "updatedAt" to 1700000003000L,
        ),
        "template_tasks" to linkedMapOf(
            "id" to "template-task-old", "templateId" to "template-old", "categoryId" to "category-old",
            "title" to "旧模板任务", "progressType" to "NUMBER", "startValue" to "10.5",
            "targetValue" to "50.5", "unit" to "份", "recurrence" to "EVERY_N_DAYS",
            "weekdays" to null, "everyNDays" to 4L, "sortOrder" to 8L,
        ),
        "game_accounts" to linkedMapOf(
            "id" to "account-old", "ownerId" to "owner-old", "gameName" to "旧游戏",
            "server" to "官服", "uid" to "uid-old", "loginAccount" to "login-old",
            "loginMethod" to "PASSWORD", "password" to "旧密码", "createdAt" to 1700000000000L,
            "updatedAt" to 1700000001000L, "archivedAt" to null, "archiveBatchId" to null,
            "defaultTemplateId" to null,
        ),
    )

    private fun value(raw: Any?): JsonElement? = when (raw) {
        null -> null
        is Long -> JsonPrimitive(raw)
        else -> JsonPrimitive(raw as String)
    }

    private fun legacyData(): BackupDataSet = BackupDataSet(BackupTableCatalog.includedTables.map { table ->
        val legacyTable = table.copy(columns = legacyColumns(table))
        val data = legacyRows[table.name]
        BackupDataSet.TableRows(legacyTable, if (data == null) emptyList() else
            listOf(legacyTable.columns.map { column -> value(data.getValue(column.name)) }))
    })

    @Test
    fun versionSevenBackupAddsNullFieldsWithoutShiftingAnyLegacyValue() {
        val decoded = BackupDataCodec.decode(encodeLegacy(legacyData(), 7), 7)
        assertEquals(BackupTableCatalog.includedTables.size, decoded.tables.size)
        for ((name, expected) in legacyRows) {
            val table = decoded.tables.single { it.table.name == name }
            val row = table.rows.single()
            for ((column, original) in expected) {
                assertEquals("$name.$column", value(original), row[table.table.columnNames.indexOf(column)])
            }
            for (newColumn in added.getValue(name)) {
                assertNull("$name.$newColumn new field", row[table.table.columnNames.indexOf(newColumn)])
            }
        }
        assertEquals(4, decoded.tables.sumOf { it.rows.size })
    }

    @Test
    fun missingAnyOriginalColumnIsRejectedEvenWhenNewColumnsAreOptional() {
        val legacy = legacyData()
        // 只针对**物理列**：legacy-only 列（历史网页扩展）本来就不写出。
        for (target in legacy.tables) for (columnIndex in target.table.columns.indices) {
            if (target.table.columns[columnIndex].legacyOnly) continue
            // 读取兼容白名单里的列本来就允许缺失（例如 13/14 文件的 periodId）。
            if (target.table.columns[columnIndex].name in
                BackupTableCatalog.legacyOptionalColumns[target.table.name].orEmpty()
            ) {
                continue
            }
            val broken = BackupDataSet(legacy.tables.map { table ->
                if (table.table.name != target.table.name) table else BackupDataSet.TableRows(
                    table.table.copy(columns = table.table.columns.filterIndexed { index, _ -> index != columnIndex }),
                    table.rows.map { row -> row.filterIndexed { index, _ -> index != columnIndex } },
                )
            })
            try {
                BackupDataCodec.decode(encodeLegacy(broken, 7), 7)
                fail("Accepted missing legacy column ${target.table.name}.${target.table.columns[columnIndex].name}")
            } catch (_: BackupException) { }
        }
    }

    @Test
    fun v16BackupWithoutNewTablesRestoresWithEmptyCollectionsAndNullDevice() {
        // A real v1.6 file has no game_server_options / order_price_changes tables at all
        // and no game_accounts.loginDevice or resourceNotes column.
        val legacyTables = BackupTableCatalog.includedTables
            .filter { it.name !in v17NewTables }
            .map { table ->
                val spec = table.copy(columns = legacyColumns(table))
                val data = legacyRows[table.name]
                BackupDataSet.TableRows(
                    spec,
                    if (data == null) emptyList() else
                        listOf(spec.columns.map { column -> value(data.getValue(column.name)) }),
                )
            }
        val decoded = BackupDataCodec.decode(encodeLegacy(BackupDataSet(legacyTables), 8), 8)
        assertEquals(0, decoded.count("game_server_options"))
        assertEquals(0, decoded.count("order_price_changes"))
        val accountTable = decoded.tables.single { it.table.name == "game_accounts" }
        val account = accountTable.rows.single()
        assertNull(account[accountTable.table.columnNames.indexOf("loginDevice")])
        assertNull(
            "a v1.6 backup predates resourceNotes and restores it as NULL",
            account[accountTable.table.columnNames.indexOf("resourceNotes")],
        )
        assertEquals(value("旧游戏"), account[accountTable.table.columnNames.indexOf("gameName")])
    }

    @Test
    fun v17BackupRoundTripsDeviceServerOptionsAndPriceChanges() {
        val full = legacyData().let { base ->
            // Upgrade the legacy-shape rows to current shape and fill the new tables.
            BackupDataSet(base.tables.map { tableRows ->
                when (tableRows.table.name) {
                    "game_accounts" -> {
                        val spec = BackupTableCatalog.table("game_accounts")!!
                        val legacyRow = tableRows.rows.single()
                        val row = spec.columns.map { column ->
                            when (column.name) {
                                "loginDevice" -> JsonPrimitive("平板")
                                "resourceNotes" -> JsonPrimitive("3 星专武 · 60 抽")
                                // 新列（如 schema 15 的 categoryId）在旧行里没有位置。
                                else -> tableRows.table.columnNames.indexOf(column.name)
                                    .takeIf { it >= 0 }
                                    ?.let { legacyRow[it] }
                            }
                        }
                        BackupDataSet.TableRows(spec, listOf(row))
                    }
                    "game_server_options" -> BackupDataSet.TableRows(
                        tableRows.table,
                        listOf(listOf(
                            JsonPrimitive("opt-1"),
                            JsonPrimitive("旧游戏"),
                            JsonPrimitive("官服"),
                            JsonPrimitive(0),
                        )),
                    )
                    "order_price_changes" -> BackupDataSet.TableRows(
                        tableRows.table,
                        listOf(listOf(
                            JsonPrimitive("pc-1"),
                            JsonPrimitive("op-pc-1"),
                            JsonPrimitive("order-old"),
                            null,
                            JsonPrimitive(12345L),
                            JsonPrimitive(10000L),
                            JsonPrimitive("拆分活动价"),
                            JsonPrimitive(1700000400000L),
                        )),
                    )
                    else -> tableRows
                }
            })
        }
        // v1.7 形状的文件：声明 schema 9，缺列只允许出现在历史白名单里。
        val decoded = BackupDataCodec.decode(encodeLegacy(full, 9), 9)
        val account = decoded.tables.single { it.table.name == "game_accounts" }
        assertEquals("平板", (account.rows.single()[account.table.columnNames.indexOf("loginDevice")] as JsonPrimitive).content)
        assertEquals(
            "3 星专武 · 60 抽",
            (account.rows.single()[account.table.columnNames.indexOf("resourceNotes")] as JsonPrimitive).content,
        )
        assertEquals(1, decoded.count("game_server_options"))
        assertEquals(1, decoded.count("order_price_changes"))
    }

    @Test
    fun manifestFromSchemaEightWithLegacyTableCountsIsAccepted() {
        val legacyCounts = BackupTableCatalog.includedTables
            .filter { it.name !in v17NewTables }
            .associate { it.name to 0 }
        val manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = 8,
            exportedAt = 1700000000000,
            generator = "daigan-record 1.6",
            dataSha256 = "a".repeat(64),
            tableCounts = legacyCounts,
            attachments = emptyList(),
            attachmentTotalBytes = 0,
        )
        val decoded = BackupManifestCodec.decode(
            BackupManifestCodec.encode(manifest),
            currentSchemaVersion = 9,
        )
        assertEquals(8, decoded.schemaVersion)
        assertEquals(legacyCounts, decoded.tableCounts)
    }

    @Test
    fun manifestWithUnknownTableIsStillRejected() {
        val counts = BackupTableCatalog.includedTables.associate { it.name to 0 } +
            mapOf("unexpected_table" to 1)
        val manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = 9,
            exportedAt = 1700000000000,
            generator = "daigan-record 1.7",
            dataSha256 = "a".repeat(64),
            tableCounts = counts,
            attachments = emptyList(),
            attachmentTotalBytes = 0,
        )
        try {
            BackupManifestCodec.decode(BackupManifestCodec.encode(manifest), currentSchemaVersion = 9)
            fail("Accepted a manifest with an unknown table")
        } catch (_: BackupException) { }
    }

    @Test
    fun manifestFromUnsupportedSchemaVersionIsRejected() {
        val counts = BackupTableCatalog.includedTables.associate { it.name to 0 }
        val manifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = 6,
            exportedAt = 1700000000000,
            generator = "daigan-record 1.5",
            dataSha256 = "a".repeat(64),
            tableCounts = counts,
            attachments = emptyList(),
            attachmentTotalBytes = 0,
        )
        try {
            BackupManifestCodec.decode(BackupManifestCodec.encode(manifest), currentSchemaVersion = 9)
            fail("Accepted a schema 6 manifest")
        } catch (_: BackupException) { }
    }
}
