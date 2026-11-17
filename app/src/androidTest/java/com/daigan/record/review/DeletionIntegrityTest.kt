package com.daigan.record.review

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.repository.AttachmentFileCleanup
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.writeTestImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-007 / UF-009: destructive operations must be exact, transactional and isolated.
 *
 * Three independent promises are checked here:
 * 1. a permanent deletion removes every dependent row of *that* order/account only;
 * 2. a failure inside the transaction restores the complete previous state;
 * 3. screenshot files are removed only after the rows committed, and a failed file pass
 *    leaves a state the start-up orphan sweep can finish.
 */
@RunWith(AndroidJUnit4::class)
class DeletionIntegrityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun seedHostingOrder(
        ownerId: String,
        accountId: String,
        title: String,
        receiptCents: Long? = null,
    ): String {
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId,
                accountId,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                title = title,
                tasks = listOf(TaskDraft(title = "签到")),
            ),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        env.orderRepository.setOccurrenceCompleted(detail.currentHostingTasks().single().task.id, true)
        val occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context, "$orderId.jpg")),
        )
        if (receiptCents != null) {
            env.receiptRepository.recordPayment(
                PaymentRequest(
                    orderId = orderId,
                    periodId = detail.currentPeriod!!.id,
                    amountCents = receiptCents,
                    receivedAt = env.clock.now(),
                    channel = PaymentChannel.WECHAT,
                    note = "收款",
                    operationId = "receipt-$orderId",
                ),
            )
        }
        return orderId
    }

    @Test
    fun accountDeletionLeavesOtherAccountsAndTheirFilesUntouched() = runBlocking {
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val (owner, accountA) = env.seedOwnerAndAccount(uid = "10000001")
        // A second, unrelated owner/account pair that must survive the deletion below.
        val (otherOwner, accountB) = env.seedOwnerAndAccount(
            nickname = "第二位老板",
            gameName = "明日方舟",
            uid = "20000002",
        )
        val orderA = seedHostingOrder(owner.id, accountA.id, "待删除托管", receiptCents = 30000)
        val orderB = seedHostingOrder(otherOwner.id, accountB.id, "保留托管", receiptCents = 30000)

        val impact = env.accountRepository.deletionImpact(accountA.id)
        assertEquals(1, impact.orderCount)
        assertEquals(1, impact.activeOrderCount)
        assertEquals(1, impact.periodCount)
        assertEquals(1, impact.occurrenceCount)
        assertEquals(1, impact.receiptCount)
        assertEquals(30000L, impact.receiptSumCents)
        assertEquals(1, impact.attachmentCount)
        assertEquals("价 30000, 收 30000 -> nothing outstanding", 0L, impact.outstandingCents)

        val fileA = env.attachmentRepository.fileFor(
            env.orderRepository.observeOrderDetail(orderA).first()!!.attachments.single(),
        )
        val fileB = env.attachmentRepository.fileFor(
            env.orderRepository.observeOrderDetail(orderB).first()!!.attachments.single(),
        )
        assertTrue(fileA.exists())
        assertTrue(fileB.exists())

        val summary = env.accountRepository.deleteAccount(accountA.id)
        assertEquals(1, summary.deletedOrderCount)
        assertEquals("occurrence + receipt + attachment", 3, summary.deletedDependentRowCount)
        assertFalse("the committed deletion still cleaned up its files", summary.attachmentCleanupDeferred)
        assertFalse(fileA.exists())
        assertTrue("an unrelated account's screenshot must survive", fileB.exists())

        assertNull(env.accountRepository.findAccount(accountA.id))
        assertNull(env.orderRepository.observeOrderDetail(orderA).first())
        val surviving = env.orderRepository.observeOrderDetail(orderB).first()!!
        assertEquals(orderB, surviving.order.id)
        assertEquals(1, surviving.occurrences.size)
        assertEquals(1, surviving.receipts.size)
        assertEquals(1, surviving.attachments.size)
        assertEquals(1, env.orderRepository.observeOrdersForOwner(otherOwner.id).first().size)
        assertEquals(
            "the deleted account's owner keeps its other (nonexistent) accounts only",
            0,
            env.orderRepository.observeOrdersForOwner(owner.id).first().size,
        )
        // No dangling foreign keys anywhere.
        env.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse(it.moveToFirst())
        }
    }

    @Test
    fun aFailedFilePassIsReportedAndRetriedByTheOrphanSweep() = runBlocking {
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = seedHostingOrder(owner.id, account.id, "待删除托管")
        val file = env.attachmentRepository.fileFor(
            env.orderRepository.observeOrderDetail(orderId).first()!!.attachments.single(),
        )
        assertTrue(file.exists())

        val failing = DefaultGameAccountRepository(
            database = env.database,
            ownerDao = env.database.ownerDao(),
            accountDao = env.database.gameAccountDao(),
            orderDao = env.database.orderDao(),
            taskDao = env.database.taskDao(),
            occurrenceDao = env.database.occurrenceDao(),
            receiptDao = env.database.receiptDao(),
            attachmentDao = env.database.attachmentDao(),
            completionBatchDao = env.database.completionBatchDao(),
            categoryDao = env.database.accountCategoryDao(),
            timeProvider = env.clock,
            attachmentCleanup = AttachmentFileCleanup { throw java.io.IOException("磁盘忙") },
        )
        val summary = failing.deleteAccount(account.id)
        assertTrue("rows committed, files deferred", summary.attachmentCleanupDeferred)
        assertNull(env.accountRepository.findAccount(account.id))
        assertNull(env.orderRepository.observeOrderDetail(orderId).first())
        assertTrue("the file is still there until the sweep runs", file.exists())

        // Retry path: the start-up sweep is idempotent and finishes the job.
        assertTrue(env.attachmentRepository.cleanupOrphans() >= 1)
        assertFalse(file.exists())
        assertEquals(0, env.attachmentRepository.cleanupOrphans())
    }

    @Test
    fun cancelledOrderDeletionRollsBackEveryRowWhenTheCommitFails() = runBlocking {
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = seedHostingOrder(owner.id, account.id, "作废后删除", receiptCents = 30000)
        env.orderRepository.cancelOrder(orderId)
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!
        val raw = env.database.openHelper.writableDatabase
        val file = env.attachmentRepository.fileFor(before.attachments.single())

        raw.execSQL(
            "CREATE TRIGGER fail_order_delete BEFORE DELETE ON orders " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
        )
        val failure = runCatching { env.orderRepository.deleteCancelledOrder(orderId) }
            .exceptionOrNull()
        assertNotNull("the injected failure must surface", failure)
        raw.execSQL("DROP TRIGGER fail_order_delete")

        // Nothing was half-deleted: same order, periods, tasks, occurrences, receipts,
        // attachments and the same file on disk.
        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(before.order.id, after.order.id)
        assertEquals(before.periods.map { it.id }, after.periods.map { it.id })
        assertEquals(before.tasks.map { it.id }, after.tasks.map { it.id })
        assertEquals(before.occurrences.map { it.id }, after.occurrences.map { it.id })
        assertEquals(before.receipts.map { it.id }, after.receipts.map { it.id })
        assertEquals(before.attachments.map { it.id }, after.attachments.map { it.id })
        assertTrue(file.exists())

        val summary = env.orderRepository.deleteCancelledOrder(orderId)
        assertEquals(1, summary.occurrenceCount)
        assertEquals(1, summary.receiptCount)
        assertEquals(30000L, summary.receiptSumCents)
        assertEquals(1, summary.attachmentCount)
        assertNull(env.orderRepository.observeOrderDetail(orderId).first())
        assertTrue("row deletion never touches files", file.exists())
        // The screen cleans up afterwards; the sweep is the durable retry.
        env.attachmentRepository.cleanupOrphans()
        assertFalse(file.exists())
        assertNotNull("the account itself survives an order deletion", env.accountRepository.findAccount(account.id))
    }
}
