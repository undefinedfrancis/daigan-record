package com.daigan.record.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UF-012: price edits may leave receipts above the revised price. The balance model
 * must show the surplus instead of failing, and cancellation still zeroes outstanding.
 */
class BalancesTest {

    private val refresh = GameRefreshRulesDomain(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    private fun hostingOrder(cancelledAt: Instant? = null) = Order(
        id = "order-1",
        accountId = "account-1",
        type = OrderType.HOSTING,
        title = "日常托管",
        priceCents = null,
        paymentMode = PaymentMode.INSTALLMENTS,
        deadlineDate = null,
        notes = null,
        refresh = refresh,
        createdAt = Instant.EPOCH,
        completedAt = null,
        cancelledAt = cancelledAt,
    )

    private fun period(priceCents: Long) = HostingPeriod(
        id = "period-1",
        orderId = "order-1",
        periodIndex = 0,
        startDate = LocalDate.of(2026, 10, 1),
        endDate = LocalDate.of(2026, 10, 31),
        priceCents = priceCents,
        refresh = refresh,
        createdAt = Instant.EPOCH,
        renewalOperationId = null,
    )

    private fun receipt(
        amountCents: Long,
        status: ReceiptStatus = ReceiptStatus.ACTIVE,
        receivedAt: Instant = Instant.EPOCH,
    ) = Receipt(
        id = "receipt-1",
        operationId = "op-1",
        orderId = "order-1",
        periodId = "period-1",
        amountCents = amountCents,
        receivedAt = receivedAt,
        channel = PaymentChannel.WECHAT,
        note = null,
        status = status,
        replacedByReceiptId = null,
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `receipts above the period price produce zero outstanding plus an overpaid surplus`() {
        val balance = Balances.order(
            order = hostingOrder(),
            periods = listOf(period(priceCents = 10_000)),
            receipts = listOf(receipt(amountCents = 15_000)),
        )
        val item = balance.items.single()
        assertEquals(15_000L, item.paidCents)
        assertEquals(0L, item.outstandingCents)
        assertEquals(5_000L, item.overpaidCents)
        assertEquals(0L, balance.totalOutstandingCents)
        assertEquals(5_000L, balance.totalOverpaidCents)
    }

    @Test
    fun `cancelled orders keep zero outstanding and zero overpaid while paid stays visible`() {
        val balance = Balances.order(
            order = hostingOrder(cancelledAt = Instant.EPOCH),
            periods = listOf(period(priceCents = 10_000)),
            receipts = listOf(receipt(amountCents = 15_000)),
        )
        val item = balance.items.single()
        assertEquals(15_000L, item.paidCents)
        assertEquals(0L, item.outstandingCents)
        assertEquals(0L, item.overpaidCents)
    }

    @Test
    fun `voided and replaced rows never inflate paid amounts`() {
        val balance = Balances.order(
            order = hostingOrder(),
            periods = listOf(period(priceCents = 10_000)),
            receipts = listOf(
                receipt(amountCents = 8_000),
                receipt(amountCents = 7_000).copy(id = "receipt-2", status = ReceiptStatus.VOIDED),
                receipt(amountCents = 6_000).copy(id = "receipt-3", status = ReceiptStatus.REPLACED),
            ),
        )
        val item = balance.items.single()
        assertEquals(8_000L, item.paidCents)
        assertEquals(2_000L, item.outstandingCents)
        assertEquals(0L, item.overpaidCents)
    }

    @Test
    fun `future receipts stay in transit and never settle the debt`() {
        val now = Instant.parse("2026-10-10T00:00:00Z")
        val arrival = now.plusSeconds(86_400)
        val pending = Balances.order(
            order = hostingOrder(),
            periods = listOf(period(priceCents = 10_000)),
            receipts = listOf(receipt(amountCents = 10_000, receivedAt = arrival)),
            now = now,
        )
        assertEquals(0L, pending.totalPaidCents)
        assertEquals(10_000L, pending.totalOutstandingCents)
        assertEquals(10_000L, pending.totalPendingCents)
        assertEquals(10_000L, pending.items.single().pendingCents)

        val arrived = Balances.order(
            order = hostingOrder(),
            periods = listOf(period(priceCents = 10_000)),
            receipts = listOf(receipt(amountCents = 10_000, receivedAt = arrival)),
            now = now.plusSeconds(2 * 86_400),
        )
        assertEquals(10_000L, arrived.totalPaidCents)
        assertEquals(0L, arrived.totalOutstandingCents)
        assertEquals(0L, arrived.totalPendingCents)
    }
}
