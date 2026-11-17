package com.daigan.record.feature.settings

import com.daigan.record.testing.InMemoryUiPreferenceStore
import com.daigan.record.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageTransitionViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `animations are on until the user turns them off`() = runTest {
        val viewModel = PageTransitionViewModel(InMemoryUiPreferenceStore())
        assertTrue(viewModel.pageTransitionAnimations.value)

        viewModel.setPageTransitionAnimations(false)
        runCurrent()

        assertFalse(viewModel.pageTransitionAnimations.value)
    }

    @Test
    fun `a stored off value is read on construction with no flash of the wrong switch`() =
        runTest {
            val backing = mutableMapOf<String, Boolean>()
            InMemoryUiPreferenceStore(backing).setPageTransitionAnimationsEnabled(false)

            val viewModel = PageTransitionViewModel(InMemoryUiPreferenceStore(backing))

            assertFalse(viewModel.pageTransitionAnimations.value)
        }
}
