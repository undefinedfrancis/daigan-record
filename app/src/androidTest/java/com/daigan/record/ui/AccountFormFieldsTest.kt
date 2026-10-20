package com.daigan.record.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.feature.accounts.AccountFormScreen
import com.daigan.record.feature.accounts.AccountFormUiState
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.ui.theme.DaiganRecordTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-006/UF-008/UF-005 form behavior: QR login, login device presets plus custom
 * values, and per-game 区服 option chips with free text still available.
 */
@RunWith(AndroidJUnit4::class)
class AccountFormFieldsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun qrCodeLoginIsSelectableAndDropsThePasswordField() {
        var draft by mutableStateOf(
            AccountDraft(
                gameName = "原神",
                server = "官服",
                uid = "100001",
                loginAccount = "demo",
                loginMethod = LoginMethod.PASSWORD,
                password = "secret",
            ),
        )
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountFormScreen(
                    uiState = AccountFormUiState(draft = draft),
                    onBack = {},
                    onDraftChange = { transform -> draft = transform(draft) },
                    onSelectLoginMethod = { method ->
                        draft = draft.copy(
                            loginMethod = method,
                            password = if (method == LoginMethod.PASSWORD) draft.password else "",
                        )
                    },
                    onSelectTemplate = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText("登录密码").assertIsDisplayed()
        composeTestRule.onNodeWithText("扫码登录").performClick()
        assertEquals(LoginMethod.QR_CODE, draft.loginMethod)
        assertEquals("", draft.password)
        composeTestRule.onNodeWithText("登录密码").assertDoesNotExist()
        composeTestRule.onNodeWithText("扫码登录只记录登录方式", substring = true).assertIsDisplayed()
    }

    @Test
    fun loginDevicePresetsAndCustomValuesRoundTripThroughTheRepository() {
        runBlocking {
        var draft by mutableStateOf(
            AccountDraft(
                gameName = "原神",
                server = "官服",
                uid = "100001",
                loginAccount = "demo",
                loginMethod = LoginMethod.QR_CODE,
            ),
        )
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountFormScreen(
                    uiState = AccountFormUiState(draft = draft),
                    onBack = {},
                    onDraftChange = { transform -> draft = transform(draft) },
                    onSelectLoginMethod = { method -> draft = draft.copy(loginMethod = method) },
                    onSelectTemplate = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText("平板").performClick()
        assertEquals("平板", draft.loginDevice)
        composeTestRule.onNodeWithText("登录设备名称").performTextClearance()
        composeTestRule.onNodeWithText("登录设备名称").performTextInput("模拟器")
        assertEquals("模拟器", draft.loginDevice)

        // Persistence: create and reload through the real repository.
        val owner = env.ownerRepository.createOwner("老板甲")
        val account = env.accountRepository.createAccount(owner.id, draft)
        val reloaded = env.accountRepository.findAccount(account.id)!!
        assertEquals(LoginMethod.QR_CODE, reloaded.loginMethod)
        assertEquals("模拟器", reloaded.loginDevice)
        }
    }

    @Test
    fun storedDeviceAndQrMethodDisplayAfterReload() {
        val stored = com.daigan.record.data.model.GameAccount(
            id = "account-1",
            ownerId = "owner-1",
            gameName = "原神",
            server = "官服",
            uid = "100001",
            loginAccount = "demo",
            loginMethod = LoginMethod.QR_CODE,
            password = null,
            createdAt = java.time.Instant.EPOCH,
            archived = false,
            archivedWithOwner = false,
            loginDevice = "模拟器",
        )
        val reloadedDraft = AccountDraft.from(stored)
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountFormScreen(
                    uiState = AccountFormUiState(isEdit = true, draft = reloadedDraft),
                    onBack = {},
                    onDraftChange = {},
                    onSelectLoginMethod = {},
                    onSelectTemplate = {},
                    onSave = {},
                )
            }
        }
        composeTestRule.onNodeWithText("模拟器").assertIsDisplayed()
        composeTestRule.onNodeWithText("扫码登录").assertIsDisplayed()
    }

    @Test
    fun serverOptionChipsFillTheFieldWhileCustomInputStaysAvailable() {
        var draft by mutableStateOf(
            AccountDraft(
                gameName = "原神",
                server = "",
                uid = "100001",
                loginAccount = "demo",
                loginMethod = LoginMethod.QR_CODE,
            ),
        )
        composeTestRule.setContent {
            DaiganRecordTheme {
                AccountFormScreen(
                    uiState = AccountFormUiState(
                        draft = draft,
                        serverOptions = listOf("官服", "B服"),
                    ),
                    onBack = {},
                    onDraftChange = { transform -> draft = transform(draft) },
                    onSelectLoginMethod = {},
                    onSelectTemplate = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText("B服").performClick()
        assertEquals("B服", draft.server)
        composeTestRule.onNodeWithTag("account-server-field").performTextClearance()
        composeTestRule.onNodeWithTag("account-server-field").performTextInput("自定义服")
        assertEquals("自定义服", draft.server)
        composeTestRule.onNodeWithText("点选常用区服", substring = true).assertIsDisplayed()
    }
}
