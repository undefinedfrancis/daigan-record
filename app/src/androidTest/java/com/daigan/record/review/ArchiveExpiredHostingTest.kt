package com.daigan.record.review

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.backup.BackupDataCodec
import com.daigan.record.backup.BackupDataSet
import com.daigan.record.backup.BackupDatabaseAccess
import com.daigan.record.core.time.StatisticsRange
import com.daigan.record.data.model.*
import com.daigan.record.notifications.ReminderEvaluator
import com.daigan.record.notifications.ReminderRepository
import com.daigan.record.testing.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveExpiredHostingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private val day = LocalDate.of(2026, 10, 17)
    @Before fun setup() { env = OrderTestEnvironment(context) }
    @After fun close() { env.close() }

    @Test fun archivePreservesDebtIncomeHistoryAndOtherOrderAndAllowsCollecting() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)).copy(paymentMode = PaymentMode.INSTALLMENTS))
        val other = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        val period = env.database.orderDao().findPeriods(id).single()
        val task = env.database.taskDao().findForOrder(id).single()
        env.orderRepository.setHistoricalCompletion(id, period.id, task.id, day.minusDays(1), true)
        val occurrence = env.database.occurrenceDao().findForOrder(id).single()
        env.attachmentRepository.addAttachment(null, occurrence.id, Uri.fromFile(writeTestJpeg(context, "archive-history.jpg")))
        env.receiptRepository.recordPayment(PaymentRequest(id, period.id, 10000, env.clock.now(), PaymentChannel.WECHAT, null, "before-archive"))
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        val statsBefore = env.statisticsRepository.loadStatistics(StatisticsRange.ALL, ZoneId.of("Asia/Shanghai"), env.clock.now())
        env.orderRepository.archiveExpiredHostingOrder(id)
        val archived = env.orderRepository.observeOrderDetail(id).first()!!
        assertTrue(archived.order.archived)
        assertFalse(archived.order.cancelled)
        assertEquals(before.periods, archived.periods)
        assertEquals(before.occurrences, archived.occurrences)
        assertEquals(before.attachments, archived.attachments)
        assertEquals(before.balance(), archived.balance())
        assertEquals(setOf(other), env.orderRepository.observeTodayHosting().first().map { it.order.id }.toSet())
        assertFalse(env.accountRepository.findAccount(account.id)!!.archived)
        assertEquals(2, env.orderRepository.observeOrdersForOwner(owner.id).first().size)
        val statsAfter = env.statisticsRepository.loadStatistics(StatisticsRange.ALL, ZoneId.of("Asia/Shanghai"), env.clock.now())
        assertEquals(statsBefore.receivedCents, statsAfter.receivedCents)
        assertEquals(statsBefore.outstandingCents, statsAfter.outstandingCents)
        env.receiptRepository.recordPayment(PaymentRequest(id, period.id, 5000, env.clock.now(), PaymentChannel.ALIPAY, null, "after-archive"))
        val paid = env.orderRepository.observeOrderDetail(id).first()!!
        assertEquals(15000L, paid.balance().totalPaidCents)
        assertEquals(15000L, paid.balance().totalOutstandingCents)
        assertEquals(1, paid.occurrences.size)
        env.receiptRepository.recordPayment(PaymentRequest(id, period.id, 15000, env.clock.now(), PaymentChannel.WECHAT, null, "settled-after-archive"))
        val settled = env.orderRepository.observeOrderDetail(id).first()!!
        assertTrue(settled.order.archived)
        assertEquals(0L, settled.balance().totalOutstandingCents)
        assertEquals(30000L, settled.balance().totalPaidCents)
    }

    @Test fun finalDayAndFutureRenewalRejectArchiveUsingPeriodRefreshSnapshot() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day))
        // Order rules can differ from the historical period; only the period clock decides.
        env.database.orderDao().updateRefreshRules(id, "Asia/Shanghai", 0, 1, 300, null, env.clock.now())
        env.clock.set(Instant.parse("2026-10-17T19:59:59Z"))
        assertTrue(runCatching { env.orderRepository.archiveExpiredHostingOrder(id) }.isFailure)
        assertNull(env.database.orderDao().findById(id)!!.archivedAt)
        env.clock.set(Instant.parse("2026-10-17T20:00:00Z"))
        env.orderRepository.archiveExpiredHostingOrder(id)
        assertNotNull(env.database.orderDao().findById(id)!!.archivedAt)
        val future = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(2)))
        env.orderRepository.renewHosting(future, "future-renewal", 5, 20000)
        assertTrue(runCatching { env.orderRepository.archiveExpiredHostingOrder(future) }.isFailure)
        assertNull(env.database.orderDao().findById(future)!!.archivedAt)
    }

    @Test fun archivedWorkRequiresRestoreAndRestoreDoesNotRenewOrAlterMoney() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        env.orderRepository.archiveExpiredHostingOrder(id)
        val timestamp = env.database.orderDao().findById(id)!!.archivedAt
        env.clock.advanceSeconds(5)
        env.orderRepository.archiveExpiredHostingOrder(id)
        assertEquals(timestamp, env.database.orderDao().findById(id)!!.archivedAt)
        val before = env.orderRepository.observeOrderDetail(id).first()!!
        assertTrue(runCatching { env.orderRepository.renewHosting(id, "blocked-renewal", 5, 10000) }.isFailure)
        assertTrue(runCatching { env.orderRepository.setHistoricalCompletion(id, before.periods.single().id, before.tasks.single().id, day.minusDays(1), true) }.isFailure)
        env.orderRepository.restoreArchivedOrder(id)
        val restored = env.orderRepository.observeOrderDetail(id).first()!!
        assertFalse(restored.order.archived)
        assertEquals(before.periods, restored.periods)
        assertEquals(before.balance(), restored.balance())
        env.orderRepository.renewHosting(id, "after-restore", 5, 10000)
        assertEquals(2, env.database.orderDao().findPeriods(id).size)
    }

    @Test fun archivedOrderIsExcludedFromRemindersEvenWhenClockMovesBack() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        env.orderRepository.archiveExpiredHostingOrder(id)
        env.clock.set(Instant.parse("2026-10-16T02:00:00Z"))
        val repository = ReminderRepository(env.database, env.database.orderDao(), env.database.gameAccountDao(), env.database.ownerDao())
        val snapshot = repository.loadSnapshot(env.clock.now())
        assertTrue(snapshot.orders.single().archived)
        assertTrue(ReminderEvaluator.messages(snapshot, env.clock.now()).isEmpty())
        assertTrue(env.orderRepository.observeTodayHosting().first().isEmpty())
    }

    /** The archive flag survives an export/restore round trip in the current schema. */
    @Test fun archiveFlagRoundTrips() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        env.orderRepository.archiveExpiredHostingOrder(id)
        val access = BackupDatabaseAccess(context, env.database, env.clock)
        val dump = access.dump()
        val timestamp = env.database.orderDao().findById(id)!!.archivedAt
        assertNotNull("archiving must set the flag", timestamp)
        access.replaceAll(BackupDataCodec.decode(BackupDataCodec.encode(dump)), "archive-roundtrip")
        assertEquals(timestamp, env.database.orderDao().findById(id)!!.archivedAt)
    }

    /**
     * A genuine schema-14 file predates `orders.archivedAt`: the manifest declares 14 and
     * the column is simply absent. That generation is restorable and defaults to
     * unarchived.
     */
    @Test fun schema14BackupWithoutArchivedAtDefaultsToUnarchived() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        env.orderRepository.archiveExpiredHostingOrder(id)
        val access = BackupDatabaseAccess(context, env.database, env.clock)
        val json = BackupDataCodec.encode(withoutArchivedAt(access.dump()))
        val declared = "\"schemaVersion\":15"
        assertEquals(
            "the encoder must declare the portable schema exactly once",
            1,
            Regex(Regex.escape(declared)).findAll(json).count(),
        )
        val legacyJson = json.replace(declared, "\"schemaVersion\":14")
        access.replaceAll(BackupDataCodec.decode(legacyJson, schemaVersion = 14), "legacy-archive-default")
        assertNull(env.database.orderDao().findById(id)!!.archivedAt)
        assertEquals(1, env.database.orderDao().findPeriods(id).size)
    }

    /**
     * Strictness is not weakened for the current schema: a schema-15 file that drops a
     * required column is still a malformed backup.
     */
    @Test fun currentSchemaBackupMissingArchivedAtIsStillRejected() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        val access = BackupDatabaseAccess(context, env.database, env.clock)
        val broken = BackupDataCodec.encode(withoutArchivedAt(access.dump()))
        val failure = runCatching { BackupDataCodec.decode(broken) }.exceptionOrNull()
        assertNotNull("a schema-15 file may not omit a required column", failure)
        // The malformed file is never applied: the live order is untouched.
        assertNull(env.database.orderDao().findById(id)!!.archivedAt)
    }

    /** Drops `orders.archivedAt` from both the column list and every row of the dump. */
    private fun withoutArchivedAt(dump: BackupDataSet): BackupDataSet = BackupDataSet(
        dump.tables.map { table ->
            if (table.table.name != "orders") table else {
                val index = table.table.columnNames.indexOf("archivedAt")
                BackupDataSet.TableRows(
                    table.table.copy(columns = table.table.columns.filterIndexed { i, _ -> i != index }),
                    table.rows.map { row -> row.filterIndexed { i, _ -> i != index } },
                )
            }
        },
    )

    @Test fun cancelledAndShortOrdersCannotUseNormalExpiryArchive() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val short = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        assertTrue(runCatching { env.orderRepository.archiveExpiredHostingOrder(short) }.isFailure)
        val hosting = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.minusDays(3), day.minusDays(1)))
        env.orderRepository.cancelOrder(hosting)
        assertTrue(runCatching { env.orderRepository.archiveExpiredHostingOrder(hosting) }.isFailure)
    }
}
