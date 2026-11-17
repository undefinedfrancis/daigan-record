package com.daigan.record.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * Balance of one payable item: a short order's whole price, or one hosting period.
 * [paidCents] only counts effective (ACTIVE) receipts whose arrival time has passed,
 * so a void / superseded row can never inflate the paid amount, and money still held by
 * a third party (future arrival, e.g. second-hand marketplace escrow) stays in
 * [pendingCents] instead of silently settling the debt.
 */
data class PayableBalance(
    val orderId: String,
    /** Null for a short order. */
    val periodId: String?,
    /** Null for a short order. */
    val periodIndex: Int?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val priceCents: Long,
    val paidCents: Long,
    val outstandingCents: Long,
    /** Effective receipts above the agreed price (e.g. after a price correction). */
    val overpaidCents: Long = 0L,
    /** Effective receipts whose arrival time is still in the future; never offsets debt. */
    val pendingCents: Long = 0L,
    /**
     * 打卡式托管: the single container period carries no business date range, so its
     * label must not present one.
     */
    val countBased: Boolean = false,
) {
    val settled: Boolean get() = outstandingCents == 0L

    val periodLabel: String
        get() = when {
            periodId == null -> "短期订单"
            countBased -> "打卡式托管"
            else -> {
                val index = (periodIndex ?: 0) + 1
                if (periodStart != null && periodEnd != null) {
                    "第 $index 期 · $periodStart – $periodEnd"
                } else {
                    "第 $index 期"
                }
            }
        }
}

/**
 * Derived order-level balances. Everything here is computed from the stored price and
 * effective receipts; no screen keeps its own running total.
 */
data class OrderBalance(
    val orderId: String,
    val orderType: OrderType,
    val paymentMode: PaymentMode,
    /** Current period first for hosting orders; one item for short orders. */
    val items: List<PayableBalance>,
    val totalPriceCents: Long,
    val totalPaidCents: Long,
    val totalOutstandingCents: Long,
    val currentPeriodId: String?,
    /** Sum of per-item overpaid surpluses; zero for cancelled orders. */
    val totalOverpaidCents: Long = 0L,
    /** Sum of in-transit receipts (arrival time in the future). */
    val totalPendingCents: Long = 0L,
    /**
     * Ids of periods that start after today. They are collected separately so a future,
     * already renewed period is never presented as today's debt (UF-020).
     */
    val futurePeriodIds: Set<String> = emptySet(),
) {
    val hosting: Boolean get() = orderType == OrderType.HOSTING

    val currentItem: PayableBalance?
        get() = if (hosting) items.firstOrNull { it.periodId == currentPeriodId } else items.firstOrNull()

    /** Debt from every period that is not the one in effect today. */
    val historicalOutstandingCents: Long
        get() = if (!hosting) {
            0L
        } else {
            totalOutstandingCents - (currentItem?.outstandingCents ?: 0L) - futureOutstandingCents
        }

    /** Outstanding amount on periods that have not started yet. */
    val futureOutstandingCents: Long
        get() = if (!hosting) 0L else Money.sumCents(
            items.filter { it.periodId in futurePeriodIds }.map { it.outstandingCents },
        )

    val futureItems: List<PayableBalance>
        get() = items.filter { it.periodId in futurePeriodIds }

    val receivableItems: List<PayableBalance> get() = items.filter { !it.settled }
}

/**
 * One outstanding item in the always-all-time collection list. Context is included so
 * the statistics page and payment screens can identify archived debt precisely.
 */
data class OutstandingItem(
    val orderId: String,
    val orderTitle: String,
    val ownerId: String,
    val ownerNumber: String,
    val ownerNickname: String,
    val accountId: String,
    val gameName: String,
    val server: String,
    val orderType: OrderType,
    val periodId: String?,
    val periodIndex: Int?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val priceCents: Long,
    val paidCents: Long,
    val outstandingCents: Long,
    /** Sort key: period start for hosting, order creation date for short orders. */
    val sortDate: LocalDate,
    /** 打卡式托管 has no business period dates to display. */
    val countBased: Boolean = false,
) {
    val periodLabel: String
        get() = when {
            orderType == OrderType.SHORT_TASK -> "短期订单"
            countBased -> "打卡式托管"
            else -> "第 ${(periodIndex ?: 0) + 1} 期" +
                if (periodStart != null && periodEnd != null) " · $periodStart – $periodEnd" else ""
        }
}

