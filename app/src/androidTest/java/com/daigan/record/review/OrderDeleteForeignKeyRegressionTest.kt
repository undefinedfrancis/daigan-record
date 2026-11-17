package com.daigan.record.review

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.hostingSnapshot
import com.daigan.record.testing.hostingSnapshots
import com.daigan.record.testing.shortDraft
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
 * UF-040: 作废 → 删除 must succeed for every combination of dependent records.
 *
 * This is a diagnosis-first regression suite: each test drives the **real** delete
 * transaction (`OrderRepository.deleteCancelledOrder` -> `deleteOrderCascade`) against a
 * real Room database, so a foreign-key or ordering defect fails here exactly as it fails
 * on a device. A failure is logged with the full stack trace and the surviving
 * `PRAGMA foreign_key_check` rows so the offending statement and constraint are visible.
 *
 * The scenarios are the ones a cancelled order can own in practice:
 * no history, one check-in, whole-order bulk completion, "complete all hosting" batch,
 * short-order bulk completion, renewals, receipts with a correction, attachments on both
 * a task and an occurrence, and count-based rounds. The last test proves a failed delete
 * rolls everything back.
 */
@RunWith(AndroidJUnit4::class)
class OrderDeleteForeignKeyRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
    }

    @After
    fun tearDown() {
        env.close()
    }

    // ------------------------------------------------------------------ fixtures

    private suspend fun detail(orderId: String): OrderDetail =
        requireNotNull(env.orderRepository.observeOrderDetail(orderId).first()) {
            "订单详情缺失：$orderId"
        }

    private suspend fun seedHosting(
        title: String,
        start: LocalDate = LocalDate.of(2026, 10, 1),
        end: LocalDate = LocalDate.of(2026, 10, 31),
        tasks: List<TaskDraft> = listOf(TaskDraft(title = "签到")),
        seed: Pair<String, String>? = null,
    ): String {
        val (ownerId, accountId) = seed
            ?: env.seedOwnerAndAccount().let { it.first.id to it.second.id }
        return env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = ownerId,
                accountId = accountId,
                start = start,
                end = end,
                title = title,
                tasks = tasks,
            ),
        )
    }

    private suspend fun cancel(orderId: String) {
        env.orderRepository.cancelOrder(orderId)
        assertNotNull("作废失败", detail(orderId).order.cancelledAt)
    }

    /** Runs the real delete and dumps full evidence when it fails. */
    private suspend fun delete(orderId: String, scenario: String) {
        Log.i("UF040", "场景=$scenario 删除前批次引用=${batchReferenceReport()}")
        try {
            env.orderRepository.deleteCancelledOrder(orderId)
        } catch (error: Exception) {
            Log.e("UF040", "删除失败 order=$orderId 场景=$scenario", error)
            Log.e("UF040", "foreign_key_check=${foreignKeyCheckRows()}")
            Log.e("UF040", "counts=${rowCounts()}")
            throw AssertionError(
                "作废订单删除失败（场景：$scenario）：${error.javaClass.name}: ${error.message}\n" +
                    "外键残留：${foreignKeyCheckRows()}\n" +
                    "行数：${rowCounts()}",
                error,
            )
        }
    }

    /** `PRAGMA foreign_key_check` rows: table|rowid|parent|fkid. Empty means consistent. */
    private fun foreignKeyCheckRows(): List<String> = runCatching {
        env.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getInt(3).toString(),
                        ).joinToString("|"),
                    )
                }
            }
        }
    }.getOrElse { listOf("query-failed:${it.message}") }

    /**
     * Every batch that still has children pointing at it: `id|scope|targetId|
     * tasks|occurrences|items`. `task_definitions.completionBatchId` and
     * `task_occurrences.completionBatchId` are the two `ON DELETE RESTRICT` edges that
     * block `DELETE FROM completion_batches`, so they are counted separately.
     */
    private fun batchReferenceReport(): List<String> = runCatching {
        env.database.openHelper.writableDatabase.query(
            """
            SELECT b.id, b.scope, IFNULL(b.targetId, '-'),
                (SELECT COUNT(*) FROM task_definitions t WHERE t.completionBatchId = b.id),
                (SELECT COUNT(*) FROM task_occurrences o WHERE o.completionBatchId = b.id),
                (SELECT COUNT(*) FROM completion_batch_items i WHERE i.batchId = b.id)
            FROM completion_batches b
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        buildString {
                            append(cursor.getString(0)).append('|')
                            append(cursor.getString(1)).append('|')
                            append(cursor.getString(2)).append("|t=")
                            append(cursor.getInt(3)).append("|o=")
                            append(cursor.getInt(4)).append("|i=")
                            append(cursor.getInt(5))
                        },
                    )
                }
            }
        }
    }.getOrElse { listOf("query-failed:${it.message}") }

    /** Row counts of every table a cancelled-order delete touches. */
    private suspend fun rowCounts(): Map<String, Int> {
        val db = env.database
        return mapOf(
            "orders" to db.orderDao().findAllOrders().size,
            "periods" to db.orderDao().findAllPeriods().size,
            "tasks" to db.orderDao().findAllTasks().size,
            "occurrences" to db.orderDao().findAllOccurrences().size,
            "attachments" to db.orderDao().findAllAttachments().size,
            "batches" to db.completionBatchDao().count(),
            "batchItems" to db.completionBatchDao().findAllItems().size,
            "receipts" to db.receiptDao().findAll().size,
            "corrections" to db.receiptDao().findAllCorrections().size,
            "sessions" to db.hostingSessionDao().findAll().size,
        )
    }

    private suspend fun assertDeleted(orderId: String) {
        assertNull("订单行应已删除", env.orderRepository.observeOrderDetail(orderId).first())
        assertTrue("删除后不应存在悬空外键：${foreignKeyCheckRows()}", foreignKeyCheckRows().isEmpty())
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun cancelledOrderWithoutHistoryDeletes() = runBlocking {
        val orderId = seedHosting("无历史")
        cancel(orderId)
        delete(orderId, "无业务历史")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledOrderWithSingleCheckinDeletes() = runBlocking {
        val orderId = seedHosting("有打卡")
        val taskId = detail(orderId).currentHostingTasks().single().task.id
        env.orderRepository.setOccurrenceCompleted(taskId, true)
        cancel(orderId)
        assertEquals(1, detail(orderId).occurrences.size)
        delete(orderId, "单条打卡")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledHostingOrderAfterWholeOrderBulkCompletionDeletes() = runBlocking {
        val orderId = seedHosting("整单完成")
        val result = env.orderRepository.completeHostingOrder(
            orderId,
            "uf040-whole-order",
            env.hostingSnapshot(orderId),
        )
        assertEquals(1, result.completedCount)
        cancel(orderId)
        delete(orderId, "整单完成批量")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledHostingOrderAfterCompleteAllHostingDeletes() = runBlocking {
        val orderId = seedHosting("全部托管批量完成")
        val batch = env.orderRepository.completeAllHosting("uf040-all-hosting", env.hostingSnapshots())
        assertEquals(1, batch.completedCount)
        cancel(orderId)
        delete(orderId, "全部托管批量完成")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledShortOrderAfterWholeOrderCompletionDeletes() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(owner.id, account.id, title = "短期整单完成"),
        )
        val batch = env.orderRepository.completeShortOrder(orderId, "uf040-short")
        assertTrue("短期订单至少完成一项", batch.completedCount >= 1)
        cancel(orderId)
        delete(orderId, "短期订单整单完成")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledOrderWithRenewalPeriodsDeletes() = runBlocking {
        val orderId = seedHosting("续期两期")
        val firstTask = detail(orderId).currentHostingTasks().single().task.id
        env.orderRepository.setOccurrenceCompleted(firstTask, true)
        env.orderRepository.renewHosting(orderId, "uf040-renew", durationDays = 30, priceCents = 30000)
        val renewed = detail(orderId)
        assertEquals(2, renewed.periods.size)
        cancel(orderId)
        delete(orderId, "续期多期次")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledOrderWithReceiptAndCorrectionDeletes() = runBlocking {
        val orderId = seedHosting("收款与更正")
        val current = detail(orderId)
        val payment = env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = orderId,
                periodId = current.currentPeriod!!.id,
                amountCents = 30000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "定金",
                operationId = "uf040-receipt",
            ),
        )
        env.receiptRepository.correctReceipt(
            ReceiptCorrectionRequest.Amend(
                operationId = "uf040-amend",
                receiptId = payment.receipt.id,
                amountCents = 20000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "改为部分收款",
                reason = "客户少付",
            ),
        )
        assertEquals(2, detail(orderId).receipts.size)
        cancel(orderId)
        // The correction's newReceiptId also references a receipt of this order.
        assertTrue(env.database.receiptDao().findAllCorrections().isNotEmpty())
        delete(orderId, "收款与更正")
        assertDeleted(orderId)
        assertEquals(0, env.database.receiptDao().findAllCorrections().size)
    }

    @Test
    fun cancelledOrderWithTaskAndOccurrenceAttachmentsDeletes() = runBlocking {
        val orderId = seedHosting("截图在任务与打卡")
        val taskId = detail(orderId).currentHostingTasks().single().task.id
        env.orderRepository.setOccurrenceCompleted(taskId, true)
        val occurrence = detail(orderId).occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = taskId,
            occurrenceId = null,
            sourceUri = Uri.fromFile(writeTestImage(context, "uf040-task.jpg")),
        )
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context, "uf040-occurrence.jpg")),
        )
        assertEquals(2, detail(orderId).attachments.size)
        cancel(orderId)
        delete(orderId, "任务与打卡截图")
        assertDeleted(orderId)
    }

    @Test
    fun cancelledCountBasedOrderWithCompletedRoundsDeletes() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 17),
                end = LocalDate.of(2026, 10, 17),
                title = "打卡式托管",
                tasks = listOf(TaskDraft(title = "任务 1"), TaskDraft(title = "任务 2")),
            ).copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = 3),
        )
        detail(orderId).currentHostingTasks().forEach { task ->
            env.orderRepository.setOccurrenceCompleted(task.task.id, true, task.slot.key)
        }
        assertTrue("至少一个轮次已计入", env.database.hostingSessionDao().countForOrder(orderId) >= 1)
        cancel(orderId)
        delete(orderId, "打卡式托管轮次")
        assertDeleted(orderId)
    }

    @Test
    fun failedDeletionKeepsTheWholeOrderAndOtherOrders() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val seed = owner.id to account.id
        val doomed = seedHosting("待删作废订单", seed = seed)
        val survivor = seedHosting("同账号保留订单", seed = seed)
        env.orderRepository.setOccurrenceCompleted(
            detail(doomed).currentHostingTasks().single().task.id,
            true,
        )
        env.orderRepository.completeHostingOrder(doomed, "uf040-fail-batch", env.hostingSnapshot(doomed))
        val occurrence = detail(doomed).occurrences.first()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context, "uf040-rollback.jpg")),
        )
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = doomed,
                periodId = detail(doomed).currentPeriod!!.id,
                amountCents = 30000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "定金",
                operationId = "uf040-rollback-receipt",
            ),
        )
        cancel(doomed)
        val before = detail(doomed)
        val countsBefore = rowCounts()
        val file = env.attachmentRepository.fileFor(before.attachments.single())

        val raw = env.database.openHelper.writableDatabase
        raw.execSQL(
            "CREATE TRIGGER uf040_fail_order_delete BEFORE DELETE ON orders " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
        )
        val failure = try {
            env.orderRepository.deleteCancelledOrder(doomed)
            null
        } catch (error: Exception) {
            Log.i("UF040", "注入失败按预期抛出", error)
            error
        }
        raw.execSQL("DROP TRIGGER uf040_fail_order_delete")
        assertNotNull("注入的失败必须抛出", failure)

        assertEquals("失败后行数必须完全回到删除前", countsBefore, rowCounts())
        val after = detail(doomed)
        assertEquals(before.order.id, after.order.id)
        assertEquals(before.periods.map { it.id }, after.periods.map { it.id })
        assertEquals(before.tasks.map { it.id }, after.tasks.map { it.id })
        assertEquals(before.occurrences.map { it.id }, after.occurrences.map { it.id })
        assertEquals(before.receipts.map { it.id }, after.receipts.map { it.id })
        assertEquals(before.attachments.map { it.id }, after.attachments.map { it.id })
        assertTrue("失败不得提前删除附件文件", file.exists())
        assertNotNull("同账号的其他订单不受影响", env.orderRepository.observeOrderDetail(survivor).first())
        assertNotNull("账号本身不受影响", env.accountRepository.findAccount(account.id))
        assertTrue("失败后不应留下悬空外键：${foreignKeyCheckRows()}", foreignKeyCheckRows().isEmpty())
        assertEquals(0, env.receiptDaoCorrections())
    }

    private suspend fun OrderTestEnvironment.receiptDaoCorrections(): Int =
        database.receiptDao().findAllCorrections().size

    /**
     * All-or-nothing on the real (un-injected) path.
     *
     * A bulk-completion order is the case that currently fails inside the cascade, so this
     * test records what the user actually gets: either the order is gone with no orphan
     * rows, or every row, the money and the screenshot file are still there. It stays
     * green after the ordering bug is fixed (then the delete simply succeeds) while the
     * rollback invariants above keep guarding the failure path.
     */
    @Test
    fun deletionOfABulkCompletedOrderIsAllOrNothing() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val seed = owner.id to account.id
        val doomed = seedHosting("批量完成后作废", seed = seed)
        val survivor = seedHosting("同账号另一单", seed = seed)
        env.orderRepository.completeHostingOrder(doomed, "uf040-atomic", env.hostingSnapshot(doomed))
        val occurrence = detail(doomed).occurrences.first()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context, "uf040-atomic.jpg")),
        )
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = doomed,
                periodId = detail(doomed).currentPeriod!!.id,
                amountCents = 30000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "定金",
                operationId = "uf040-atomic-receipt",
            ),
        )
        cancel(doomed)
        val before = detail(doomed)
        val countsBefore = rowCounts()
        val file = env.attachmentRepository.fileFor(before.attachments.single())
        val survivorBefore = detail(survivor)
        val receiptsBefore = env.database.receiptDao().findAll().map { it.orderId }

        val failure = try {
            env.orderRepository.deleteCancelledOrder(doomed)
            null
        } catch (error: Exception) {
            Log.e("UF040", "原子性场景：真实路径删除失败", error)
            error
        }

        if (failure == null) {
            assertDeleted(doomed)
            assertEquals("其它订单的收款不得被连带删除", receiptsBefore - doomed, env.database.receiptDao().findAll().map { it.orderId })
        } else {
            assertEquals("失败必须完整回滚", countsBefore, rowCounts())
            val after = detail(doomed)
            assertEquals(before.order.id, after.order.id)
            assertEquals(before.periods.map { it.id }, after.periods.map { it.id })
            assertEquals(before.tasks.map { it.id }, after.tasks.map { it.id })
            assertEquals(before.occurrences.map { it.id }, after.occurrences.map { it.id })
            assertEquals(before.receipts.map { it.id }, after.receipts.map { it.id })
            assertEquals(before.attachments.map { it.id }, after.attachments.map { it.id })
            assertTrue("失败不得提前删除截图文件", file.exists())
            assertTrue("失败后不应留下悬空外键", foreignKeyCheckRows().isEmpty())
            assertEquals(
                "同一账号的另一单必须保持原样",
                survivorBefore.occurrences.map { it.id },
                detail(survivor).occurrences.map { it.id },
            )
            assertEquals(receiptsBefore, env.database.receiptDao().findAll().map { it.orderId })
            assertNotNull("账号本身不受影响", env.accountRepository.findAccount(account.id))
        }
    }
}
