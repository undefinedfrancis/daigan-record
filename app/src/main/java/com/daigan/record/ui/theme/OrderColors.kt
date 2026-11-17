package com.daigan.record.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The fixed, directly clickable business-colour palette of an order (UF-028).
 *
 * Colours are stored as opaque ARGB ints on `orders.orderColor`, so the detail page's
 * dots, the Today stripes and the Today colour filter all read exactly one value. The
 * palette is intentionally small and saturated: the mark has to be recognisable as a
 * stripe or dot beside a task title, not a barely visible pastel background.
 */
data class OrderColorOption(
    val argb: Int,
    val label: String,
)

object OrderColors {

    /**
     * Marker of an order **without** a chosen colour.
     *
     * A neutral grey, deliberately not derived from the order id: an uncoloured order must
     * not look like it owns some colour the user never picked, otherwise it would appear
     * coloured while the colour filter can never match it. Clearing a colour returns the
     * order to exactly this appearance.
     */
    val neutral: Color = Color(0xFF9E9E9E)

    val palette: List<OrderColorOption> = listOf(
        OrderColorOption(0xFFE53935.toInt(), "红"),
        OrderColorOption(0xFFFB8C00.toInt(), "橙"),
        OrderColorOption(0xFFFDD835.toInt(), "黄"),
        OrderColorOption(0xFF43A047.toInt(), "绿"),
        OrderColorOption(0xFF00897B.toInt(), "青"),
        OrderColorOption(0xFF1E88E5.toInt(), "蓝"),
        OrderColorOption(0xFF5E35B1.toInt(), "靛"),
        OrderColorOption(0xFFD81B60.toInt(), "玫红"),
        OrderColorOption(0xFF8D6E63.toInt(), "棕"),
    )

    fun optionOf(argb: Int): OrderColorOption? = palette.firstOrNull { it.argb == argb }

    /** Colours actually used by the given orders, in palette order (for the Today filter). */
    fun usedBy(orderColors: Iterable<Int?>): List<OrderColorOption> {
        val used = orderColors.filterNotNull().toSet()
        return palette.filter { it.argb in used }
    }

    /**
     * The vivid mark used for a stripe/dot: the chosen colour, or [neutral] when none was
     * chosen. Never a light tint, so the mark stays readable next to text.
     */
    fun mark(colorArgb: Int?): Color = colorArgb?.let(::Color) ?: neutral

    /**
     * Background wash derived from the mark. An uncoloured order gets the plain surface:
     * the absence of a colour must be visible as "no colour", not as a random pale tint.
     */
    fun tint(colorArgb: Int?, surface: Color): Color =
        colorArgb?.let { Color(it).copy(alpha = 0.12f).compositeOver(surface) } ?: surface
}
