package com.daigan.record.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.feature.orders.OrderMaintenanceActions
import com.daigan.record.feature.orders.UnusedOrderCleanupRow
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.shortOrderDetailForUi
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-030: the management section offers exactly 修改 and 作废订单. Mistaken-order cleanup
 * moved outside it, so it is asserted separately.
 */
@RunWith(AndroidJUnit4::class)
class OrderMaintenanceUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showActions(
        detail: OrderDetail = shortOrderDetailForUi(),
        busy: Boolean = false,
        onEdit: () -> Unit = {},
        onCancel: () -> Unit = {},
        onDeleteCancelled: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                OrderMaintenanceActions(
                    detail = detail,
                    onEdit = onEdit,
                    onCancel = onCancel,
                    onDeleteCancelled = onDeleteCancelled,
                    busy = busy,
                )
            }
        }
    }

    private fun showCleanup(
        detail: OrderDetail = shortOrderDetailForUi(),
        busy: Boolean = false,
        onDelete: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                UnusedOrderCleanupRow(detail = detail, busy = busy, onDelete = onDelete)
            }
        }
    }

    @Test
    fun managementOffersOneEditorAndCancellationOnly() {
        var edits = 0
        showActions(onEdit = { edits++ })
        composeTestRule.onNodeWithText("修改").assertIsEnabled().performClick()
        assertEquals(1, edits)
        composeTestRule.onNodeWithText("作废订单").assertExists()
        // No per-field entries are flattened here any more.
        composeTestRule.onNodeWithText("修改名称").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改备注").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改价格").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改任务").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改日期").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改刷新规则").assertDoesNotExist()
        composeTestRule.onNodeWithText("修改服务计划").assertDoesNotExist()
        composeTestRule.onNodeWithText("更正所属账号与订单类型").assertDoesNotExist()
        composeTestRule.onNodeWithText("删除误建订单").assertDoesNotExist()
    }

    @Test
    fun deletingAMistakenOrderRequiresConfirmationAndCanBeDismissed() {
        var deletes = 0
        showCleanup(onDelete = { deletes++ })
        composeTestRule.onNodeWithText("删除误建订单").performClick()
        assertEquals(0, deletes)
        composeTestRule.onNodeWithText("取消").performClick()
        assertEquals(0, deletes)
        composeTestRule.onNodeWithText("删除误建订单").performClick()
        composeTestRule.onNodeWithText("确认删除").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun cancellationRequiresConfirmationAndCanBeDismissed() {
        var cancellations = 0
        showActions(onCancel = { cancellations++ })
        composeTestRule.onNodeWithText("作废订单").performClick()
        assertEquals(0, cancellations)
        composeTestRule.onNodeWithText("取消").performClick()
        assertEquals(0, cancellations)
        composeTestRule.onNodeWithText("作废订单").performClick()
        composeTestRule.onNodeWithText("确认作废").performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun recordedProgressPreventsCleanupButStillAllowsCancellation() {
        val detail = shortOrderDetailForUi()
        val recorded = detail.copy(tasks = detail.tasks.map { it.copy(completedAt = fixedInstant) })
        showActions(recorded)
        composeTestRule.onNodeWithText("作废订单").assertIsEnabled()
        composeTestRule.onNodeWithText("删除误建订单").assertDoesNotExist()
    }

    @Test
    fun cancelledOrderExplainsItsReadOnlyStateAndOffersNoMaintenanceActions() {
        val detail = shortOrderDetailForUi()
        showActions(detail.copy(order = detail.order.copy(cancelledAt = fixedInstant)))
        composeTestRule.onNodeWithText("订单已作废").assertExists()
        composeTestRule.onNodeWithText("修改").assertDoesNotExist()
        composeTestRule.onNodeWithText("删除误建订单").assertDoesNotExist()
        composeTestRule.onNodeWithText("作废订单").assertDoesNotExist()
    }

    @Test
    fun pendingOperationDisablesTheEditorAndCancellation() {
        showActions(busy = true)
        composeTestRule.onNodeWithText("修改").assertIsNotEnabled()
        composeTestRule.onNodeWithText("作废订单").assertIsNotEnabled()
    }
}
