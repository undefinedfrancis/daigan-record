package com.daigan.record.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.local.RecordDatabase
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Explicit-only probe for the original APK -> candidate package upgrade scenario. */
@RunWith(AndroidJUnit4::class)
class UpgradeCandidateProbeTest {
    @Test fun originalPackageFixtureSurvivesActualCandidateInstall() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context,RecordDatabase::class.java,RecordDatabase.NAME)
            .addMigrations(*RecordDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(12,db.openHelper.readableDatabase.version)
            assertEquals("Upgrade preservation",db.ownerDao().findById("upgrade-owner")!!.nickname)
            assertEquals("upgrade-uid",db.gameAccountDao().findById("upgrade-account")!!.uid)
            assertEquals("upgrade-account",db.orderDao().findById("upgrade-order")!!.accountId)
            assertNull(db.orderDao().findById("upgrade-order")!!.archivedAt)
            assertEquals(10000L,db.orderDao().findPeriod("upgrade-period")!!.priceCents)
            assertEquals("15",db.taskDao().findById("upgrade-task")!!.monthDays)
            val receipts = db.receiptDao().findForOrder("upgrade-order")
            assertEquals(2500L,receipts.single().amountCents)
            assertEquals("upgrade-receipt",receipts.single().id)
        } finally { db.close() }
    }
}
