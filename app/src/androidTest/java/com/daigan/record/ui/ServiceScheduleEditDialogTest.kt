package com.daigan.record.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.feature.orders.ServiceScheduleEditDialog
import com.daigan.record.testing.activeHostingDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import java.time.DayOfWeek
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceScheduleEditDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cumulativeValidationAndSubmitKeepDialogInput() {
        val detail = activeHostingDetailForUi()
        var submitted: Triple<String, Int?, Set<DayOfWeek>>? = null
        compose.setContent {
            DaiganRecordTheme {
                ServiceScheduleEditDialog(detail, false, {}, { id, days, weekdays ->
                    submitted = Triple(id, days, weekdays)
                })
            }
        }
        compose.onNodeWithText("累计服务日").performScrollTo().performClick()
        compose.onNodeWithTag("service-schedule-days").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("保存服务计划").assertIsNotEnabled()
        compose.onNodeWithTag("service-schedule-days").performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("保存服务计划").performClick()
        assertEquals(detail.periods.single().id, submitted?.first)
        assertEquals(3, submitted?.second)
        assertEquals(DayOfWeek.entries.toSet(), submitted?.third)
        // Submit deliberately leaves the editor open; the parent closes only on success.
        compose.onNodeWithTag("service-schedule-days").assert(hasText("3"))
    }

    @Test fun switchingPeriodRetainsEachDraftAndContinuousUsesNullDays() {
        val detail = activeHostingDetailForUi()
        val current = detail.periods.single()
        val future = current.copy(id = "future", periodIndex = 1,
            startDate = current.endDate.plusDays(1), endDate = current.endDate.plusDays(30))
        var submitted: Triple<String, Int?, Set<DayOfWeek>>? = null
        compose.setContent {
            DaiganRecordTheme {
                ServiceScheduleEditDialog(detail.copy(periods = listOf(future, current)), false, {}, { id, days, weekdays ->
                    submitted = Triple(id, days, weekdays)
                })
            }
        }
        compose.onNodeWithText("累计服务日").performScrollTo().performClick()
        compose.onNodeWithTag("service-schedule-days").performScrollTo().performTextReplacement("23")
        compose.onNodeWithText("未来期 · 第 2 期").performScrollTo().performClick()
        compose.onNodeWithText("保存服务计划").performClick()
        assertEquals(Triple("future", null, emptySet<DayOfWeek>()), submitted)
        compose.onNodeWithText("当前期 · 第 1 期").performScrollTo().performClick()
        compose.onNodeWithTag("service-schedule-days").assert(hasText("23"))
    }
}
