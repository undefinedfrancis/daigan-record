package com.daigan.record.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
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
 * Schema 12 → 13 (v1.8.4 Stage 1). Schema 12 has shipped, so the migration may only add
 * nullable surface: no existing order, template, period, task, receipt or screenshot may
 * be rewritten, and the new round table starts empty.
 */
@RunWith(AndroidJUnit4::class)
class MigrationV12ToV13Test {
    private val name = "count-hosting-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanup() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    @Test
    fun schemaTwelveDataSurvivesAsDateBasedAndRoundsStartEmpty() {
        helper.createDatabase(name, 12).use { db ->
            db.execSQL(
                "INSERT INTO owners(id,number,nickname,createdAt,updatedAt) " +
                    "VALUES ('o',1,'老板',1,1)",
            )
            db.execSQL(
                "INSERT INTO game_accounts(id,ownerId,gameName,server,uid,loginAccount," +
                    "loginMethod,createdAt,updatedAt) " +
                    "VALUES ('a','o','游戏','服','u','login','VERIFICATION_CODE',1,1)",
            )
            db.execSQL(
                "INSERT INTO orders(id,accountId,type,title,priceCents,paymentMode," +
                    "gameZoneId,dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes," +
                    "createdAt,updatedAt) VALUES " +
                    "('x','a','HOSTING','旧托管',NULL,'ONE_TIME','Asia/Shanghai',240,1,300,1,1)",
            )
            db.execSQL(
                "INSERT INTO hosting_periods(id,orderId,periodIndex,startDate,endDate," +
                    "priceCents,gameZoneId,dailyRefreshMinutes,weeklyRefreshDay," +
                    "weeklyRefreshMinutes,createdAt) VALUES " +
                    "('p','x',0,'2026-10-01','2026-10-30',100,'Asia/Shanghai',240,1,300,1)",
            )
            db.execSQL(
                "INSERT INTO game_templates(id,gameName,templateVersion,zoneId," +
                    "dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes,origin," +
                    "sortOrder,createdAt,updatedAt) VALUES " +
                    "('t','旧游戏','1.0','Asia/Shanghai',240,1,300,'USER',0,1,1)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { db ->
            assertEquals(RECORD_DATABASE_VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

            // Existing rows keep their identity; the new mode columns are NULL.
            db.query("SELECT title,priceCents,hostingMode,targetSessions FROM orders WHERE id='x'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("旧托管", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                    assertTrue(cursor.isNull(3))
                }
            db.query("SELECT gameName,hostingMode,targetSessions FROM game_templates WHERE id='t'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("旧游戏", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                }
            db.query("SELECT * FROM hosting_sessions").use { cursor ->
                assertFalse("a migrated database must not invent rounds", cursor.moveToFirst())
            }

            // The new table accepts one round per index and keeps the order relation.
            db.execSQL(
                "INSERT INTO hosting_sessions(id,orderId,sessionIndex,createdAt,completedAt) " +
                    "VALUES ('s1','x',1,10,NULL)",
            )
            db.query("SELECT sessionIndex,completedAt FROM hosting_sessions WHERE id='s1'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                    assertTrue(cursor.isNull(1))
                }
            db.execSQL(
                "UPDATE orders SET hostingMode='COUNT_BASED', targetSessions=30 WHERE id='x'",
            )
            db.query("SELECT hostingMode,targetSessions FROM orders WHERE id='x'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COUNT_BASED", cursor.getString(0))
                assertEquals(30, cursor.getInt(1))
            }
        }
    }
}
