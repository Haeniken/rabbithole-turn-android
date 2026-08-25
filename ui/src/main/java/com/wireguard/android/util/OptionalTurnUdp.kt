/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first

/** Global TURN/UDP preference with an automatic fallback to TURN/TCP. */
object OptionalTurnUdp {
    const val PREFERENCE_KEY_NAME = "optional_turn_udp"
    private val preferenceKey = booleanPreferencesKey(PREFERENCE_KEY_NAME)

    suspend fun isEnabled(dataStore: DataStore<Preferences>): Boolean =
        dataStore.data.first()[preferenceKey] ?: true
}
