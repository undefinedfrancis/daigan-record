package com.daigan.record.feature.today

import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.testing.fixedInstant
import com.daigan.record.ui.theme.OrderColors
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Order-level colour filtering of the Today page (UF-028). */
class TodayColorFilterTest {

    private fun order(id: String, color: Int? = null) = Order(
        id = id,
        accountId = "account-1",
        type = OrderType.SHORT_TASK,
        title = id,
        priceCents = 1000,
        paymentMode = PaymentMode.ONE_TIME,
        deadlineDate = null,
        notes = null,
        refresh = GameRefreshRulesDomain(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        ),
        createdAt = fixedInstant,
        completedAt = null,
        colorArgb = color,
    )

    @Test
    fun `all keeps coloured and uncoloured orders`() {
        assertTrue(TodayColorFilter.All.matches(order("a")))
        assertTrue(TodayColorFilter.All.matches(order("b", 0xFFE53935.toInt())))
    }

    @Test
    fun `uncoloured filter keeps only orders without a colour`() {
        assertTrue(TodayColorFilter.Uncolored.matches(order("a")))
        assertFalse(TodayColorFilter.Uncolored.matches(order("b", 0xFFE53935.toInt())))
    }

    @Test
    fun `matching filter is scoped to one colour`() {
        val red = 0xFFE53935.toInt()
        val blue = 0xFF1E88E5.toInt()
        assertTrue(TodayColorFilter.Matched(red).matches(order("a", red)))
        assertFalse(TodayColorFilter.Matched(red).matches(order("b", blue)))
        assertFalse(TodayColorFilter.Matched(red).matches(order("c")))
    }

    @Test
    fun `filter chips only list colours actually used, in palette order`() {
        val red = 0xFFE53935.toInt()
        val green = 0xFF43A047.toInt()
        val used = OrderColors.usedBy(listOf(green, null, red, green))

        assertEquals(listOf(red, green), used.map { it.argb })
        assertEquals("红", used.first().label)
    }

    @Test
    fun `uncoloured orders get the neutral marker, not a generated colour`() {
        assertEquals(OrderColors.neutral, OrderColors.mark(null))
        assertTrue(OrderColors.mark(0xFFE53935.toInt()) != OrderColors.neutral)
    }

    @Test
    fun `palette lookups ignore unknown colours`() {
        assertTrue(OrderColors.optionOf(0xFFE53935.toInt()) != null)
        assertTrue(OrderColors.optionOf(0xFF123456.toInt()) == null)
    }
}
