package com.daigan.record.feature.today

import com.daigan.record.data.model.Order
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.TodayHostingOrder

/**
 * Compact colour filter of the Today page (UF-028).
 *
 * The colour used to be set *on this page, per account*. It now belongs to the order and
 * is chosen on the order detail page; Today only filters by it, so the same account's
 * orders can be shown or hidden independently.
 */
sealed interface TodayColorFilter {

    /** Everything, coloured or not. The reset path of the filter row. */
    data object All : TodayColorFilter

    /** Orders with no colour chosen — the "未着色" chip stays available. */
    data object Uncolored : TodayColorFilter

    /** Orders whose stored colour equals [argb]. */
    data class Matched(val argb: Int) : TodayColorFilter

    fun matches(order: Order): Boolean = when (this) {
        All -> true
        Uncolored -> order.colorArgb == null
        is Matched -> order.colorArgb == argb
    }
}

/** Pure filtering helpers, kept outside Compose so they can be unit-tested. */
object TodayColorFiltering {

    fun hosting(orders: List<TodayHostingOrder>, filter: TodayColorFilter): List<TodayHostingOrder> =
        if (filter == TodayColorFilter.All) orders else orders.filter { filter.matches(it.order) }

    fun short(orders: List<ShortWorkOrder>, filter: TodayColorFilter): List<ShortWorkOrder> =
        if (filter == TodayColorFilter.All) orders else orders.filter { filter.matches(it.order) }
}
