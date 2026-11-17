package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.feature.today.TodayColorFilter
import com.daigan.record.feature.today.TodayScreen
import com.daigan.record.feature.today.TodayTab
import com.daigan.record.feature.today.TodayUiState
import com.daigan.record.testing.hostingOrderForToday
import com.daigan.record.testing.shortWorkOrderForToday
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        state: TodayUiState,
        onOpenOrder: (String) -> Unit = {},
        onCompleteAll: () -> Unit = {},
        onSetColorFilter: (TodayColorFilter) -> Unit = {},
        onSetTaskGroup: (String, String?) -> Unit = { _, _ -> },
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                TodayScreen(
                    uiState = state,
                    onSelectTab = {},
                    onOpenSettings = {},
                    onOpenOrder = onOpenOrder,
                    onCompleteAll = onCompleteAll,
                    onCompleteOrder = {},
                    onToggleHostingTask = { taskId, occurrenceKey, completed ->
                        toggleCalls += Triple(taskId, occurrenceKey, completed)
                    },
                    onUpdateHostingProgress = { taskId, occurrenceKey, value ->
                        progressCalls += Triple(taskId, occurrenceKey, value)
                    },
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onUndo = {},
                    onClearUndo = {},
                    onToggleCompleted = {},
                    onSetColorFilter = onSetColorFilter,
                    onSetTaskGroup = onSetTaskGroup,
                )
            }
        }
    }

    private val toggleCalls = mutableListOf<Triple<String, String, Boolean>>()
    private val progressCalls = mutableListOf<Triple<String, String, String>>()

    @Test
    fun hostingToggleCarriesTheDisplayedOccurrenceKey() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(hostingOrderForToday()),
            ),
        )
        // Details only exist after the unit is opened (UF-029).
        composeTestRule.onNodeWithTag("today-hosting-header-hosting-1").performClick()
        composeTestRule.onNode(hasClickAction() and hasText("每日签到"))
            .performClick()

        assertEquals(
            listOf(Triple("task-1", "D:2026-10-17", true)),
            toggleCalls,
        )
    }

    @Test
    fun emptyHostingStatePointsAtTheAccountContext() {
        setContent(TodayUiState(dateLabel = "10 月 17 日", loading = false))
        composeTestRule.onNodeWithText("还没有托管打卡").assertExists()
    }

    @Test
    fun emptyShortTaskStatePointsAtTheAccountContext() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                selectedTab = TodayTab.SHORT_TASK,
            ),
        )
        composeTestRule.onNodeWithText("还没有短期任务").assertExists()
    }

    @Test
    fun taskUnitsAreCollapsedByDefaultWithTitleStatusAndAccessibleState() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(hostingOrderForToday()),
            ),
        )

        // Title and completion status stay visible; the body does not.
        composeTestRule.onNodeWithText("日常托管").assertExists()
        composeTestRule.onNodeWithText("待完成").assertExists()
        composeTestRule.onNodeWithText("每日签到").assertDoesNotExist()
        composeTestRule.onNodeWithText("整单完成").assertDoesNotExist()
        composeTestRule.onNodeWithTag("today-hosting-header-hosting-1")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
            )
    }

    @Test
    fun expandingRevealsDetailsAndCollapsingHidesThemAgain() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(hostingOrderForToday()),
            ),
        )
        val header = composeTestRule.onNodeWithTag("today-hosting-header-hosting-1")

        header.performClick()
        composeTestRule.onNodeWithText("每日签到").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )

        header.performClick()
        composeTestRule.onNodeWithText("每日签到").assertDoesNotExist()
        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        )
    }

    @Test
    fun expandingAUnitNeverCompletesOrOpensAnything() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(hostingOrderForToday()),
            ),
        )

        composeTestRule.onNodeWithTag("today-hosting-header-hosting-1").performClick()
        composeTestRule.onNodeWithTag("today-hosting-header-hosting-1").performClick()

        assertTrue(toggleCalls.isEmpty())
        assertTrue(progressCalls.isEmpty())
    }

    @Test
    fun expansionFollowsOrderIdentityWhenTheListRefreshes() {
        val first = hostingOrderForToday(orderId = "hosting-a", title = "甲单", taskTitle = "甲任务")
        val second = hostingOrderForToday(orderId = "hosting-b", title = "乙单", taskTitle = "乙任务")
        val hosting = mutableStateOf(listOf(first, second))
        composeTestRule.setContent {
            DaiganRecordTheme {
                TodayScreen(
                    uiState = TodayUiState(
                        dateLabel = "10 月 17 日",
                        loading = false,
                        hosting = hosting.value,
                    ),
                    onSelectTab = {},
                    onOpenSettings = {},
                    onOpenOrder = {},
                    onCompleteAll = {},
                    onCompleteOrder = {},
                    onToggleHostingTask = { _, _, _ -> },
                    onUpdateHostingProgress = { _, _, _ -> },
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onUndo = {},
                    onClearUndo = {},
                    onToggleCompleted = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("today-hosting-header-hosting-a").performClick()
        composeTestRule.onNodeWithText("甲任务").assertExists()

        // The opened unit leaves the refresh result: the remaining unit must stay closed.
        composeTestRule.runOnIdle { hosting.value = listOf(second) }
        composeTestRule.onNodeWithText("乙任务").assertDoesNotExist()
        composeTestRule.onNodeWithTag("today-hosting-header-hosting-b")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
            )
    }

    @Test
    fun activeOrdersShowTasksWhileCompletedOrdersStayCollapsed() {
        val active = hostingOrderForToday(orderId = "active")
        val completed = hostingOrderForToday(
            orderId = "completed",
            title = "已完成的单",
            allCompleted = true,
        )
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(active, completed),
            ),
        )

        composeTestRule.onNodeWithText("日常托管").assertExists()
        composeTestRule.onNodeWithTag("today-hosting-header-active").performClick()
        composeTestRule.onNodeWithText("每日签到").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        composeTestRule.onNodeWithText("已完成 · 1 单").assertExists()
        composeTestRule.onNodeWithText("已完成的单").assertDoesNotExist()
    }

    @Test
    fun expandedCompletedSectionShowsCompletedCards() {
        val completed = hostingOrderForToday(
            orderId = "completed",
            title = "已完成的单",
            allCompleted = true,
        )
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(completed),
                completedExpanded = true,
            ),
        )
        composeTestRule.onNodeWithText("已完成 · 1 单").assertExists()
        composeTestRule.onNodeWithText("已完成的单").assertExists()
    }

    @Test
    fun expiredHostingStaysOutOfDailyWorkAndDoesNotOfferCompletion() {
        val expired = hostingOrderForToday(status = HostingStatus.EXPIRED)
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(expired),
            ),
        )
        composeTestRule.onNodeWithText("已到期 · 待续期").assertDoesNotExist()
        composeTestRule.onNodeWithText("查看并续期").assertDoesNotExist()
        composeTestRule.onNodeWithText("整单完成").assertDoesNotExist()
    }

    @Test
    fun overdueShortTasksAreHiddenUntilDiscoverableSectionExpanded() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                selectedTab = TodayTab.SHORT_TASK,
                shortOrders = listOf(shortWorkOrderForToday()),
            ),
        )
        composeTestRule.onNodeWithText("已逾期 2 天 · 2026.10.15 截止").assertDoesNotExist()
        composeTestRule.onNodeWithText("逾期事项 · 1 单").performClick()
        composeTestRule.onNodeWithTag("today-short-header-short-1").performClick()
        composeTestRule.onNodeWithText("已逾期 2 天 · 2026.10.15 截止").assertExists()
        composeTestRule.onNodeWithText("约定总价 ¥100.00").assertExists()
        composeTestRule.onNodeWithText("订单详情").assertExists()
    }

    @Test
    fun shortUnitCollapsedHidesDeadlineTasksAndPrice() {
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                selectedTab = TodayTab.SHORT_TASK,
                shortOrders = listOf(shortWorkOrderForToday(deadline = null)),
            ),
        )
        // Collapsed: status tag only, no business content.
        composeTestRule.onNodeWithText("无期限").assertExists()
        composeTestRule.onNodeWithText("约定总价 ¥100.00").assertDoesNotExist()
        composeTestRule.onNodeWithText("推进主线").assertDoesNotExist()

        composeTestRule.onNodeWithTag("today-short-header-short-1").performClick()
        composeTestRule.onNodeWithText("无交付期限").assertExists()
        composeTestRule.onNodeWithText("推进主线").assertExists()
        composeTestRule.onNodeWithText("约定总价 ¥100.00").assertExists()
    }

    @Test
    fun noDeadlineShortTasksRemainVisibleAndGroupIsRecognizable() {
        val open = shortWorkOrderForToday(deadline = null)
        setContent(TodayUiState(loading = false, selectedTab = TodayTab.SHORT_TASK,
            shortOrders = listOf(open), groups = mapOf(open.accountId to "优先处理")))
        composeTestRule.onNodeWithTag("today-short-header-short-1").performClick()
        composeTestRule.onNodeWithText("无交付期限").assertExists()
        composeTestRule.onNodeWithText("分组 · 优先处理").assertExists()
        composeTestRule.onNodeWithText("逾期事项 · 1 单").assertDoesNotExist()
    }

    @Test
    fun colorChipsFilterOrdersWithoutOpeningAColorDialog() {
        val red = hostingOrderForToday(orderId = "red")
        val blue = hostingOrderForToday(orderId = "blue", title = "蓝色单")
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(
                    red.copy(order = red.order.copy(colorArgb = 0xFFE53935.toInt())),
                    blue.copy(order = blue.order.copy(colorArgb = 0xFF1E88E5.toInt())),
                ),
                colorFilter = TodayColorFilter.Matched(0xFF1E88E5.toInt()),
            ),
        )
        // The collapsed title keeps the colour filter working: filtered-out units vanish.
        composeTestRule.onNodeWithText("蓝色单").assertExists()
        composeTestRule.onNodeWithText("日常托管").assertDoesNotExist()
        // "全部" is the reset path and colour assignment no longer exists on this page.
        composeTestRule.onNodeWithText("全部").assertExists()
        composeTestRule.onNodeWithText("自动配色").assertDoesNotExist()
        composeTestRule.onNodeWithText("自定义").assertDoesNotExist()
    }

    @Test
    fun colorFilterSelectionIsReportedToTheViewModel() {
        val selections = mutableListOf<TodayColorFilter>()
        val order = hostingOrderForToday()
        setContent(
            TodayUiState(
                dateLabel = "10 月 17 日",
                loading = false,
                hosting = listOf(order.copy(order = order.order.copy(colorArgb = 0xFF43A047.toInt()))),
            ),
            onSetColorFilter = { selections += it },
        )
        composeTestRule.onNodeWithText("绿").performClick()
        composeTestRule.onNodeWithText("未着色").performClick()
        assertEquals(
            listOf<TodayColorFilter>(
                TodayColorFilter.Matched(0xFF43A047.toInt()),
                TodayColorFilter.Uncolored,
            ),
            selections,
        )
    }

    @Test
    fun groupingDialogKeepsWorkingWithoutColourAssignment() {
        val groups = mutableListOf<Pair<String, String?>>()
        setContent(
            TodayUiState(loading = false, hosting = listOf(hostingOrderForToday())),
            onSetTaskGroup = { account, group -> groups += account to group },
        )
        composeTestRule.onNodeWithText("分组").performClick()
        composeTestRule.onNodeWithText("小满 · B001 · 重返未来：1999 · 日常托管").assertExists()
        composeTestRule.onNodeWithText("淡蓝").assertDoesNotExist()
        composeTestRule.onNodeWithText("分组（可留空取消）").performTextReplacement("优先处理")
        composeTestRule.onNodeWithText("保存").performClick()
        assertEquals(listOf("account-1" to "优先处理"), groups)
    }

    @Test
    fun longOrderContentStaysReachableAtDoubleFontScale() {
        val longTitle = "超长托管订单名称".repeat(6)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    TodayScreen(
                        uiState = TodayUiState(
                            dateLabel = "10 月 17 日",
                            loading = false,
                            hosting = listOf(
                                hostingOrderForToday(title = longTitle, taskTitle = "超长任务".repeat(8)),
                            ),
                        ),
                        onSelectTab = {},
                        onOpenSettings = {},
                        onOpenOrder = {},
                        onCompleteAll = {},
                        onCompleteOrder = {},
                        onToggleHostingTask = { taskId, occurrenceKey, completed ->
                            toggleCalls += Triple(taskId, occurrenceKey, completed)
                        },
                        onUpdateHostingProgress = { taskId, occurrenceKey, value ->
                            progressCalls += Triple(taskId, occurrenceKey, value)
                        },
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onUndo = {},
                        onClearUndo = {},
                        onToggleCompleted = {},
                        onSetColorFilter = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithText(longTitle).assertExists()
        composeTestRule.onNodeWithTag("today-hosting-header-hosting-1").performClick()
        composeTestRule.onNodeWithText("整单完成").assertExists()
    }
}
