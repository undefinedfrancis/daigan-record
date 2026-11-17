package com.daigan.record.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.InitialPaymentDraft
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderEditDraft
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.withPeriod
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Stage 2 follow-up: the unified editor must be able to target a **historical** hosting
 * period, not only current/future ones.
 *
 * These are the repository-level halves of the editor checks; the screen-side phase
 * classification is covered by `HostingPeriodSelectionTest` (JVM).
 */
@RunWith(AndroidJUnit4::class)
class Stage2PeriodSelectionTest {

    private lateinit var env: OrderTestEnvironment

    @Before
    fun setup() {
        env = OrderTestEnvironment(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun teardown() {
        env.close()
    }

    private suspend fun detail(id: String): OrderDetail =
        requireNotNull(env.orderRepository.observeOrderDetail(id).first())

    private suspend fun hostWithThreePeriods(): Pair<String, OrderDetail> {
        val (owner, account) = env.seedOwnerAndAccount()
        // Period 1 is already over; the two renewals become the current and a future one.
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 9, 1),
                end = LocalDate.of(2026, 9, 10),
            ).copy(
                paymentMode = PaymentMode.INSTALLMENTS,
                initialPayment = InitialPaymentDraft("100.00", operationId = "pay-hist-1"),
            ),
        )
        env.orderRepository.renewHosting(orderId, "renew-1", 10, 30000)
        env.orderRepository.renewHosting(orderId, "renew-2", 10, 40000)
        return orderId to detail(orderId)
    }

    private fun OrderDetail.latestPeriodOrThrow(): HostingPeriod =
        periods.maxByOrNull { it.periodIndex } ?: error("订单没有期次")

    private fun OrderDetail.historical(latestScheduled: HostingPeriod): HostingPeriod =
        periods.filter { it.id != latestScheduled.id }.minByOrNull { it.periodIndex }
            ?: error("需要一个历史期次")

    @Test
    fun editingHistoricalPeriodPriceKeepsReceiptsAndOtherPeriods() = runBlocking {
        val (orderId, before) = hostWithThreePeriods()
        assertEquals(3, before.periods.size)
        val historical = before.historical(before.latestPeriodOrThrow())
        val others = before.periods.filter { it.id != historical.id }
        val receiptsBefore = before.receipts.map { it.id to it.amountCents }
        val historicalTaskIds = before.tasks.filter { it.periodId == historical.id }.map { it.id }.toSet()

        env.orderRepository.applyOrderEdit(
            OrderEditDraft.of(before)
                .withPeriod(before, historical.id)
                .copy(priceYuan = "120.00", priceReason = "历史价更正"),
        )

        val after = detail(orderId)
        assertEquals(12000L, after.periods.first { it.id == historical.id }.priceCents)
        others.forEach { untouched ->
            val current = after.periods.first { it.id == untouched.id }
            assertEquals(untouched.priceCents, current.priceCents)
            assertEquals(untouched.startDate, current.startDate)
            assertEquals(untouched.endDate, current.endDate)
        }
        assertEquals(receiptsBefore, after.receipts.map { it.id to it.amountCents })
        assertEquals(historicalTaskIds, after.tasks.filter { it.periodId == historical.id }.map { it.id }.toSet())
        assertTrue(
            "only the selected period gets an audit row",
            env.database.orderDao().findPriceChanges(orderId)
                .filter { it.newPriceCents == 12000L }
                .all { it.periodId == historical.id },
        )
        assertTrue(
            env.database.orderDao().findPriceChanges(orderId).any { it.periodId == historical.id },
        )
    }

    @Test
    fun priceAndNameCorrectionWorksWhenEveryPeriodHasEnded() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 1, 1),
                end = LocalDate.of(2026, 1, 10),
            ),
        )
        val before = detail(orderId)
        val onlyPeriod = before.periods.single()

        env.orderRepository.applyOrderEdit(
            OrderEditDraft.of(before)
                .withPeriod(before, onlyPeriod.id)
                .copy(title = "历史单", priceYuan = "88.00"),
        )

        val after = detail(orderId)
        assertEquals("历史单", after.order.title)
        assertEquals(8800L, after.periods.single().priceCents)
    }

    @Test
    fun selectingAPeriodLoadsItsOwnPriceDatesAndTasks() = runBlocking {
        val (_, before) = hostWithThreePeriods()
        val latest = before.latestPeriodOrThrow()
        val historical = before.historical(latest)
        val current = latest

        val draft = OrderEditDraft.of(before).withPeriod(before, historical.id)

        assertEquals(historical.id, draft.periodId)
        assertEquals(historical.startDate, draft.startDate)
        assertEquals(historical.endDate, draft.endDate)
        assertEquals(historical.priceCents, requireNotNull(draft.priceCentsOrNull))
        assertEquals(
            before.tasks.filter { it.periodId == historical.id && it.retiredAt == null }.map { it.id }.toSet(),
            draft.tasks.mapNotNull { it.id }.toSet(),
        )
        // Order-level fields are not period-scoped.
        assertEquals(before.order.title, draft.title)
        assertEquals(before.order.paymentMode, draft.paymentMode)
        // Switching to the current period loads that period instead.
        val switched = draft.withPeriod(before, current.id)
        assertEquals(current.id, switched.periodId)
        assertEquals(current.priceCents, requireNotNull(switched.priceCentsOrNull))
    }
}
