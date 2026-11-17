package com.daigan.record.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.feature.orders.TaskEditDialog
import com.daigan.record.testing.activeHostingDetailForUi
import com.daigan.record.testing.expiredHostingDetailForUi
import com.daigan.record.testing.shortOrderDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskEditorCorrectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun correctionRetainsStableTaskIdentityAndInputAfterSubmission() {
        val detail = shortOrderDetailForUi()
        var submitted = emptyList<TaskDraft>()
        compose.setContent { DaiganRecordTheme { TaskEditDialog(detail, false, {}, { submitted = it }) } }
        compose.onNodeWithText("任务名称，例如：每日签到").performTextReplacement("更正名称")
        compose.onNodeWithText("保存").performClick()
        assertEquals("task-short", submitted.single().id)
        assertNull(submitted.single().periodId)
        compose.onNodeWithText("更正名称").assertExists()
    }

    @Test fun retiredDefinitionsAreExcludedAndNewTaskHasNoIdentity() {
        val base = shortOrderDetailForUi()
        val detail = base.copy(tasks = base.tasks + base.tasks.single().copy(id = "old", title = "历史定义", retiredAt = base.now))
        var submitted = emptyList<TaskDraft>()
        compose.setContent { DaiganRecordTheme { TaskEditDialog(detail, false, {}, { submitted = it }) } }
        compose.onNodeWithText("历史定义").assertDoesNotExist()
        compose.onNodeWithText("添加任务").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(2, submitted.size)
        assertNull(submitted.last().id)
    }

    @Test fun futurePeriodSelectionDoesNotSubmitCurrentOrEndedTaskDefinitions() {
        val base = activeHostingDetailForUi()
        val current = base.periods.single()
        val future = current.copy(id = "future", periodIndex = 1, startDate = current.endDate.plusDays(1), endDate = current.endDate.plusDays(30))
        val ended = current.copy(id = "ended", periodIndex = -1, startDate = current.startDate.minusDays(30), endDate = current.startDate.minusDays(1))
        val detail = base.copy(periods = listOf(future, current, ended), tasks = base.tasks +
            base.tasks.single().copy(id = "future-task", periodId = future.id, title = "未来任务"))
        var submitted = emptyList<TaskDraft>()
        compose.setContent { DaiganRecordTheme { TaskEditDialog(detail, false, {}, { submitted = it }) } }
        compose.onNodeWithText("未来期 · 第 2 期").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("future-task", submitted.single().id)
        assertEquals("future", submitted.single().periodId)
        compose.onNodeWithText("当前期 · 第 0 期").assertDoesNotExist()
    }

    @Test fun monthlyDatesAndNumericDefinitionArePreservedInEditableFields() {
        val base = activeHostingDetailForUi()
        val detail = base.copy(tasks = listOf(base.tasks.single().copy(
            progressType = com.daigan.record.data.model.ProgressType.NUMERIC,
            recurrence = com.daigan.record.data.model.RecurrenceType.MONTHLY,
            monthDays = setOf(1, 15), startValue = java.math.BigDecimal("2"),
            targetValue = java.math.BigDecimal("10"), unit = "次")))
        var submitted = emptyList<TaskDraft>()
        compose.setContent { DaiganRecordTheme { TaskEditDialog(detail, false, {}, { submitted = it }) } }
        compose.onNodeWithText("起始值").performScrollTo().assertExists()
        compose.onNodeWithText("每月日号（如 1,15；当月无该日跳过）").performScrollTo()
            .performTextReplacement("1,15,25")
        compose.onNodeWithText("保存").performClick()
        assertEquals("1,15,25", submitted.single().monthDay)
        assertEquals("2", submitted.single().startValue)
        assertEquals("10", submitted.single().targetValue)
        assertEquals("次", submitted.single().unit)
    }

    @Test fun endedHostingPeriodCannotBeEdited() {
        compose.setContent { DaiganRecordTheme { TaskEditDialog(expiredHostingDetailForUi(), false, {}, {}) } }
        compose.onNodeWithText("没有可修改的当前或未来期次；历史任务可在订单记录中查看。").assertExists()
        compose.onNodeWithText("添加任务").assertDoesNotExist()
    }
}
