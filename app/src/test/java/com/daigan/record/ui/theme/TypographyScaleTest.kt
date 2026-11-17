package com.daigan.record.ui.theme

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** UF-035: one documented role per text size, body text kept at the readable baseline. */
class TypographyScaleTest {
    @Test
    fun pageTitleAndBodyUseTheApprovedComfortableScale() {
        assertEquals(22f, Typography.headlineSmall.fontSize.value)
        assertEquals(15f, Typography.bodyMedium.fontSize.value)
        assertEquals(13f, Typography.bodySmall.fontSize.value)
        assertEquals(16f, Typography.titleMedium.fontSize.value)
    }

    @Test
    fun rolesHaveDistinctWeightAndLineHeight() {
        // Page title > unit title > section label > body, by weight and not by shrinking.
        assertEquals(FontWeight.SemiBold, Typography.headlineSmall.fontWeight)
        assertEquals(FontWeight.SemiBold, Typography.titleMedium.fontWeight)
        assertEquals(FontWeight.Medium, Typography.titleSmall.fontWeight)
        assertEquals(FontWeight.Normal, Typography.bodyMedium.fontWeight)

        assertEquals(29f, Typography.headlineSmall.lineHeight.value)
        assertEquals(22f, Typography.titleMedium.lineHeight.value)
        assertEquals(20f, Typography.titleSmall.lineHeight.value)
        assertEquals(22f, Typography.bodyMedium.lineHeight.value)
        assertEquals(19f, Typography.bodySmall.lineHeight.value)
    }

    @Test
    fun labelsStaySmallButPresentForTagsAndFieldCaptions() {
        assertEquals(14f, Typography.labelLarge.fontSize.value)
        assertEquals(12f, Typography.labelMedium.fontSize.value)
        assertEquals(11f, Typography.labelSmall.fontSize.value)
        assertEquals(FontWeight.Medium, Typography.labelMedium.fontWeight)
    }
}
