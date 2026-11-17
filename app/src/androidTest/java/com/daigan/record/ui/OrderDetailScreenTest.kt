package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.feature.orders.OrderDetailContent
import com.daigan.record.testing.activeHostingDetailForUi
import com.daigan.record.testing.expiredHostingDetailForUi
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.shortOrderDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val toggleCalls = mutableListOf<Triple<String, String, Boolean>>()
    private val calendarCalls = mutableListOf<List<Any>>()
    private val progressCalls = mutableListOf<Triple<String, String, String>>()

    private fun setContent(detail: com.daigan.record.data.model.OrderDetail) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                OrderDetailContent(
                    detail = detail,
                    lastBatchId = null,
                    undoResult = null,
                    onBack = {},
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onCompleteShortOrder = {},
                    onToggleOccurrence = { taskId, occurrenceKey, completed ->
                        toggleCalls += Triple(taskId, occurrenceKey, completed)
                    },
                    onUpdateOccurrenceProgress = { taskId, occurrenceKey, value ->
                        progressCalls += Triple(taskId, occurrenceKey, value)
                    },
                    onCalendarCompletion = { period, task, date, key, completed ->
                        calendarCalls += listOf(period, task, date, key, completed)
                    },
                    onCompleteHostingOrder = {},
                    onUndo = {},
                    onClearUndoResult = {},
                    onAddAttachment = { _, _, _ -> },
                    onReplaceAttachment = { _, _ -> },
                    onDeleteAttachment = {},
                    fileFor = { File("/does-not-exist") },
                    onBackfill = {},
                    onRenewClick = {},
                    onRecordPayment = {},
                    onAmendReceipt = {},
                    onVoidReceipt = {},
                    paymentInFlight = false,
                )
            }
        }
    }

    @Test
    fun headerManagementExposesOneEditorAndCleanupStaysOutsideIt() {
        setContent(shortOrderDetailForUi())
        composeTestRule.onNodeWithText("修改").assertDoesNotExist()
        composeTestRule.onNodeWithText("管理").performClick()
        // UF-030: only 修改 + 作废订单 live in the management section.
        composeTestRule.onNodeWithText("修改").assertExists()
        composeTestRule.onNodeWithText("作废订单").assertExists()
        composeTestRule.onNodeWithText("修改日期").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改名称").assertDoesNotExist()
    }

    @Test
    fun orderColourRowIsDirectlyClickableAtTheTop() {
        val colors = mutableListOf<Int?>()
        composeTestRule.setContent {
            DaiganRecordTheme {
                OrderDetailContent(
                    detail = shortOrderDetailForUi(),
                    lastBatchId = null,
                    undoResult = null,
                    onBack = {},
                    onToggleShortTask = { _, _ -> },
                    onUpdateShortProgress = { _, _ -> },
                    onCompleteShortOrder = {},
                    onToggleOccurrence = { _, _, _ -> },
                    onUpdateOccurrenceProgress = { _, _, _ -> },
                    onCompleteHostingOrder = {},
                    onUndo = {},
                    onClearUndoResult = {},
                    onAddAttachment = { _, _, _ -> },
                    onReplaceAttachment = { _, _ -> },
                    onDeleteAttachment = {},
                    fileFor = { File("/does-not-exist") },
                    onBackfill = {},
                    onRenewClick = {},
                    onRecordPayment = {},
                    onAmendReceipt = {},
                    onVoidReceipt = {},
                    paymentInFlight = false,
                    onSetOrderColor = { colors += it },
                )
            }
        }
        composeTestRule.onNodeWithTag("order-color-row").assertExists()
        composeTestRule.onNodeWithContentDescription("订单颜色红").performClick()
        composeTestRule.onNodeWithTag("order-color-${0xFF1E88E5.toInt()}").performClick()
        assertEquals(listOf<Int?>(0xFFE53935.toInt(), 0xFF1E88E5.toInt()), colors)
    }

    @Test
    fun hostingCalendarToggleCarriesTheDisplayedOccurrenceKey() {
        // An active period: an expired order's history is intentionally read-only, so it
        // has no toggle to carry an identity.
        setContent(activeHostingDetailForUi())

        // 日历详情行在首屏之下：先滚动到可见区域，否则点击落在屏幕外会被丢弃。
        composeTestRule.onNode(hasClickAction() and hasText("每日签到"))
            .performScrollTo()
            .performClick()

        assertEquals("D:2026-10-17", calendarCalls.single()[3])
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun hostingCalendarShowsSelectedGameDayAndUsesItsOccurrenceKey() {
        setContent(activeHostingDetailForUi())

        composeTestRule.onNodeWithTag("hosting-calendar-day-2026-10-17").assertExists()
        composeTestRule.onNodeWithText("2026-10-17 · 0/1").assertExists()
        composeTestRule.onNode(
            hasClickAction() and hasText("每日签到") and hasAnyAncestor(hasTestTag("hosting-calendar")),
        ).performScrollTo().performClick()

        assertEquals("D:2026-10-17", calendarCalls.single()[3])
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun calendarPastDateUsesHistoricalCompletionInsteadOfTodayWrite() {
        val detail = activeHostingDetailForUi()
        setContent(detail)
        val date = detail.today.minusDays(1)
        composeTestRule.onNodeWithTag("hosting-calendar-day-$date").performClick()
        composeTestRule.onNode(
            hasClickAction() and hasText("每日签到") and hasAnyAncestor(hasTestTag("hosting-calendar")),
        ).performScrollTo().performClick()
        assertEquals(
            listOf(detail.currentPeriod!!.id, "task-hosting", date, "D:$date", true),
            calendarCalls.single(),
        )
        assertTrue(toggleCalls.isEmpty())
    }

    @Test
    fun hostingCalendarRemainsReadableAtOnePointFourFontScale() {
        val detail = activeHostingDetailForUi()
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.4f),
            ) {
                DaiganRecordTheme {
                    OrderDetailContent(
                        detail = detail,
                        lastBatchId = null,
                        undoResult = null,
                        onBack = {},
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onCompleteShortOrder = {},
                        onToggleOccurrence = { _, _, _ -> },
                        onUpdateOccurrenceProgress = { _, _, _ -> },
                        onCompleteHostingOrder = {},
                        onUndo = {},
                        onClearUndoResult = {},
                        onAddAttachment = { _, _, _ -> },
                        onReplaceAttachment = { _, _ -> },
                        onDeleteAttachment = {},
                        fileFor = { File("/does-not-exist") },
                        onBackfill = {},
                        onRenewClick = {},
                        onRecordPayment = {},
                        onAmendReceipt = {},
                        onVoidReceipt = {},
                        paymentInFlight = false,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("hosting-calendar").assertExists()
        composeTestRule.onNodeWithTag("order-detail-list").performScrollToNode(hasText("收款与欠款"))
        composeTestRule.onNodeWithText("收款与欠款").assertExists()
    }

    @Test
    fun emptyHostingHistoryExplainsBatchCompletionDoesNotCreatePastRecords() {
        setContent(activeHostingDetailForUi())

        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasText("本期打卡记录"))
        composeTestRule.onNodeWithText("批量完成不会补写历史遗漏", substring = true).assertExists()
    }

    @Test
    fun shortDetailShowsTasksPriceAndARealPaymentSection() {
        setContent(shortOrderDetailForUi())

        composeTestRule.onNodeWithText("推进主线").assertExists()
        composeTestRule.onNodeWithText("¥100.00").assertExists()
        // "分批收款" appears in both the order summary and the financial card. Scope the
        // checks to those regions instead of deleting the assertion or accepting any one.
        composeTestRule.onNode(
            hasText("分批收款") and hasAnyAncestor(hasTestTag("order-detail-summary")),
        ).assertExists()
        composeTestRule.onNode(
            hasText("分批收款") and hasAnyAncestor(hasTestTag("order-detail-payment-section")),
        ).assertExists()
        composeTestRule.onNodeWithText("整单完成").assertExists()
        composeTestRule.onNodeWithText("收款与欠款").assertExists()
        composeTestRule.onNodeWithText("¥100.00 / ¥0.00 / ¥100.00").assertExists()
        composeTestRule.onNodeWithText("记录收款").assertExists()
        composeTestRule.onNodeWithText("还没有收款记录", substring = true).assertExists()
        composeTestRule.onNodeWithText("添加截图").assertExists()
    }

    @Test
    fun expiredHostingShowsRenewalAndHistoricalCorrection() {
        setContent(expiredHostingDetailForUi())

        // The price is shown in the order summary and the outstanding total is a second
        // identical amount. Both are real; assert each in its own region. The summary is
        // above the fold, so it is asserted before scrolling further down.
        composeTestRule.onNode(
            hasText("¥300.00") and hasAnyAncestor(hasTestTag("order-detail-summary")),
        ).assertExists()
        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasText("已到期 · 待续期"))
        assertTrue(composeTestRule.onAllNodesWithText("已到期 · 待续期").fetchSemanticsNodes().isNotEmpty())

        composeTestRule.onNodeWithTag("order-detail-list").performScrollToNode(hasText("续期"))
        composeTestRule.onNodeWithText("续期").assertExists()
        composeTestRule.onNodeWithText("本期打卡记录").assertExists()
        composeTestRule.onNodeWithText("补录/更正").assertExists()
        composeTestRule.onNodeWithText("¥300.00 / ¥0.00 / ¥300.00").assertExists()
        composeTestRule.onNodeWithText("整单完成").assertDoesNotExist()
        composeTestRule.onNodeWithText("到期后不再生成新任务", substring = true).assertExists()
    }

    @Test
    fun longDetailContentStaysReachableAtDoubleFontScale() {
        val detail = shortOrderDetailForUi()
        val longTitle = "超长订单名称".repeat(8)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    OrderDetailContent(
                        detail = detail.copy(order = detail.order.copy(title = longTitle)),
                        lastBatchId = null,
                        undoResult = null,
                        onBack = {},
                        onToggleShortTask = { _, _ -> },
                        onUpdateShortProgress = { _, _ -> },
                        onCompleteShortOrder = {},
                        onToggleOccurrence = { taskId, occurrenceKey, completed ->
                            toggleCalls += Triple(taskId, occurrenceKey, completed)
                        },
                        onUpdateOccurrenceProgress = { taskId, occurrenceKey, value ->
                            progressCalls += Triple(taskId, occurrenceKey, value)
                        },
                        onCompleteHostingOrder = {},
                        onUndo = {},
                        onClearUndoResult = {},
                        onAddAttachment = { _, _, _ -> },
                        onReplaceAttachment = { _, _ -> },
                        onDeleteAttachment = {},
                        fileFor = { File("/does-not-exist") },
                        onBackfill = {},
                        onRenewClick = {},
                        onRecordPayment = {},
                        onAmendReceipt = {},
                        onVoidReceipt = {},
                        paymentInFlight = false,
                    )
                }
            }
        }
        composeTestRule.onNodeWithText(longTitle).assertExists()
        // 页面顶部还有横向滚动的订单颜色行，全局 hasScrollAction() 会匹配到两个容器；
        // 明确指定主列表，避免歧义。
        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasText("整单完成"))
        composeTestRule.onNodeWithText("整单完成").assertExists()
    }

    /**
     * Regression for the detail-page crash class: a list key built from `taskId + date`
     * is not unique. A weekly task whose previous window still overlaps the first game
     * day of a period produces two occurrences clamped to the same date, and LazyColumn
     * throws on a repeated key.
     */
    @Test
    fun twoOccurrencesOfOneTaskOnTheSameDateRenderWithoutACrash() {
        val base = activeHostingDetailForUi()
        val task = base.tasks.single()
        val day = base.today
        val shared = base.copy(
            occurrences = listOf(
                occurred("occ-closing", task.id, "W:${day.minusDays(7)}", day),
                occurred("occ-opening", task.id, "W:$day", day),
            ),
            attachments = listOf(
                com.daigan.record.data.model.Attachment(
                    id = "att-1",
                    taskDefinitionId = null,
                    occurrenceId = "occ-opening",
                    relativePath = "shot.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 4,
                    createdAt = base.now,
                ),
            ),
        )
        val entries = shared.historyEntries()
        assertEquals(2, entries.size)
        // Evidence that the previous key (taskId + attributedDate) was not unique.
        assertEquals(1, entries.map { "${it.taskId}-${it.attributedDate}" }.toSet().size)
        assertEquals(2, entries.map { it.stableKey }.toSet().size)

        setContent(shared)
        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasTestTag("order-detail-payment-section"))
        composeTestRule.onNodeWithTag("order-detail-payment-section").assertExists()
    }

    /**
     * The reported "crash on scrolling detail to the bottom" could not be reproduced by
     * any single known input, so this walks the whole page twice with the densest data
     * the screens can hold: long names, three periods, receipts, attachments and a long
     * history.
     */
    @Test
    fun denseHostingDetailScrollsTopToBottomTwice() {
        val detail = denseHostingDetail()
        setContent(detail)
        repeat(2) {
            composeTestRule.onNodeWithTag("order-detail-list")
                .performScrollToNode(hasTestTag("order-detail-payment-section"))
            composeTestRule.onNodeWithTag("order-detail-payment-section").assertExists()
            composeTestRule.onNodeWithTag("order-detail-list").performScrollToNode(hasText("本期托管", substring = true))
            composeTestRule.onNodeWithText("本期托管", substring = true).assertExists()
        }
        composeTestRule.onNodeWithTag("order-detail-list")
            .performScrollToNode(hasText("未来期次（已提前续期）"))
        composeTestRule.onNodeWithText("未来期次（已提前续期）").assertExists()
    }

    private fun occurred(
        id: String,
        taskId: String,
        key: String,
        day: java.time.LocalDate,
    ): com.daigan.record.data.model.TaskOccurrence = com.daigan.record.data.model.TaskOccurrence(
        id = id,
        taskDefinitionId = taskId,
        occurrenceKey = key,
        attributedDate = day,
        windowStart = day,
        windowEnd = day,
        completedAt = fixedInstant,
        recordedAt = fixedInstant,
        backfilled = false,
        completionBatchId = null,
        value = null,
        createdAt = fixedInstant,
    )

    private fun denseHostingDetail(): com.daigan.record.data.model.OrderDetail {
        val base = activeHostingDetailForUi()
        val current = base.periods.single()
        val previous = current.copy(
            id = "period-previous",
            periodIndex = 0,
            startDate = current.startDate.minusDays(40),
            endDate = current.startDate.minusDays(1),
        )
        val running = current.copy(periodIndex = 1)
        val future = current.copy(
            id = "period-future",
            periodIndex = 2,
            startDate = running.endDate.plusDays(1),
            endDate = running.endDate.plusDays(30),
        )
        val previousTask = base.tasks.single().copy(id = "task-previous", periodId = previous.id)
        val runningTask = base.tasks.single().copy(id = "task-running", periodId = running.id)
        val futureTask = base.tasks.single().copy(id = "task-future", periodId = future.id)
        val history = (0 until 60).map { index ->
            occurred(
                id = "occ-$index",
                taskId = runningTask.id,
                key = "D:${running.startDate.plusDays(index.toLong())}",
                day = running.startDate.plusDays(index.toLong()),
            )
        } + occurred(
            id = "occ-previous",
            taskId = previousTask.id,
            key = "D:${previous.startDate}",
            day = previous.startDate,
        )
        return base.copy(
            order = base.order.copy(
                title = "超长托管订单名称".repeat(6),
                notes = "备注".repeat(100),
            ),
            tasks = listOf(previousTask, runningTask, futureTask),
            periods = listOf(previous, running, future),
            occurrences = history,
            attachments = listOf(
                com.daigan.record.data.model.Attachment(
                    id = "att-dense",
                    taskDefinitionId = runningTask.id,
                    occurrenceId = "occ-0",
                    relativePath = "missing.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 1024,
                    createdAt = fixedInstant,
                ),
            ),
            receipts = listOf(
                com.daigan.record.data.model.Receipt(
                    id = "receipt-1",
                    operationId = "op-r1",
                    orderId = base.order.id,
                    periodId = running.id,
                    amountCents = 20000,
                    receivedAt = fixedInstant,
                    channel = com.daigan.record.data.model.PaymentChannel.WECHAT,
                    note = "首款",
                    status = com.daigan.record.data.model.ReceiptStatus.ACTIVE,
                    replacedByReceiptId = null,
                    createdAt = fixedInstant,
                ),
            ),
        )
    }
}
