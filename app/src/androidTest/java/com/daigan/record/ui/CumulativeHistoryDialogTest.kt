package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.feature.orders.CumulativePanel
import com.daigan.record.feature.orders.CumulativeProgressDialog
import com.daigan.record.feature.orders.CumulativeSessionOption
import com.daigan.record.ui.theme.DaiganRecordTheme
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3-4 UI: a fully completed order can open read-only history, switch the viewed session
 * explicitly, and still see the correction entry — without a "save new total" field that
 * would write into a completed round.
 */
@RunWith(AndroidJUnit4::class)
class CumulativeHistoryDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sessions = listOf(
        CumulativeSessionOption("session-1", sessionIndex = 1, periodIndex = 0, completed = true),
        CumulativeSessionOption("session-2", sessionIndex = 2, periodIndex = 1, completed = true),
    )

    private fun panel(
        scope: ProgressScope = ProgressScope.Session("session-2"),
        sessionIndex: Int = 2,
        historical: Boolean = true,
    ) = CumulativePanel(
        taskId = "task-1",
        taskTitle = "累计抽数",
        scope = scope,
        targetValue = BigDecimal("100"),
        unit = "抽",
        currentTotal = BigDecimal("100"),
        events = emptyList(),
        sessionIndex = sessionIndex,
        scopeLabel = if (historical) "第 $sessionIndex 次（历史）" else "第 $sessionIndex 次",
        historical = historical,
        sessions = sessions,
    )

    @Test
    fun completedSessionShowsReadOnlyHistoryWithoutTheNewTotalField() {
        var selected: String? = null
        composeTestRule.setContent {
            DaiganRecordTheme {
                CumulativeProgressDialog(
                    panel = panel(),
                    onDismiss = {},
                    onSubmit = {},
                    onCorrect = { _, _ -> },
                    onSelectSession = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithText("累计进度 · 累计抽数（第 2 次（历史））")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("历史轮次只读：新增进度请回到当前轮次；这里可以更正或撤销已有记录。")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("保存当前总值").assertDoesNotExist()

        composeTestRule.onNodeWithText("第 1 次 · 已完成").performClick()
        assertEquals("session-1", selected)
    }

    @Test
    fun activeSessionStillOffersTheTotalEntry() {
        composeTestRule.setContent {
            DaiganRecordTheme {
                CumulativeProgressDialog(
                    panel = panel(
                        scope = ProgressScope.Session("session-2"),
                        historical = false,
                    ),
                    onDismiss = {},
                    onSubmit = {},
                    onCorrect = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithText("保存当前总值").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前累计总值").assertExists()
    }

    @Test
    fun historyStaysReachableAtDoubleFontScale() {
        composeTestRule.setContent {
            DaiganRecordTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    CumulativeProgressDialog(
                        panel = panel(),
                        onDismiss = {},
                        onSubmit = {},
                        onCorrect = { _, _ -> },
                        onSelectSession = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("历史轮次只读：新增进度请回到当前轮次；这里可以更正或撤销已有记录。")
            .assertExists()
        composeTestRule.onNodeWithText("第 1 次 · 已完成").assertExists()
    }
}
