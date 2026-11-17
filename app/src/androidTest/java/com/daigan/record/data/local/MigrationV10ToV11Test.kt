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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema 11 adds only `game_accounts.resourceNotes`. The migration must be additive: no
 * legacy value changes, the new column reads back as NULL, and the resulting schema is
 * exactly what Room expects (validated by [MigrationTestHelper]).
 */
@RunWith(AndroidJUnit4::class)
class MigrationV10ToV11Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-v10-to-v11-test.db"

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
    fun legacyAccountsKeepEveryValueAndGainANullResourceColumn() {
        val columns: List<String>
        val before: Map<String, List<List<String?>>>
        helper.createDatabase(databaseName, 10).use { database ->
            insertV10Fixture(database)
            columns = accountColumns(database)
            assertFalse("precondition: schema 10 has no resourceNotes", "resourceNotes" in columns)
            before = snapshot(database, columns)
        }

        helper.runMigrationsAndValidate(databaseName, 11, true, RecordDatabase.MIGRATION_10_11)
            .use { database ->
                assertEquals(11, database.version)
                assertEquals(
                    "every schema-10 column is still present and in the same order",
                    columns,
                    accountColumns(database).filter { it != "resourceNotes" },
                )
                assertTrue("resourceNotes must be appended", "resourceNotes" in accountColumns(database))
                assertEquals("no legacy value changed", before, snapshot(database, columns))
                database.query("SELECT id, resourceNotes FROM game_accounts ORDER BY id")
                    .use { cursor ->
                        var rows = 0
                        while (cursor.moveToNext()) {
                            rows++
                            assertTrue("row ${cursor.getString(0)} must keep NULL", cursor.isNull(1))
                        }
                        assertEquals(2, rows)
                    }
                // The new column is writable and is not overwritten by anything else.
                database.execSQL(
                    "UPDATE game_accounts SET resourceNotes = '3 星专武' WHERE id = 'account-1'",
                )
                database.query("SELECT resourceNotes FROM game_accounts WHERE id = 'account-1'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("3 星专武", cursor.getString(0))
                    }
                database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                database.query("PRAGMA integrity_check").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            }
    }

    @Test
    fun aSchemaTenDatabaseMigratesToTheCurrentVersionThroughTheShippedChain() {
        helper.createDatabase(databaseName, 10).use { database -> insertV10Fixture(database) }
        helper.runMigrationsAndValidate(
            databaseName,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { database ->
            assertEquals(RECORD_DATABASE_VERSION, database.version)
            assertTrue("resourceNotes" in accountColumns(database))
            database.query("SELECT COUNT(*) FROM game_accounts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("migration never deletes accounts", 2, cursor.getInt(0))
            }
            database.query(
                "SELECT loginAccount FROM game_accounts WHERE id = 'account-2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v10-login-2", cursor.getString(0))
            }
        }
    }

    private fun accountColumns(database: SupportSQLiteDatabase): List<String> =
        database.query("PRAGMA table_info(`game_accounts`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun snapshot(
        database: SupportSQLiteDatabase,
        columns: List<String>,
    ): Map<String, List<List<String?>>> {
        val projection = columns.joinToString(", ") { "`$it`" }
        return database.query("SELECT $projection FROM `game_accounts` ORDER BY `id`").use { cursor ->
            mapOf(
                "game_accounts" to buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        })
                    }
                },
            )
        }
    }

    /** v10 fixture: two accounts (one archived) plus the rows that reference them. */
    private fun insertV10Fixture(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO owners (id, number, nickname, createdAt, updatedAt, archivedAt, archiveBatchId) " +
                "VALUES ('owner-1', 1, 'v10老板', 1700000000000, 1700000000000, NULL, NULL)",
        )
        database.execSQL("INSERT INTO owner_number_sequence (id, nextNumber) VALUES (0, 2)")
        database.execSQL(
            "INSERT INTO game_accounts (id, ownerId, gameName, server, uid, loginAccount, " +
                "loginMethod, password, createdAt, updatedAt, archivedAt, archiveBatchId, " +
                "defaultTemplateId, loginDevice) VALUES " +
                "('account-1', 'owner-1', '重返未来：1999', '官服', '10000628', 'v10-login-1', " +
                "'PASSWORD', 'secret-1', 1700000000000, 1700000000000, NULL, NULL, NULL, '手机')",
        )
        database.execSQL(
            "INSERT INTO game_accounts (id, ownerId, gameName, server, uid, loginAccount, " +
                "loginMethod, password, createdAt, updatedAt, archivedAt, archiveBatchId, " +
                "defaultTemplateId, loginDevice) VALUES " +
                "('account-2', 'owner-1', '明日方舟', '官服', '20000002', 'v10-login-2', " +
                "'VERIFICATION_CODE', NULL, 1700000000000, 1700000000000, 1700000005000, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO orders (id, accountId, type, title, priceCents, paymentMode, deadlineDate, " +
                "notes, gameZoneId, dailyRefreshMinutes, weeklyRefreshDay, weeklyRefreshMinutes, " +
                "createdAt, updatedAt, completedAt, sourceTemplateId, sourceTemplateName, " +
                "sourceTemplateVersion, cancelledAt) VALUES " +
                "('order-short', 'account-1', 'SHORT_TASK', 'v10短期单', 12345, 'ONE_TIME', " +
                "'2026-10-30', NULL, 'Asia/Shanghai', 240, 1, 300, 1700000000000, 1700000000000, " +
                "NULL, NULL, NULL, NULL, NULL)",
        )
        database.execSQL(
            "INSERT INTO receipts (id, operationId, orderId, periodId, amountCents, receivedAt, " +
                "channel, note, status, replacedByReceiptId, createdAt) VALUES " +
                "('receipt-1', 'op-receipt-1', 'order-short', NULL, 5000, 1700000100000, " +
                "'WECHAT', '定金', 'ACTIVE', NULL, 1700000100000)",
        )
        database.execSQL(
            "INSERT INTO reminder_settings VALUES (0, 1, 30, 0, 0, 0, 0, 1700000300000)",
        )
        database.execSQL("INSERT INTO app_settings VALUES (0, 'dataset-v10', NULL, 1700000300000)")
    }
}
