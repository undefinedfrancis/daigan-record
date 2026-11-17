package com.daigan.record.data.model

import com.daigan.record.testing.TemplateFixtures
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3-5: category-level hosting defaults are applied to order creation, an explicit
 * template value wins, and a mixed category selection is reported instead of silently
 * picking the first category.
 */
class TemplateServiceDefaultsTest {

    private val template = TemplateFixtures.template()

    @Test
    fun explicitCountModeWinsOverCategoryDefaults() {
        val count = template.copy(
            hostingMode = HostingMode.COUNT_BASED,
            targetSessions = 12,
            serviceWeekdays = "1,3,5",
            categories = listOf(
                TemplateFixtures.category("template-1", "c1", "日常")
                    .copy(hostingMode = HostingMode.DATE_RANGE, serviceWeekdays = "2,4"),
            ),
        )

        val defaults = count.serviceDefaults

        assertTrue(defaults.explicit)
        assertEquals(HostingMode.COUNT_BASED, defaults.hostingMode)
        assertEquals(12, defaults.targetSessions)
        assertTrue("count mode clears inherited weekdays", defaults.serviceWeekdays.isEmpty())
    }

    @Test
    fun explicitDateRangeCarriesServiceWeekdays() {
        val dated = template.copy(
            hostingMode = HostingMode.DATE_RANGE,
            serviceWeekdays = "1,5",
        )

        val defaults = dated.serviceDefaults

        assertEquals(HostingMode.DATE_RANGE, defaults.hostingMode)
        assertNull(defaults.targetSessions)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), defaults.serviceWeekdays)
    }

    @Test
    fun inheritedCategoryConsensusIsApplied() {
        val inherited = template.copy(
            hostingMode = null,
            categories = listOf(
                TemplateFixtures.category("template-1", "c1", "主号")
                    .copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = 8),
                TemplateFixtures.category("template-1", "c2", "小号")
                    .copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = 8),
            ),
        )

        val defaults = inherited.serviceDefaults

        assertFalse(defaults.explicit)
        assertFalse(defaults.conflict)
        assertEquals(HostingMode.COUNT_BASED, defaults.hostingMode)
        assertEquals(8, defaults.targetSessions)
    }

    @Test
    fun mixedCategoryModesAreAConflictNotAFirstPick() {
        val mixed = template.copy(
            hostingMode = null,
            categories = listOf(
                TemplateFixtures.category("template-1", "c1", "主号")
                    .copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = 8),
                TemplateFixtures.category("template-1", "c2", "小号")
                    .copy(hostingMode = HostingMode.DATE_RANGE, serviceWeekdays = "1,2"),
            ),
        )

        val defaults = mixed.serviceDefaults

        assertTrue(defaults.conflict)
        assertNull("conflict must not pick a mode", defaults.hostingMode)
        assertNull(defaults.targetSessions)
        assertTrue(defaults.serviceWeekdays.isEmpty())
    }

    @Test
    fun undeclaredCategoriesAreIgnoredRatherThanBlockingConsensus() {
        val partial = template.copy(
            hostingMode = null,
            categories = listOf(
                TemplateFixtures.category("template-1", "c1", "主号")
                    .copy(hostingMode = HostingMode.DATE_RANGE, serviceWeekdays = "3"),
                TemplateFixtures.category("template-1", "c2", "未声明"),
            ),
        )

        val defaults = partial.serviceDefaults

        assertFalse(defaults.conflict)
        assertEquals(HostingMode.DATE_RANGE, defaults.hostingMode)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), defaults.serviceWeekdays)
    }

    @Test
    fun noDeclaredDefaultKeepsTheFormChoice() {
        val none = template.copy(hostingMode = null)

        val defaults = none.serviceDefaults

        assertNull(defaults.hostingMode)
        assertFalse(defaults.conflict)
        assertTrue(defaults.serviceWeekdays.isEmpty())
    }
}
