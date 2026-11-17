package com.daigan.record.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationV11ToV12Test {
    private val name="scheduling-migration-test"
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),RecordDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @After fun cleanup() { ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name) }
    @Test fun allHistoricalSchemasMigrateToCurrentWithoutDestructiveFallback() {
        for (version in 1 until RECORD_DATABASE_VERSION) {
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
            helper.createDatabase(name,version).close()
            helper.runMigrationsAndValidate(name,RECORD_DATABASE_VERSION,true,*RecordDatabase.ALL_MIGRATIONS).use { db ->
                assertEquals(RECORD_DATABASE_VERSION,db.version)
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        }
    }
    @Test fun legacyMonthlyDatesBecomeOneElementSets() {
        helper.createDatabase(name,11).use { db ->
            db.execSQL("INSERT INTO owners(id,number,nickname,createdAt,updatedAt) VALUES ('o',1,'老板',1,1)")
            db.execSQL("INSERT INTO game_accounts(id,ownerId,gameName,server,uid,loginAccount,loginMethod,createdAt,updatedAt,resourceNotes) VALUES ('a','o','游戏','服','u','login','VERIFICATION_CODE',1,1,'60抽')")
            db.execSQL("INSERT INTO orders(id,accountId,type,title,priceCents,paymentMode,gameZoneId,dailyRefreshMinutes,weeklyRefreshDay,weeklyRefreshMinutes,createdAt,updatedAt) VALUES ('x','a','SHORT_TASK','任务',100,'ONE_TIME','Asia/Shanghai',240,1,300,1,1)")
            db.execSQL("INSERT INTO task_definitions(id,orderId,title,progressType,startValue,targetValue,currentValue,recurrence,monthDay,sortOrder,createdAt,updatedAt) VALUES ('t','x','月任务','CHECKBOX','0','1','0','MONTHLY',15,0,1,1)")
        }
        helper.runMigrationsAndValidate(name,RECORD_DATABASE_VERSION,true,*RecordDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT monthDays,retiredAt,activeFrom FROM task_definitions WHERE id='t'").use { c ->
                assertTrue(c.moveToFirst());assertEquals("15",c.getString(0));assertTrue(c.isNull(1));assertTrue(c.isNull(2))
            }
            db.query("SELECT resourceNotes FROM game_accounts WHERE id='a'").use { c ->assertTrue(c.moveToFirst());assertEquals("60抽",c.getString(0)) }
            db.query("SELECT archivedAt FROM orders WHERE id='x'").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        }
    }
}