/**
 * Pure balance derivation shared by the order detail, payment entry and statistics.
 * Uses checked arithmetic so a corrupted ledger fails loudly instead of wrapping.
 */
object Balances {

    fun order(
        order: Order,
        periods: List<HostingPeriod>,
        receipts: List<Receipt>,
        today: LocalDate? = null,
        now: Instant? = null,
    ): OrderBalance {
        // Only money that has actually arrived settles a debt. `now = null` keeps the
        // legacy "every effective receipt counts" behaviour for pure unit tests.
        val effective = receipts.filter { receipt ->
            receipt.effective && (now == null || !receipt.receivedAt.isAfter(now))
        }
        val pending = if (now == null) {
            emptyList()
        } else {
            receipts.filter { it.effective && it.receivedAt.isAfter(now) }
        }
        if (!order.isHosting) {
            val price = order.priceCents ?: 0L
            val paid = Money.sumCents(effective.filter { it.periodId == null }.map { it.amountCents })
            val pendingCents = Money.sumCents(
                pending.filter { it.periodId == null }.map { it.amountCents },
            )
            val item = PayableBalance(
                orderId = order.id,
                periodId = null,
                periodIndex = null,
                periodStart = null,
                periodEnd = null,
                priceCents = price,
                paidCents = paid,
                outstandingCents = if (order.cancelled) 0L else Money.remainingCents(price, paid),
                overpaidCents = if (order.cancelled) 0L else Money.overpaidCents(price, paid),
                pendingCents = pendingCents,
            )
            return OrderBalance(
                orderId = order.id,
                orderType = order.type,
                paymentMode = order.paymentMode,
                items = listOf(item),
                totalPriceCents = item.priceCents,
                totalPaidCents = item.paidCents,
                totalOutstandingCents = item.outstandingCents,
                currentPeriodId = null,
                totalOverpaidCents = item.overpaidCents,
                totalPendingCents = item.pendingCents,
            )
        }

        val descending = periods.sortedByDescending { it.periodIndex }
        val items = descending.map { period ->
            val paid = Money.sumCents(
                effective.filter { it.periodId == period.id }.map { it.amountCents },
            )
            PayableBalance(
                orderId = order.id,
                periodId = period.id,
                periodIndex = period.periodIndex,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                priceCents = period.priceCents,
                paidCents = paid,
                outstandingCents = if (order.cancelled) 0L else Money.remainingCents(period.priceCents, paid),
                overpaidCents = if (order.cancelled) 0L else Money.overpaidCents(period.priceCents, paid),
                pendingCents = Money.sumCents(
                    pending.filter { it.periodId == period.id }.map { it.amountCents },
                ),
                countBased = order.countBased,
            )
        }
        // "本期" is the period in effect today, not the newest row: after an early
        // renewal the newest row is a future period whose money is not due yet.
        val currentId = if (today == null) {
            descending.firstOrNull()?.id
        } else {
            descending.firstOrNull { it.startDate <= today && it.endDate >= today }?.id
                ?: descending.filter { it.startDate > today }.minByOrNull { it.startDate }?.id
                ?: descending.firstOrNull()?.id
        }
        val futureIds = if (today == null) {
            emptySet()
        } else {
            periods.filter { it.startDate > today }.map { it.id }.toSet()
        }
        return OrderBalance(
            orderId = order.id,
            orderType = order.type,
            paymentMode = order.paymentMode,
            items = items,
            totalPriceCents = Money.sumCents(items.map { it.priceCents }),
            totalPaidCents = Money.sumCents(items.map { it.paidCents }),
            totalOutstandingCents = Money.sumCents(items.map { it.outstandingCents }),
            currentPeriodId = currentId,
            totalOverpaidCents = Money.sumCents(items.map { it.overpaidCents }),
            totalPendingCents = Money.sumCents(items.map { it.pendingCents }),
            futurePeriodIds = futureIds,
        )
    }
}
