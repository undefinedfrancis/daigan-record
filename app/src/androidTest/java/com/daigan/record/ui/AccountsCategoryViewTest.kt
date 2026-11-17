package com.daigan.record.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountCategory
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.preferences.SharedPreferencesUiPreferenceStore
import com.daigan.record.feature.accounts.AccountDirectoryRow
import com.daigan.record.feature.accounts.AccountsScreen
import com.daigan.record.feature.accounts.OwnersUiState
import com.daigan.record.testing.account
import com.daigan.record.testing.fixedInstant
import com.daigan.record.testing.owner
import com.daigan.record.ui.theme.DaiganRecordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-037 账号视图：跨老板平铺、显示老板身份、按分类筛选，操作始终按 accountId 的
 * ownerId 进入详情。分类只是标签，列表本身不改变归属。
 */
@RunWith(AndroidJUnit4::class)
class AccountsCategoryViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val openedOwners = mutableListOf<String>()
    private val filterChanges = mutableListOf<String?>()
    private val deletedCategories = mutableListOf<String>()
    private val renamedCategories = mutableListOf<Pair<String, String>>()

    private val category = AccountCategory(
        id = "category-1",
        name = "常客",
        sortOrder = 1,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    private fun successState(
        accounts: List<AccountDirectoryRow>,
        categories: List<AccountCategory> = listOf(category),
    ) = OwnersUiState.Success(
        owners = emptyList(),
        archivedCount = 0,
        accounts = accounts,
        categories = categories,
    )

    private fun setContent(
        state: OwnersUiState.Success,
        viewMode: String = SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_ACCOUNT,
        categoryFilter: String? = null,
    ) {
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = state,
                    onOpenSettings = {},
                    onOpenOwner = { openedOwners += it },
                    onCreateOwner = {},
                    onOpenArchived = {},
                    viewMode = viewMode,
                    onViewModeChange = {},
                    categoryFilter = categoryFilter,
                    onCategoryFilterChange = { filterChanges += it },
                    categoryMessage = null,
                    onRenameCategory = { id, name -> renamedCategories += id to name },
                    onDeleteCategory = { deletedCategories += it },
                    onClearCategoryMessage = {},
                )
            }
        }
    }

    @Test
    fun accountViewListsCrossOwnerAccountsWithOwnerIdentityAndCategory() {
        setContent(
            successState(
                accounts = listOf(
                    AccountDirectoryRow(
                        account = account(id = "account-1", gameName = "鸣潮", uid = "1001")
                            .copy(categoryId = category.id),
                        ownerId = "owner-1",
                        ownerNickname = "小满",
                        ownerNumberLabel = "B001",
                        categoryName = category.name,
                    ),
                    AccountDirectoryRow(
                        account = account(id = "account-2", gameName = "原神", uid = "2002"),
                        ownerId = "owner-2",
                        ownerNickname = "阿橙",
                        ownerNumberLabel = "B002",
                        categoryName = null,
                    ),
                ),
            ),
        )

        // 两个老板的账号同时出现，且各自带老板身份与分类标签。
        composeTestRule.onNodeWithText("鸣潮").assertIsDisplayed()
        composeTestRule.onNodeWithText("原神").assertIsDisplayed()
        composeTestRule.onNodeWithText("小满 · B001").assertIsDisplayed()
        composeTestRule.onNodeWithText("阿橙 · B002").assertIsDisplayed()
        composeTestRule.onNode(
            hasText("常客") and hasAnyAncestor(hasTestTag("account-category-filter")),
        ).assertIsDisplayed()
        composeTestRule.onNode(
            hasText("未分类") and hasAnyAncestor(hasTestTag("account-category-filter")),
        ).assertIsDisplayed()

        composeTestRule.onNodeWithTag("account-card-account-2").assertExists()
        // 每行都有自己的入口；用 accountId 定位，避免同名/多行时选错账号。
        composeTestRule.onNode(
            hasText("查看账号详情") and hasAnyAncestor(hasTestTag("account-card-account-2")),
        ).performClick()
        assertEquals(listOf("owner-2"), openedOwners)
    }

    @Test
    fun categoryFilterChipsReportTheChosenFilter() {
        setContent(
            successState(
                accounts = listOf(
                    AccountDirectoryRow(
                        account = account(id = "account-1").copy(categoryId = category.id),
                        ownerId = "owner-1",
                        ownerNickname = "小满",
                        ownerNumberLabel = "B001",
                        categoryName = category.name,
                    ),
                ),
            ),
        )

        composeTestRule.onNode(
            hasText("常客") and hasAnyAncestor(hasTestTag("account-category-filter")),
        ).performClick()
        assertEquals(listOf<String?>(category.id), filterChanges)
    }

    @Test
    fun uncategorizedFilterHidesClassifiedAccounts() {
        setContent(
            successState(
                accounts = listOf(
                    AccountDirectoryRow(
                        account = account(id = "account-1", gameName = "鸣潮")
                            .copy(categoryId = category.id),
                        ownerId = "owner-1",
                        ownerNickname = "小满",
                        ownerNumberLabel = "B001",
                        categoryName = category.name,
                    ),
                    AccountDirectoryRow(
                        account = account(id = "account-2", gameName = "原神"),
                        ownerId = "owner-2",
                        ownerNickname = "阿橙",
                        ownerNumberLabel = "B002",
                        categoryName = null,
                    ),
                ),
            ),
            categoryFilter = UNCATEGORIZED,
        )

        composeTestRule.onNodeWithText("原神").assertIsDisplayed()
        composeTestRule.onNodeWithText("鸣潮").assertDoesNotExist()
    }

    @Test
    fun categoryManagementCanDeleteWithoutTouchingAccounts() {
        setContent(
            successState(
                accounts = listOf(
                    AccountDirectoryRow(
                        account = account(id = "account-1").copy(categoryId = category.id),
                        ownerId = "owner-1",
                        ownerNickname = "小满",
                        ownerNumberLabel = "B001",
                        categoryName = category.name,
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("管理分类").performClick()
        composeTestRule.onNodeWithText("删除").performClick()

        // 删除分类只让账号回到未分类；账号行本身必须仍然存在。
        composeTestRule.onNodeWithTag("account-card-account-1").assertExists()
        assertEquals(listOf(category.id), deletedCategories)
    }

    @Test
    fun ownerViewStaysAvailableAndKeepsOwnerCards() {
        val summary = OwnerSummary(owner(id = "owner-1", nickname = "小满"), emptyList())
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountsScreen(
                    uiState = OwnersUiState.Success(owners = listOf(summary), archivedCount = 0),
                    onOpenSettings = {},
                    onOpenOwner = { openedOwners += it },
                    onCreateOwner = {},
                    onOpenArchived = {},
                    viewMode = SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_OWNER,
                )
            }
        }

        composeTestRule.onNodeWithText("按老板").assertIsDisplayed()
        composeTestRule.onNodeWithTag("owner-card-owner-1").assertExists()
    }

    private companion object {
        /** 与 AccountsScreen 内部筛选哨兵一致：只表示「未分类」。 */
        const val UNCATEGORIZED = "__uncategorized__"
    }
}
