package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.testing.OrderTestEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** UF-005: per-game 区服 options stay separate and never rewrite account values. */
@RunWith(AndroidJUnit4::class)
class ServerOptionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
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
    fun optionsAreKeptSeparatePerGame() = runBlocking {
        env.serverOptionRepository.setOptions("原神", listOf("官服", "B服"))
        env.serverOptionRepository.setOptions("崩坏：星穹铁道", listOf("官服"))

        assertEquals(listOf("官服", "B服"), env.serverOptionRepository.observeForGame("原神").first())
        assertEquals(listOf("官服"), env.serverOptionRepository.observeForGame("崩坏：星穹铁道").first())
    }

    @Test
    fun renameAndRemoveNeverTouchAccountServerValues() = runBlocking {
        val owner = env.ownerRepository.createOwner("老板甲")
        val account = env.accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = "原神",
                server = "官服",
                uid = "100001",
                loginAccount = "demo",
                loginMethod = LoginMethod.QR_CODE,
            ),
        )
        env.serverOptionRepository.setOptions("原神", listOf("官服", "B服"))

        env.serverOptionRepository.renameOption("原神", "官服", "官方服")
        env.serverOptionRepository.removeOption("原神", "B服")

        val reloaded = env.accountRepository.findAccount(account.id)!!
        assertEquals("账号区服值必须原样保留", "官服", reloaded.server)
        assertEquals(listOf("官方服"), env.serverOptionRepository.observeForGame("原神").first())
    }

    @Test
    fun setOptionsReplacesTheWholeListInOrder() = runBlocking {
        env.serverOptionRepository.setOptions("原神", listOf("官服", "B服", "国际服"))
        env.serverOptionRepository.setOptions("原神", listOf("官服"))
        assertEquals(listOf("官服"), env.serverOptionRepository.observeForGame("原神").first())
    }

    @Test
    fun blankDuplicateAndOversizedOptionsAreRejected() = runBlocking {
        for (bad in listOf(
            listOf(""),
            listOf("  "),
            listOf("官服", "官服"),
            listOf("区".repeat(61)),
            List(51) { "选项$it" },
        )) {
            try {
                env.serverOptionRepository.setOptions("原神", bad)
                fail("应拒绝非法区服选项：$bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
        assertTrue(env.serverOptionRepository.observeForGame("原神").first().isEmpty())
    }
}
