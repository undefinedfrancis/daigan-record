package com.daigan.record.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema 14 → 15 (Stage 2)：轮次归属回填、累计事件表、账号分类与数值口径列。
 *
 * 这里用导出的 schema 14 资产 + [MigrationTestHelper] 跑**真实的 SQLite 迁移**，而不是
 * 比较两份 JSON。覆盖：轮次表重建（periodId 回填）、目标次数回填、索引、外键完整性、
 * 以及歧义输入的整库回滚。
 */
@RunWith(AndroidJUnit4::class)
class MigrationV14ToV15Test {
    private val name = "stage2-migration-test"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    private fun SupportSQLiteDatabase.seedBase() {
        execSQL(
            "INSERT INTO owners(id,number,nickname,createdAt,updatedAt) VALUES ('o',1,'老板',1,1)",
        )
        execSQL(
            "INSERT INTO game_accounts(id,ownerId,gameName,server,uid,loginAccount," +
                "loginMethod,createdAt,updatedAt) VALUES " +
                "('a','o','游戏','服','u','login','VERIFICATION_CODE',1,1)",
        )
    }

    private fun SupportSQLiteDatabase.seedCountOrder(orderId: String, targetSessions: Int) {
        execSQL(
            "INSERT INTO orders(id,accountId,type,title,paymentMode,gameZoneId," +
                "dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes,createdAt,updatedAt," +
                "hostingMode,targetSessions) VALUES " +
                "('$orderId','a','HOSTING','按次托管','INSTALLMENTS','Asia/Shanghai',300,1,300,1,1," +
                "'COUNT_BASED',$targetSessions)",
        )
    }

    private fun SupportSQLiteDatabase.seedPeriod(
        id: String,
        orderId: String,
        periodIndex: Int,
        startDate: String = "2026-02-01",
    ) {
        execSQL(
            "INSERT INTO hosting_periods(id,orderId,periodIndex,startDate,endDate,priceCents," +
                "gameZoneId,dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes,createdAt) " +
                "VALUES ('$id','$orderId',$periodIndex,'$startDate','$startDate',30000," +
                "'Asia/Shanghai',300,1,300,1)",
        )
    }

    private fun SupportSQLiteDatabase.seedTask(id: String, orderId: String, periodId: String?) {
        execSQL(
            "INSERT INTO task_definitions(id,orderId,periodId,title,progressType,startValue," +
                "targetValue,currentValue,recurrence,sortOrder,createdAt,updatedAt) VALUES " +
                "('$id','$orderId',${periodId?.let { "'$it'" } ?: "NULL"},'任务','NUMERIC','0','20'," +
                "'0','DAILY',0,1,1)",
        )
    }

    @Test
    fun countRoundsGetTheirOwningPeriodAndTargetsAreBackfilled() {
        helper.createDatabase(name, 14).use { db ->
            db.seedBase()
            db.seedCountOrder("c1", 4)
            db.seedPeriod("c1p1", "c1", 0)
            db.seedTask("t1", "c1", "c1p1")
            db.execSQL(
                "INSERT INTO hosting_sessions(id,orderId,sessionIndex,createdAt,completedAt) " +
                    "VALUES ('s1','c1',1,10,20),('s2','c1',2,11,NULL)",
            )
            // 按日期订单：不得被回填目标次数。
            db.execSQL(
                "INSERT INTO orders(id,accountId,type,title,paymentMode,gameZoneId," +
                    "dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes,createdAt,updatedAt," +
                    "hostingMode) VALUES " +
                    "('d1','a','HOSTING','按日期','INSTALLMENTS','Asia/Shanghai',300,1,300,1,1," +
                    "'DATE_RANGE')",
            )
            db.seedPeriod("d1p1", "d1", 0, "2026-03-01")
            db.seedTask("t2", "d1", "d1p1")
        }

        helper.runMigrationsAndValidate(
            name,
            RECORD_DATABASE_VERSION,
            true,
            *RecordDatabase.ALL_MIGRATIONS,
        ).use { db ->
            assertEquals(RECORD_DATABASE_VERSION, db.version)
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

            // 轮次表被重建：归属期次被回填，业务值（序号/时间/完成态）原样保留。
            db.query(
                "SELECT id,periodId,sessionIndex,createdAt,completedAt FROM hosting_sessions " +
                    "ORDER BY sessionIndex",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("s1", cursor.getString(0))
                assertEquals("c1p1", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(10L, cursor.getLong(3))
                assertEquals(20L, cursor.getLong(4))
                assertTrue(cursor.moveToNext())
                assertEquals("s2", cursor.getString(0))
                assertEquals("c1p1", cursor.getString(1))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("completedAt")))
            }

            // 目标次数只回填计次订单。
            db.query("SELECT serviceCount FROM hosting_periods WHERE id='c1p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
            db.query("SELECT serviceCount FROM hosting_periods WHERE id='d1p1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }

            // 索引齐全（含唯一索引），且 periodId 索引存在。
            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(`hosting_sessions`)").use { cursor ->
                while (cursor.moveToNext()) indexes += cursor.getString(1)
            }
            assertTrue(indexes.contains("index_hosting_sessions_orderId"))
            assertTrue(indexes.contains("index_hosting_sessions_orderId_sessionIndex"))
            assertTrue(indexes.contains("index_hosting_sessions_periodId"))

            // 新表存在且为空：迁移绝不臆造事件或分类。
            db.query("SELECT COUNT(*) FROM task_progress_events").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM account_categories").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // periodId 现在是 NOT NULL：不能插入无归属的轮次。
            try {
                db.execSQL(
                    "INSERT INTO hosting_sessions(id,orderId,sessionIndex,createdAt) " +
                        "VALUES ('bad','c1',9,1)",
                )
                fail("periodId NOT NULL 约束缺失")
            } catch (expected: Exception) {
                assertNotNull(expected)
            }
        }
    }

    @Test
    fun ambiguousRoundOwnershipAbortsAndRollsBack() {
        helper.createDatabase(name, 14).use { db ->
            db.seedBase()
            db.seedCountOrder("c1", 2)
            db.seedPeriod("c1p1", "c1", 0)
            db.seedPeriod("c1p2", "c1", 1, "2026-03-01")
            db.execSQL(
                "INSERT INTO hosting_sessions(id,orderId,sessionIndex,createdAt,completedAt) " +
                    "VALUES ('s1','c1',1,10,20)",
            )
        }

        try {
            helper.runMigrationsAndValidate(
                name,
                RECORD_DATABASE_VERSION,
                true,
                *RecordDatabase.ALL_MIGRATIONS,
            ).close()
            fail("歧义的轮次归属必须中止迁移")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("打卡轮次归属无法证明"))
        }

        // 迁移失败后数据库仍是 14，原始轮次与期次一个都没少。
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper14 ->
            val db = helper14.writableDatabase
            db.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(14, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM hosting_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM hosting_periods").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            // 迁移的前置检查在改 schema 之前抛错，因此新列/新表都不该存在。
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`hosting_sessions`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertFalse(columns.contains("periodId"))
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertFalse(tables.contains("task_progress_events"))
        }
    }
}
