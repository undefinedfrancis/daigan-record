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

/** Legacy rows are inserted using the immutable exported v9 schema. */
@RunWith(AndroidJUnit4::class)
class MigrationV9ToV10Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v9-to-v10-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun everyHistoricalExportMigratesThroughTheShippedChainToTen() {
        for (version in 1..9) {
            context.deleteDatabase(databaseName)
            helper.createDatabase(databaseName, version).close()
            helper.runMigrationsAndValidate(databaseName, 10, true, *RecordDatabase.ALL_MIGRATIONS)
                .use { database -> assertEquals(10, database.version) }
        }
    }

    @Test
    fun legacyRowsSurviveAndThePresetTableStartsEmptyThenAcceptsRows() {
        lateinit var columns: Map<String, List<String>>
        val before = helper.createDatabase(databaseName, 9).use { database ->
            insertV9Fixture(database)
            columns = legacyColumns(database)
            snapshot(database, columns)
        }
        helper.runMigrationsAndValidate(databaseName, 10, true, RecordDatabase.MIGRATION_9_10)
            .use { database ->
                assertEquals(before, snapshot(database, columns))
                database.query("SELECT COUNT(*) FROM `template_price_presets`").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("legacy templates keep no price presets", 0, cursor.getInt(0))
                }
                // The new table accepts rows and its foreign key is enforced, so a preset
                // can never outlive the template that owns it.
                database.execSQL(
                    "INSERT INTO template_price_presets " +
                        "(id, templateId, amountCents, label, sortOrder) " +
                        "VALUES ('preset-1', 'tpl-1', 1250, '日常价', 0)",
                )
                database.query("SELECT COUNT(*) FROM template_price_presets").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                database.execSQL("PRAGMA foreign_keys = ON")
                try {
                    database.execSQL(
                        "INSERT INTO template_price_presets " +
                            "(id, templateId, amountCents, label, sortOrder) " +
                            "VALUES ('preset-2', 'tpl-missing', 999, NULL, 1)",
                    )
                    fail("a preset must not be insertable for an unknown template")
                } catch (expected: Exception) {
                    // FK from template_price_presets.templateId
                }
                try {
                    database.execSQL("DELETE FROM game_templates WHERE id = 'tpl-1'")
                    fail("a template with price presets must not be deletable")
                } catch (expected: Exception) {
                    // FK RESTRICT from template_price_presets.templateId
                }
                database.query("SELECT COUNT(*) FROM game_templates WHERE id = 'tpl-1'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(
                            "template must survive the rejected delete",
                            1,
                            cursor.getInt(0),
                        )
                    }
                database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
    }

    private fun legacyColumns(database: SupportSQLiteDatabase): Map<String, List<String>> {
        val tables = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            database.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
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
                    add(
                        (0 until cursor.columnCount).map { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        },
                    )
                }
            }
        }
    }

    /** Fixture covering every v9 table with at least one row where business data lives. */
    private fun insertV9Fixture(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO owners
                (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId)
            VALUES ('owner-1', 1, 'v9老板', 1700000000000, 1700000000000, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)")
        database.execSQL(
            """
            INSERT INTO game_accounts
                (id, ownerId, gameName, server, uid, loginAccount, loginMethod, password,
                 createdAt, updatedAt, archivedAt, archiveBatchId, defaultTemplateId,
                 loginDevice)
            VALUES ('account-1', 'owner-1', '重返未来：1999', '官服', '10000628',
                    'v9-login', 'PASSWORD', 'secret', 1700000000000, 1700000000000,
                    NULL, NULL, NULL, '手机')
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO game_server_options (id, gameName, name, sortOrder) " +
                "VALUES ('opt-1', '重返未来：1999', '官服', 0)",
        )
        database.execSQL(
            """
            INSERT INTO orders
                (id, accountId, type, title, priceCents, paymentMode, deadlineDate, notes,
                 gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes,
                 createdAt, updatedAt, completedAt, sourceTemplateId, sourceTemplateName,
                 sourceTemplateVersion, cancelledAt)
            VALUES ('order-short', 'account-1', 'SHORT_TASK', 'v9短期单', 12345,
                    'INSTALLMENTS', '2026-10-30', '历史备注', 'Asia/Shanghai', 240, 1, 300,
                    1700000000000, 1700000000000, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO order_price_changes " +
                "(id, operationId, orderId, periodId, previousPriceCents, newPriceCents, " +
                "reason, createdAt) VALUES " +
                "('pc-1', 'op-pc-1', 'order-short', NULL, 30000, 25000, '补差价', " +
                "1700000400000)",
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
        database.execSQL(
            "INSERT INTO reminder_deliveries VALUES ('SHORT_DEADLINE', '2026-10-18', " +
                "1700000300000)",
        )
    }
}
