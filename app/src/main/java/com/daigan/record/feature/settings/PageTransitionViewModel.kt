package com.daigan.record.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.data.preferences.UiPreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one switch that controls page-transition animations.
 *
 * Two collectors share the same singleton store: this screen, which writes it, and
 * `DaiganRecordApp`, which applies it to the nav host. Both read the same flow, so
 * toggling takes effect on the next frame without recreating the navigation graph.
 */
@HiltViewModel
class PageTransitionViewModel @Inject constructor(
    private val preferences: UiPreferenceStore,
) : ViewModel() {

    val pageTransitionAnimations: StateFlow<Boolean> = preferences
        .observePageTransitionAnimations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            // Default true: a user with no stored preference keeps the transitions the
            // app has always had.
            initialValue = true,
        )

    fun setPageTransitionAnimations(enabled: Boolean) {
        viewModelScope.launch { preferences.setPageTransitionAnimationsEnabled(enabled) }
    }
}
