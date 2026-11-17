package com.daigan.record.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Expanded/collapsed state of list units (UF-029), keyed by a **stable identity**
 * (order id, owner id), never by list position. Regrouping, re-sorting or a refresh
 * therefore keeps each unit's own state and can never hand it to another unit.
 *
 * Hoist one instance per list so it survives item recycling, and persist it with
 * [rememberExpansionState] so a rotation or process restore does not expand everything.
 */
@Stable
class ExpansionState private constructor(
    private val expanded: SnapshotStateMap<String, Boolean>,
) {
    constructor() : this(mutableStateMapOf<String, Boolean>())

    fun isExpanded(key: String): Boolean = expanded[key] == true

    fun toggle(key: String) {
        expanded[key] = !isExpanded(key)
    }

    fun setExpanded(key: String, expand: Boolean) {
        expanded[key] = expand
    }

    companion object {
        /** Only open keys are saved: the default of every unit stays collapsed. */
        val Saver: Saver<ExpansionState, Any> = listSaver(
            save = { state -> state.expanded.filterValues { it }.keys.sorted() },
            restore = { keys ->
                ExpansionState().apply { keys.forEach { setExpanded(it, true) } }
            },
        )
    }
}

@Composable
fun rememberExpansionState(): ExpansionState =
    rememberSaveable(saver = ExpansionState.Saver) { ExpansionState() }

/**
 * Header of a collapsible unit: the only expansion trigger.
 *
 * - The whole row is one 48dp-minimum target, so expanding never means hitting a small
 *   chevron on a large screen font.
 * - [Role.Button]-style click plus `stateDescription` ("已展开"/"已收起") tell a screen
 *   reader what the action will do and what the current state is.
 * - Completion checkboxes and business buttons live in the body, never in this header,
 *   so opening a unit cannot also complete, delete or pay anything.
 */
@Composable
fun DisclosureHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "收起详情" else "展开详情",
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        DisclosureIndicator(expanded)
    }
}

/** Small chevron used at the end of a [DisclosureHeader]; the header carries the label. */
@Composable
fun DisclosureIndicator(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
