package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.provenance
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.testing.FakeGameAccountRepository
import com.daigan.record.testing.FakeGameTemplateRepository
import com.daigan.record.testing.FakeOrderRepository
import com.daigan.record.testing.FakeOwnerRepository
import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.testing.TemplateFixtures
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import com.daigan.record.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Template price presets are a dropdown, never an autorun. These tests pin that applying
 * a template never overwrites a price the user typed, that switching templates never
 * leaves the previous template's options behind, and that a chosen preset lands in the
 * order as the exact same number of cents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewOrderPricePresetTest {

    @get:Rule
    val main = MainDispatcherRule()

    private class RecordingOrderRepository : OrderRepository by FakeOrderRepository() {
        val hostingDrafts = mutableListOf<HostingOrderDraft>()

        override suspend fun createHostingOrder(draft: HostingOrderDraft): String {
            hostingDrafts += draft
            return "hosting-${hostingDrafts.size}"
        }
    }

    private val presetTemplate = TemplateFixtures.template(
        id = "tpl-presets",
        gameName = "重返未来：1999",
        pricePresets = listOf(
            TemplateFixtures.pricePreset(id = "p-daily", amountCents = 3000L, label = "日常价"),
            TemplateFixtures.pricePreset(id = "p-event", amountCents = 1250L, sortOrder = 1),
        ),
    )

    private val plainTemplate = TemplateFixtures.template(
        id = "tpl-plain",
        gameName = "只有任务",
    )

    private fun TestScope.viewModel(
        templates: FakeGameTemplateRepository,
        orderRepository: OrderRepository = FakeOrderRepository(),
    ): NewOrderViewModel {
        val owners = FakeOwnerRepository().apply { ownersById["owner-1"] = owner() }
        val accounts = FakeGameAccountRepository().apply { accountsById["account-1"] = account() }
        val viewModel = NewOrderViewModel(
            SavedStateHandle(
                mapOf(Routes.OWNER_ID_ARG to "owner-1", Routes.ACCOUNT_ID_ARG to "account-1"),
            ),
            owners,
            accounts,
            orderRepository,
            FixedTimeProvider(),
            templates,
        )
        runCurrent()
        return viewModel
    }

    @Test
    fun `applying a template exposes its presets but never fills the price`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(presetTemplate) })
        viewModel.applyTemplate("tpl-presets")
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("p-daily", "p-event"), state.templatePricePresets.map { it.id })
        assertTrue(state.hasPricePresets)
        assertEquals("", state.priceYuan)
        assertNull(state.selectedPricePresetId)
    }

    @Test
    fun `selecting a preset fills the exact same cents`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(presetTemplate) })
        viewModel.applyTemplate("tpl-presets")
        runCurrent()

        viewModel.selectPricePreset("p-event")
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1250L, Money.parseYuanToCents(state.priceYuan))
        assertEquals("p-event", state.selectedPricePresetId)
    }

    @Test
    fun `typing after a selection detaches from the preset`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(presetTemplate) })
        viewModel.applyTemplate("tpl-presets")
        runCurrent()
        viewModel.selectPricePreset("p-daily")
        runCurrent()
        assertEquals("p-daily", viewModel.uiState.value.selectedPricePresetId)

        viewModel.updatePrice("999.99")
        runCurrent()

        assertNull(viewModel.uiState.value.selectedPricePresetId)
        assertEquals("999.99", viewModel.uiState.value.priceYuan)
    }

    @Test
    fun `a template without presets leaves the form exactly as it was`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(plainTemplate) })
        viewModel.applyTemplate("tpl-plain")
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.templatePricePresets.isEmpty())
        assertFalse(state.hasPricePresets)
        assertEquals("", state.priceYuan)
    }

    @Test
    fun `switching to a template without presets drops the stale options`() = runTest {
        val templates = FakeGameTemplateRepository().apply {
            put(presetTemplate)
            put(plainTemplate)
        }
        val viewModel = viewModel(templates)
        viewModel.applyTemplate("tpl-presets")
        runCurrent()
        viewModel.updatePrice("50.00")
        runCurrent()

        viewModel.applyTemplate("tpl-plain")
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.templatePricePresets.isEmpty())
        assertNull(state.selectedPricePresetId)
        // The typed price is the user's, not the template's to overwrite.
        assertEquals("50.00", state.priceYuan)
    }

    @Test
    fun `applying a preset template never overwrites a typed price`() = runTest {
        val templates = FakeGameTemplateRepository().apply {
            put(plainTemplate)
            put(presetTemplate)
        }
        val viewModel = viewModel(templates)
        viewModel.updatePrice("88.00")
        runCurrent()

        viewModel.applyTemplate("tpl-presets")
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("88.00", state.priceYuan)
        assertNull(state.selectedPricePresetId)
        assertEquals(listOf("p-daily", "p-event"), state.templatePricePresets.map { it.id })
    }

    @Test
    fun `clearing the template clears the options but keeps the price`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(presetTemplate) })
        viewModel.applyTemplate("tpl-presets")
        runCurrent()
        viewModel.selectPricePreset("p-daily")
        runCurrent()

        viewModel.clearTemplate()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.templatePricePresets.isEmpty())
        assertNull(state.selectedPricePresetId)
        assertEquals("30.00", state.priceYuan)
    }

    @Test
    fun `submitting after a selection records the preset amount as the order price`() = runTest {
        val orders = RecordingOrderRepository()
        val viewModel = viewModel(
            FakeGameTemplateRepository().apply { put(presetTemplate) },
            orderRepository = orders,
        )
        viewModel.applyTemplate("tpl-presets")
        runCurrent()
        viewModel.selectPricePreset("p-event")
        runCurrent()
        viewModel.submit()
        runCurrent()

        val draft = orders.hostingDrafts.single()
        assertEquals(1250L, draft.priceCentsOrNull)
        assertEquals("tpl-presets", draft.sourceTemplate?.templateId)
    }

    @Test
    fun `an unknown preset id is ignored instead of blanking the field`() = runTest {
        val viewModel = viewModel(FakeGameTemplateRepository().apply { put(presetTemplate) })
        viewModel.applyTemplate("tpl-presets")
        runCurrent()
        viewModel.updatePrice("12.00")
        runCurrent()

        viewModel.selectPricePreset("does-not-exist")
        runCurrent()

        assertEquals("12.00", viewModel.uiState.value.priceYuan)
        assertNull(viewModel.uiState.value.selectedPricePresetId)
    }
}
