package com.daigan.record.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UF-029: expansion is keyed by a stable identity, starts collapsed and restores only
 * what the user actually opened.
 */
class ExpansionStateTest {

    @Test
    fun everyUnitStartsCollapsed() {
        val state = ExpansionState()

        assertFalse(state.isExpanded("order-1"))
        assertFalse(state.isExpanded("owner-1"))
    }

    @Test
    fun togglingOneUnitNeverAffectsAnother() {
        val state = ExpansionState()

        state.toggle("order-1")
        assertTrue(state.isExpanded("order-1"))
        assertFalse(state.isExpanded("order-2"))

        state.toggle("order-1")
        assertFalse(state.isExpanded("order-1"))
    }

    @Test
    fun expandedIdentitySurvivesReorderingBecauseOnlyKeysAreStored() {
        val state = ExpansionState()
        state.setExpanded("order-b", true)

        // A new list order / refresh only re-reads the same keys.
        val reordered = listOf("order-a", "order-c", "order-b")
        assertEquals(
            listOf(false, false, true),
            reordered.map { state.isExpanded(it) },
        )
    }

    @Test
    fun saverPersistsOnlyOpenUnits() {
        val state = ExpansionState()
        state.setExpanded("order-1", true)
        state.setExpanded("owner-1", true)
        state.setExpanded("order-2", false)

        val saved = with(ExpansionState.Saver) { SaverScope { true }.save(state) }
        val restored = checkNotNull(ExpansionState.Saver.restore(checkNotNull(saved)))

        assertTrue(restored.isExpanded("order-1"))
        assertTrue(restored.isExpanded("owner-1"))
        assertFalse(restored.isExpanded("order-2"))
    }
}
