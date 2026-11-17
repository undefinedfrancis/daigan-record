package com.daigan.record.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.feature.accounts.AccountsScreen
import com.daigan.record.feature.accounts.OwnersUiState
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout tests for the owner list: empty state, collapsed units and long text. */
@RunWith(AndroidJUnit4::class)
class AccountsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyStateIsHonestAndHasOnlyTheOwnerCreationAction() {
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = emptyList(), archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithText("还没有老板资料").assertExists()
        composeTestRule.onNodeWithText("新增").assertExists()
        composeTestRule.onNodeWithText("归档记录").assertExists()
    }

    @Test
    fun ownerUnitIsCollapsedByDefaultAndExpandsOnTap() {
        val summary = OwnerSummary(
            owner = owner(nickname = "小满"),
            accounts = listOf(account(id = "a1", gameName = "重返未来：1999")),
        )

        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = listOf(summary), archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        val header = composeTestRule.onNodeWithTag("owner-header-owner-1")
        composeTestRule.onNodeWithText("小满").assertExists()
        composeTestRule.onNodeWithText("B001").assertExists()
        composeTestRule.onNodeWithText("1 个游戏账号 · 重返未来：1999").assertDoesNotExist()
        composeTestRule.onNodeWithText("查看账号").assertDoesNotExist()
        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        )

        header.performClick()
        composeTestRule.onNodeWithText("1 个游戏账号 · 重返未来：1999").assertExists()
        composeTestRule.onNodeWithText("查看账号").assertExists()
        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        )

        header.performClick()
        composeTestRule.onNodeWithText("查看账号").assertDoesNotExist()
    }

    @Test
    fun expandedOwnerUnitStillOpensTheOwnerDetails() {
        val opened = mutableListOf<String>()
        val summary = OwnerSummary(
            owner = owner(nickname = "小满"),
            accounts = listOf(account(id = "a1", gameName = "重返未来：1999")),
        )

        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(listOf(summary), archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = { opened += it },
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("owner-header-owner-1").performClick()
        composeTestRule.onNodeWithText("查看账号").performClick()
        assertEquals(listOf("owner-1"), opened)
    }

    @Test
    fun ownerCardGroupsAccountsAndShowsTheImmutableNumber() {
        val summary = OwnerSummary(
            owner = owner(nickname = "小满"),
            accounts = listOf(
                account(id = "a1", gameName = "重返未来：1999"),
                account(id = "a2", gameName = "鸣潮", uid = "10002136"),
            ),
        )

        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = listOf(summary), archivedCount = 2),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithText("小满").assertExists()
        composeTestRule.onNodeWithText("B001").assertExists()
        composeTestRule.onNodeWithTag("owner-header-owner-1").performClick()
        composeTestRule.onNodeWithText("2 个游戏账号 · 重返未来：1999 / 鸣潮").assertExists()
        composeTestRule.onNodeWithText("归档记录 (2)").assertExists()
    }

    @Test
    fun expandedStateStaysWithItsOwnerWhenTheListRefreshes() {
        val first = OwnerSummary(owner(nickname = "小满"), listOf(account(id = "a1")))
        val second = OwnerSummary(
            owner = owner(id = "owner-2", number = 2, nickname = "阿岚"),
            listOf(account(id = "a2", ownerId = "owner-2", gameName = "鸣潮")),
        )
        val owners = mutableStateOf(listOf(first, second))

        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners.value, archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = {},
                    onCreateOwner = {},
                    onOpenArchived = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("owner-header-owner-1").performClick()
        composeTestRule.onNodeWithText("1 个游戏账号 · 重返未来：1999").assertExists()

        // Refresh removes the opened owner: the remaining owner must stay collapsed.
        composeTestRule.runOnIdle { owners.value = listOf(second) }
        composeTestRule.onNodeWithText("1 个游戏账号 · 鸣潮").assertDoesNotExist()
        composeTestRule.onNodeWithTag("owner-header-owner-2")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
            )
    }

    @Test
    fun longChineseNicknameStaysReadableAtLargeFontScale() {
        val longNickname = "超长老板昵称".repeat(5) // 30 Chinese characters
        val summary = OwnerSummary(
            owner = owner(nickname = longNickname),
            accounts = listOf(
                account(gameName = "重返未来：1999".repeat(3), uid = "1000062810000628"),
            ),
        )

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 2f),
            ) {
                DaiganRecordTheme {
                    AccountsScreen(
                        uiState = OwnersUiState.Success(listOf(summary), archivedCount = 0),
                        onOpenSettings = {},
                        onOpenOwner = {},
                        onCreateOwner = {},
                        onOpenArchived = {},
                    )
                }
            }
        }

        // Collapsed shows the full name in semantics even when it wraps/ellipsises.
        composeTestRule.onNodeWithTag("owner-header-owner-1").assertExists()
        composeTestRule.onNodeWithText(longNickname).assertExists()
        composeTestRule.onNodeWithText("新增").assertExists()
    }

    @Test
    fun unfinishedOrderBadgeDisappearsWhenAllOrdersFinish() {
        val summary = mutableStateOf(
            OwnerSummary(owner(nickname = "小满"), emptyList(), unfinishedOrderCount = 2),
        )
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(listOf(summary.value), 0),
                    onOpenSettings = {}, onOpenOwner = {}, onCreateOwner = {}, onOpenArchived = {},
                )
            }
        }
        // The badge stays in the collapsed header; the count is part of the details.
        composeTestRule.onNodeWithContentDescription("有未完成订单").assertExists()
        composeTestRule.onNodeWithTag("owner-header-owner-1").performClick()
        composeTestRule.onNodeWithText("进行中 · 2 单").assertExists()
        composeTestRule.runOnIdle { summary.value = summary.value.copy(unfinishedOrderCount = 0) }
        composeTestRule.onNodeWithContentDescription("有未完成订单").assertDoesNotExist()
        composeTestRule.onNodeWithText("进行中 · 2 单").assertDoesNotExist()
        composeTestRule.onNodeWithText("小满").assertExists()
    }
}
