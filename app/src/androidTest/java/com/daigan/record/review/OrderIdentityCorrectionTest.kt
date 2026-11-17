package com.daigan.record.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.*
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.numericTask
import com.daigan.record.testing.shortDraft
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderIdentityCorrectionTest {
    private lateinit var env: OrderTestEnvironment
    private val day = LocalDate.of(2026, 10, 17)
    @Before fun setUp() { env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { env.close() }

    @Test fun unusedOrderMovesAcrossOwnersAndConvertsBothWaysWithoutChangingTaskOrPrice() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val (otherOwner, otherAccount) = env.seedOwnerAndAccount("另一老板", uid = "second")
        val command = shortDraft(owner.id, account.id,
            tasks = listOf(numericTask(start = "10", target = "100", recurrence = RecurrenceType.ONE_TIME)))
        val id = env.orderRepository.createShortOrder(command)
        val task = env.database.taskDao().findForOrder(id).single()
        env.orderRepository.updateUnusedOrderIdentity(id, otherAccount.id, OrderType.HOSTING, day, day.plusDays(9))
        val hosting = env.orderRepository.observeOrderDetail(id).first()!!
        assertEquals(otherOwner.id, hosting.owner.id)
        assertNull(hosting.order.priceCents)
        assertEquals(10000L, hosting.periods.single().priceCents)
        assertEquals(day.plusDays(9), hosting.periods.single().endDate)
        assertEquals(task.id, hosting.tasks.single().id)
        assertEquals(RecurrenceType.DAILY, hosting.tasks.single().recurrence)
        assertEquals(task.currentValue, hosting.tasks.single().currentValue)
        assertEquals(id, env.orderRepository.createShortOrder(command))
        assertEquals(OrderType.SHORT_TASK, env.database.creationOperationDao().findAll().single().orderType)
        env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.SHORT_TASK, day, null)
        val short = env.orderRepository.observeOrderDetail(id).first()!!
        assertEquals(owner.id, short.owner.id)
        assertEquals(10000L, short.order.priceCents)
        assertNull(short.order.deadlineDate)
        assertTrue(short.periods.isEmpty())
        assertEquals(task.id, short.tasks.single().id)
        assertNull(short.tasks.single().periodId)
        assertEquals(RecurrenceType.ONE_TIME, short.tasks.single().recurrence)
    }

    @Test fun invalidDatesOrArchivedTargetLeaveSourceUntouched() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val (_, other) = env.seedOwnerAndAccount("另一老板", uid = "second")
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val before = env.database.orderDao().findById(id)
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, other.id, OrderType.HOSTING, day, day.minusDays(1)) }.isFailure)
        assertEquals(before, env.database.orderDao().findById(id))
        assertTrue(env.database.orderDao().findPeriods(id).isEmpty())
        env.accountRepository.archiveAccount(other.id)
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, other.id, OrderType.SHORT_TASK, day, null) }.isFailure)
        assertEquals(before, env.database.orderDao().findById(id))
    }

    @Test fun priceAuditBlocksIdentityAndUnusedDeletionEvenWithNoReceipts() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        env.orderRepository.updateShortOrderPrice(id, 8000, "price-correction", "更正")
        val before = env.database.orderDao().findById(id)
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.HOSTING, day, day.plusDays(5)) }.isFailure)
        assertTrue(runCatching { env.orderRepository.deleteUnusedOrder(id) }.isFailure)
        assertEquals(before, env.database.orderDao().findById(id))
        assertEquals(1, env.database.orderDao().findPriceChanges(id).size)
    }

    @Test fun numericProgressAndUndoneBatchBlockIdentityCorrections() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, tasks = listOf(numericTask())))
        val task = env.database.taskDao().findForOrder(id).single()
        env.orderRepository.updateShortTaskProgress(task.id, BigDecimal("50"))
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.HOSTING, day, day.plusDays(5)) }.isFailure)
        env.orderRepository.updateShortTaskProgress(task.id, BigDecimal.ZERO)
        val batch = env.orderRepository.completeShortOrder(id, "batch")
        env.orderRepository.undoBatch(checkNotNull(batch.batchId))
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.HOSTING, day, day.plusDays(5)) }.isFailure)
        assertEquals(OrderType.SHORT_TASK, env.database.orderDao().findById(id)!!.type)
    }

    @Test fun voidedReceiptStillBlocksReassignmentAndConversion() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
        val receipt = env.receiptRepository.recordPayment(PaymentRequest(id, null, 10000,
            env.clock.now(), PaymentChannel.WECHAT, null, "receipt")).receipt
        env.receiptRepository.correctReceipt(ReceiptCorrectionRequest.Void("void", receipt.id, "误录"))
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.HOSTING, day, day.plusDays(5)) }.isFailure)
        assertEquals(1, env.database.receiptDao().findForOrder(id).size)
        assertEquals(OrderType.SHORT_TASK, env.database.orderDao().findById(id)!!.type)
    }

    @Test fun undoneOccurrenceAndFutureRenewalArePreservedAndBlockConversion() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)))
        val task = env.database.taskDao().findForOrder(id).single()
        env.orderRepository.setOccurrenceCompleted(task.id, true)
        env.orderRepository.setOccurrenceCompleted(task.id, false)
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(id, account.id, OrderType.SHORT_TASK, day, null) }.isFailure)
        assertEquals(1, env.database.occurrenceDao().findForOrder(id).size)
        val future = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day.plusDays(10), day.plusDays(15)))
        env.orderRepository.renewHosting(future, "renew", 5, 5000)
        assertTrue(runCatching { env.orderRepository.updateUnusedOrderIdentity(future, account.id, OrderType.SHORT_TASK, day, null) }.isFailure)
        assertEquals(2, env.database.orderDao().findPeriods(future).size)
    }
}
