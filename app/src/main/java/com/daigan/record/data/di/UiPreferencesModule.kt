package com.daigan.record.data.di

import com.daigan.record.data.preferences.SharedPreferencesUiPreferenceStore
import com.daigan.record.data.preferences.UiPreferenceStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * UI-preference bindings.
 *
 * Deliberately its own module rather than an entry in `DataModule`: the preferences are
 * device-local presentation state, not business data, and keeping them apart makes it
 * obvious that a backup or a dataset restore must never carry them.
 */
@Module
@InstallIn(SingletonComponent::class)
interface UiPreferencesModule {

    @Singleton
    @Binds
    fun bindsUiPreferenceStore(
        store: SharedPreferencesUiPreferenceStore,
    ): UiPreferenceStore
}
