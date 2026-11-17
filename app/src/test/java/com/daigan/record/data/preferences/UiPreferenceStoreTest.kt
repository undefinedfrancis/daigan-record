package com.daigan.record.data.preferences

import com.daigan.record.testing.InMemoryUiPreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setting must default to the pre-existing behaviour, take effect immediately, and
 * survive a restart. A restart is modelled as a second store constructed over the same
 * backing map, which is exactly what `SharedPreferences` gives the app.
 */
class UiPreferenceStoreTest {

    @Test
    fun `a fresh installation animates by default`() = runTest {
        val store = InMemoryUiPreferenceStore()
        assertTrue(store.pageTransitionAnimationsEnabled())
        assertTrue(store.observePageTransitionAnimations().first())
    }

    @Test
    fun `turning animations off is visible to the flow immediately`() = runTest {
        val store = InMemoryUiPreferenceStore()
        store.setPageTransitionAnimationsEnabled(false)
        assertFalse(store.pageTransitionAnimationsEnabled())
        assertFalse(store.observePageTransitionAnimations().first())
    }

    @Test
    fun `the preference survives a restart and can be turned back on`() = runTest {
        val backing = mutableMapOf<String, Boolean>()
        InMemoryUiPreferenceStore(backing).setPageTransitionAnimationsEnabled(false)

        val restarted = InMemoryUiPreferenceStore(backing)
        assertFalse(restarted.pageTransitionAnimationsEnabled())

        restarted.setPageTransitionAnimationsEnabled(true)
        assertTrue(InMemoryUiPreferenceStore(backing).pageTransitionAnimationsEnabled())
    }

    @Test
    fun `the production key and default match the in-memory contract`() = runTest {
        assertEquals(
            SharedPreferencesUiPreferenceStore.KEY_PAGE_TRANSITIONS,
            InMemoryUiPreferenceStore.KEY_PAGE_TRANSITIONS,
        )
        assertEquals(
            SharedPreferencesUiPreferenceStore.DEFAULT_PAGE_TRANSITIONS,
            InMemoryUiPreferenceStore.DEFAULT_PAGE_TRANSITIONS,
        )
    }
}
