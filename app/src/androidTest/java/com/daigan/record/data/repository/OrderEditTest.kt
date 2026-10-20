package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** UF-011/UF-012: in-place rename and audited price corrections that never touch receipts. */
@RunWith(AndroidJUnit4::class)
class OrderEditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun receiptFingerprint(orderId: String): List<String> =
        env.orderRepository.observeOrderDetail(orderId).first()!!.receipts
            .sortedBy { it.id }
            .map { "${it.id}|${it.amountCents}|${it.status}|${it.receivedAt}" }

    @Test
    fun renameKeepsIdentityPeriodsTasksAndReceipts() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 1),
                end = LocalDate.of(2026, 10, 10),
            ).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val periodId = env.database.orderDao().findPeriods(orderId).first().id
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = orderId,
                periodId = periodId,
                amountCents = 10_000,
                receivedAt = Instant.parse("2026-10-02T02:00:00Z"),
                channel = PaymentChannel.WECHAT,
                note = null,
                operationId = "op-pay-1",
            ),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!

        env.orderRepository.updateOrderTitle(orderId, "活动日常托管")

        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(orderId, after.order.id)
        assertEquals("活动日常托管", after.order.title)
        assertEquals(before.periods.map { it.id }, after.periods.map { it.id })
        assertEquals(before.tasks.map { it.id }, after.tasks.map { it.id })
        assertEquals(before.occurrences.map { it.id }, after.occurrences.map { it.id })
        assertEquals(receiptFingerprint(orderId), after.receipts
            .sortedBy { it.id }.map { "${it.id}|${it.amountCents}|${it.status}|${it.receivedAt}" })
    }

    @Test
    fun hostingPriceEditAuditsThePeriodAndLeavesReceiptsUntouched() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 1),
                end = LocalDate.of(2026, 10, 10),
            ).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        val periodId = env.database.orderDao().findPeriods(orderId).first().id
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = orderId,
                periodId = periodId,
                amountCents = 20_000,
                receivedAt = Instant.parse("2026-10-02T02:00:00Z"),
                channel = PaymentChannel.ALIPAY,
                note = null,
                operationId = "op-pay-1",
            ),
        )
        val receiptsBefore = receiptFingerprint(orderId)

        env.orderRepository.updatePeriodPrice(
            orderId = orderId,
            periodId = periodId,
            priceCents = 15_000,
            operationId = "op-price-1",
            reason = "拆分活动价",
        )

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(15_000L, detail.periods.single().priceCents)
        assertEquals(receiptsBefore, detail.receipts
            .sortedBy { it.id }.map { "${it.id}|${it.amountCents}|${it.status}|${it.receivedAt}" })
        val balance = detail.balance()
        assertEquals(0L, balance.totalOutstandingCents)
        assertEquals(5_000L, balance.totalOverpaidCents)

        val changes = env.database.orderDao().findPriceChanges(orderId)
        assertEquals(1, changes.size)
        assertEquals(30_000L, changes.single().previousPriceCents)
        assertEquals(15_000L, changes.single().newPriceCents)
        assertEquals("拆分活动价", changes.single().reason)
        assertEquals(periodId, changes.single().periodId)

        // Retry with the same operation id must not double-audit or change anything.
        env.orderRepository.updatePeriodPrice(
            orderId = orderId,
            periodId = periodId,
            priceCents = 12_000,
            operationId = "op-price-1",
            reason = "重试不同载荷",
        )
        assertEquals(15_000L, env.database.orderDao().findPeriod(periodId)!!.priceCents)
        assertEquals(1, env.database.orderDao().findPriceChanges(orderId).size)
    }

    @Test
    fun shortPriceEditBelowPaidShowsOverpaidWithoutTouchingReceipts() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            shortDraft(
                ownerId = owner.id,
                accountId = account.id,
                priceYuan = "100.00",
            ).copy(paymentMode = PaymentMode.INSTALLMENTS),
        )
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = orderId,
                periodId = null,
                amountCents = 8_000,
                receivedAt = Instant.parse("2026-10-02T02:00:00Z"),
                channel = PaymentChannel.OTHER,
                note = "现金",
                operationId = "op-pay-1",
            ),
        )
        val receiptsBefore = receiptFingerprint(orderId)

        env.orderRepository.updateShortOrderPrice(
            orderId = orderId,
            priceCents = 5_000,
            operationId = "op-price-2",
            reason = "活动拆分改价",
        )

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(5_000L, detail.order.priceCents)
        assertEquals(receiptsBefore, detail.receipts
            .sortedBy { it.id }.map { "${it.id}|${it.amountCents}|${it.status}|${it.receivedAt}" })
        val balance = detail.balance()
        assertEquals(0L, balance.totalOutstandingCents)
        assertEquals(3_000L, balance.totalOverpaidCents)
    }

    @Test
    fun invalidEditsAreRejectedWithoutPersistingAnything() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 1),
                end = LocalDate.of(2026, 10, 10),
            ),
        )
        val periodId = env.database.orderDao().findPeriods(orderId).first().id

        expectIllegal { env.orderRepository.updateOrderTitle(orderId, "   ") }
        expectIllegal { env.orderRepository.updateOrderTitle(orderId, "长".repeat(41)) }
        expectIllegal {
            env.orderRepository.updatePeriodPrice(orderId, periodId, -1, "op-bad-1", null)
        }
        expectIllegal {
            // A hosting order has no short-order price to edit.
            env.orderRepository.updateShortOrderPrice(orderId, 100, "op-bad-2", null)
        }

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals("日常托管", detail.order.title)
        assertEquals(30_000L, detail.periods.single().priceCents)
        assertTrue(env.database.orderDao().findPriceChanges(orderId).isEmpty())
    }

    private inline fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("应拒绝非法修改")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
