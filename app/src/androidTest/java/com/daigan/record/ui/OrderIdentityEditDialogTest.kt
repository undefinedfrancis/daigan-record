package com.daigan.record.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.feature.orders.OrderIdentityEditDialog
import com.daigan.record.testing.*
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrderIdentityEditDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unusedOrderCanSelectHumanReadableAccountAndHostingTypeWithoutDismissal() {
        val detail = shortOrderDetailForUi()
        val targetOwner = owner(id = "owner-b", number = 2, nickname = "另一老板")
        val target = account(id = "target", ownerId = "owner-b", gameName = "新游戏", uid = "UID998")
        var savedAccount: String? = null
        var savedType: OrderType? = null
        var dismissals = 0
        compose.setContent {
            DaiganRecordTheme {
                OrderIdentityEditDialog(detail, listOf(OwnerSummary(targetOwner, listOf(target))), false,
                    { dismissals++ }, { id, type, _, _ -> savedAccount = id; savedType = type })
            }
        }
        compose.onNodeWithText("B002 · 另一老板 · 新游戏 · 官服 · UID UID998").performScrollTo().performClick()
        compose.onNodeWithText("托管订单").performScrollTo().performClick()
        compose.onNodeWithText("保存账号与类型").performClick()
        assertEquals("target", savedAccount)
        assertEquals(OrderType.HOSTING, savedType)
        assertEquals(0, dismissals)
    }
    @Test fun completedTaskHistoryDisablesReclassificationAndExplainsWhy() {
        val detail = shortOrderDetailForUi()
        val recorded = detail.copy(tasks = detail.tasks.map { it.copy(completedAt = fixedInstant) })
        compose.setContent {
            DaiganRecordTheme {
                OrderIdentityEditDialog(recorded, listOf(OwnerSummary(detail.owner, listOf(detail.account))),
                    false, {}, { _, _, _, _ -> fail("历史订单不得提交身份更正") })
            }
        }
        compose.onNodeWithText("保存账号与类型").assertIsNotEnabled()
        compose.onNodeWithText("此订单已有历史，不能移动账号或更改类型。").assertExists()
    }
}
