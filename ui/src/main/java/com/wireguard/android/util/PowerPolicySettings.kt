/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Client-owned power policy. It does not change or revoke an active VPN session. */
object PowerPolicySettings {
    data class Snapshot(
        val backgroundUpdatesEnabled: Boolean = true,
        val powerSavingEnabled: Boolean = false,
    )

    const val BACKGROUND_UPDATES_KEY_NAME = "power_background_updates_enabled"
    const val POWER_SAVING_KEY_NAME = "power_saving_enabled"

    private val backgroundUpdatesKey = booleanPreferencesKey(BACKGROUND_UPDATES_KEY_NAME)
    private val powerSavingKey = booleanPreferencesKey(POWER_SAVING_KEY_NAME)
    private val mutableState = MutableStateFlow(Snapshot())

    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    fun observe(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        dataStore.data.map(::fromPreferences)
            .distinctUntilChanged()
            .onEach(::applyImmediate)
            .launchIn(scope)
    }

    suspend fun load(dataStore: DataStore<Preferences>): Snapshot =
        fromPreferences(dataStore.data.first()).also(::applyImmediate)

    fun current(): Snapshot = mutableState.value

    /** Keeps synchronous consumers consistent while PreferenceDataStore persists asynchronously. */
    fun applyImmediate(snapshot: Snapshot) {
        mutableState.value = snapshot
    }

    internal fun fromPreferences(preferences: Preferences): Snapshot = Snapshot(
        backgroundUpdatesEnabled = preferences[backgroundUpdatesKey] ?: true,
        powerSavingEnabled = preferences[powerSavingKey] ?: false,
    )
}
