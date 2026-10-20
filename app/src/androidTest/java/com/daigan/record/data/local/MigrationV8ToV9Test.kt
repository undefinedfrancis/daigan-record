package com.daigan.record.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Legacy rows are inserted using the immutable exported v8 schema. */
@RunWith(AndroidJUnit4::class)
class MigrationV8ToV9Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v8-to-v9-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() { context.deleteDatabase(databaseName) }

    @Test
    fun everyV8ColumnAndRowSurvivesWithNewNullableFieldsUnset() {
        lateinit var columns: Map<String, List<String>>
        val before = helper.createDatabase(databaseName, 8).use { database ->
            insertV8Fixture(database)
            columns = legacyColumns(database)
            snapshot(database, columns)
        }
        helper.runMigrationsAndValidate(databaseName, 9, true, RecordDatabase.MIGRATION_8_9).use { database ->
            assertEquals(before, snapshot(database, columns))
            database.query("SELECT `loginDevice` FROM `game_accounts`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                do { assertTrue("legacy game_accounts.loginDevice must remain NULL", cursor.isNull(0)) }
                while (cursor.moveToNext())
            }
            for (table in listOf("game_server_options", "order_price_changes")) {
                database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table must start empty", 0, cursor.getInt(0))
                }
            }
            database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun everyHistoricalExportMigratesThroughTheShippedChainToCurrentVersion() {
        for (version in 1 until RecordDatabase.RECORD_DATABASE_VERSION) {
            context.deleteDatabase(databaseName)
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(
                databaseName,
                RecordDatabase.RECORD_DATABASE_VERSION,
                true,
                *RecordDatabase.ALL_MIGRATIONS,
            ).use { database ->
                assertEquals(RecordDatabase.RECORD_DATABASE_VERSION, database.version)
            }
        }
    }

    @Test
    fun newTablesAcceptRowsAndPriceChangesRestrictOrderDeletion() {
        helper.createDatabase(databaseName, 8).use { database -> insertV8Fixture(database) }
        helper.runMigrationsAndValidate(databaseName, 9, true, RecordDatabase.MIGRATION_8_9).use { database ->
            database.execSQL(
                "INSERT INTO game_server_options (id, gameName, name, sortOrder) " +
                    "VALUES ('opt-1', '重返未来：1999', '官服', 0)",
            )
            database.query("SELECT COUNT(*) FROM game_server_options").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            // A bare order with no periods/tasks/receipts: only the price-change row can
            // block its deletion, so a rejection proves the new FK is enforced.
            database.execSQL(
                """
                INSERT INTO orders
                    (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                     gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                     createdAt, updatedAt, completedAt, sourceTemplateId, sourceTemplateName,
                     sourceTemplateVersion, cancelledAt)
                VALUES ('order-bare', 'account-1', 'HOSTING', '空单', NULL,
                        'ONE_TIME', NULL, NULL, 'Asia/Shanghai', 240, 1, 300,
                        1700000002000, 1700000002000, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO order_price_changes " +
                    "(id, operationId, orderId, periodId, previousPriceCents, newPriceCents, " +
                    "reason, createdAt) VALUES " +
                    "('pc-1', 'op-pc-1', 'order-bare', NULL, 30000, 25000, " +
                    "'拆分活动价', 1700000400000)",
            )
            database.query("SELECT COUNT(*) FROM order_price_changes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.execSQL("PRAGMA foreign_keys = ON")
            try {
                database.execSQL("DELETE FROM orders WHERE id = 'order-bare'")
                fail("order with price-change audit rows must not be deletable")
            } catch (expected: Exception) {
                // FK RESTRICT from order_price_changes.orderId
            }
            database.query("SELECT COUNT(*) FROM orders WHERE id = 'order-bare'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("order must survive the rejected delete", 1, cursor.getInt(0))
            }
            // With the audit row gone the bare order deletes cleanly.
            database.execSQL("DELETE FROM order_price_changes WHERE id = 'pc-1'")
            database.execSQL("DELETE FROM orders WHERE id = 'order-bare'")
        }
    }

    private fun legacyColumns(database: SupportSQLiteDatabase): Map<String, List<String>> {
        val tables = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            database.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
        }
    }

    private fun snapshot(
        database: SupportSQLiteDatabase,
        columns: Map<String, List<String>>,
    ): Map<String, List<List<String?>>> = columns.mapValues { (table, names) ->
        val projection = names.joinToString(", ") { "`$it`" }
        database.query("SELECT $projection FROM `$table` ORDER BY $projection").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    })
                }
            }
        }
    }

    /** Fixture covering every v8 table with at least one row where business data lives. */
    private fun insertV8Fixture(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO owners
                (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('owner-1', 1, 'v8老板', 1700000000000, 1700000000000, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)")
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod, password,
                 createdAt, updatedAt, archivedAt, archiveBatchId, defaultTemplateId)
            VALUES ('account-1', 'owner-1', '重返未来：1999', '官服', '10000628',
                    'v8-login', 'PASSWORD', 'secret', 1700000000000, 1700000000000,
                    NULL, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO orders
                (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                 gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                 createdAt, updatedAt, completedAt, sourceTemplateId, sourceTemplateName,
                 sourceTemplateVersion, cancelledAt)
            VALUES ('order-short', 'account-1', 'SHORT_TASK', 'v8短期单', 12345,
                    'INSTALLMENTS', '2026-10-30', '历史备注', 'Asia/Shanghai', 240, 1, 300,
                    1700000000000, 1700000000000, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO orders
                (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                 gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                 createdAt, updatedAt, completedAt, sourceTemplateId, sourceTemplateName,
                 sourceTemplateVersion, cancelledAt)
            VALUES ('order-hosting', 'account-1', 'HOSTING', 'v8托管单', NULL,
                    'ONE_TIME', NULL, NULL, 'Asia/Shanghai', 240, 1, 300,
                    1700000001000, 1700000001000, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO hosting_periods
                (id, orderId, periodIndex, startDate, endDate, priceCents, gameZoneId,
                 dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes, createdAt,
                 renewalOperationId)
            VALUES ('period-1', 'order-hosting', 0, '2026-10-01', '2026-10-10', 30000,
                    'Asia/Shanghai', 240, 1, 300, 1700000001000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_definitions
                (id, orderId, periodId, title, progressType, startValue, targetValue,
                 currentValue, unit, recurrence, weekdays, everyNDays, sortOrder,
                 completedAt, completionBatchId, createdAt, updatedAt, monthDay)
            VALUES ('task-short', 'order-short', NULL, '推进主线', 'CHECKBOX', '0', '1', '0',
                    NULL, 'DAILY', NULL, NULL, 0, NULL, NULL, 1700000001000, 1700000001000,
                    NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_definitions
                (id, orderId, periodId, title, progressType, startValue, targetValue,
                 currentValue, unit, recurrence, weekdays, everyNDays, sortOrder,
                 completedAt, completionBatchId, createdAt, updatedAt, monthDay)
            VALUES ('task-hosting', 'order-hosting', 'period-1', '每日签到', 'CHECKBOX', '0',
                    '1', '0', NULL, 'DAILY', NULL, NULL, 0, NULL, NULL, 1700000001000,
                    1700000001000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO completion_batches
                (id, operationId, scope, targetId, gameDate, createdAt, undoneAt)
            VALUES ('batch-1', 'op-batch-1', 'HOSTING_ORDER', 'order-hosting',
                    '2026-10-05', 1700000100000, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO task_occurrences
                (id, taskDefinitionId, occurrenceKey, gameDate, windowStart, windowEnd,
                 completedAt, recordedAt, backfilled, completionBatchId, value, createdAt)
            VALUES ('occ-1', 'task-hosting', 'D:2026-10-05', '2026-10-05', '2026-10-05',
                    '2026-10-05', 1700000100000, 1700000100000, 0, 'batch-1', NULL,
                    1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO completion_batch_items
                (id, batchId, itemType, occurrenceId, taskDefinitionId, previousCompletedAt,
                 previousRecordedAt, previousBackfilled, previousValue,
                 previousCompletionBatchId, appliedCompletedAt, appliedRecordedAt,
                 appliedBackfilled, appliedValue, appliedCompletionBatchId)
            VALUES ('item-1', 'batch-1', 'OCCURRENCE', 'occ-1', 'task-hosting', NULL, NULL,
                    NULL, NULL, NULL, 1700000100000, 1700000100000, 0, NULL, 'batch-1')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO attachments
                (id, taskDefinitionId, occurrenceId, relativePath, mimeType, sizeBytes, createdAt)
            VALUES ('attachment-1', NULL, 'occ-1', 'attachments/shot.jpg', 'image/jpeg', 4096,
                    1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO receipts
                (id, operationId, orderId, periodId, amountCents, receivedAt, channel, note,
                 status, replacedByReceiptId, createdAt)
            VALUES ('receipt-1', 'op-receipt-1', 'order-short', NULL, 10000,
                    1700000100000, 'WECHAT', NULL, 'ACTIVE', NULL, 1700000100000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO receipt_corrections
                (id, operationId, receiptId, kind, reason, previousAmountCents,
                 previousReceivedAt, previousChannel, previousNote, newReceiptId,
                 newAmountCents, newReceivedAt, newChannel, newNote, createdAt)
            VALUES ('correction-1', 'op-correction-1', 'receipt-1', 'AMEND', '补记',
                    10000, 1700000100000, 'WECHAT', NULL, NULL, 12000, 1700000100000,
                    'WECHAT', NULL, 1700000200000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO order_creation_operations
                (operationId, orderType, fingerprint, orderId, receiptId, createdAt)
            VALUES ('op-create-1', 'SHORT_TASK', 'fingerprint-1', 'order-short', 'receipt-1',
                    1700000000000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO game_templates
                (id, gameName, templateVersion, gameVersionNotes, zoneId, dailyRefreshMinutes,
                 weeklyRefreshDay, weeklyRefreshMinutes, origin, builtInKey, sortOrder,
                 createdAt, updatedAt)
            VALUES ('tpl-1', '重返未来：1999', 'v1', NULL, 'Asia/Shanghai', 240, 1, 300,
                    'USER', NULL, 1, 1700000000000, 1700000000000)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO template_categories VALUES ('category-1', 'tpl-1', '日常', 0)")
        database.execSQL(
            """
            INSERT INTO template_tasks
                (id, templateId, categoryId, title, progressType, startValue, targetValue,
                 unit, recurrence, weekdays, everyNDays, sortOrder, monthDay)
            VALUES ('tpl-task-1', 'tpl-1', 'category-1', '签到', 'CHECKBOX', '0', '1',
                    NULL, 'DAILY', NULL, NULL, 0, NULL)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO template_seed_state VALUES ('generic', 1700000000000)")
        database.execSQL(
            "INSERT INTO reminder_settings VALUES (0, 0, 0, 0, 0, 0, 0, 1700000300000)",
        )
        database.execSQL("INSERT INTO app_settings VALUES (0, 'dataset-1', NULL, 1700000300000)")
        database.execSQL("INSERT INTO reminder_deliveries VALUES ('SHORT_DEADLINE', '2026-10-18', 1700000300000)")
    }
}
