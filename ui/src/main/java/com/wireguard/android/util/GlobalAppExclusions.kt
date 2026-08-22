/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/** Applications that must bypass every userspace tunnel, regardless of profile source. */
object GlobalAppExclusions {
    val KEY = stringSetPreferencesKey("global_excluded_applications")

    suspend fun load(dataStore: DataStore<Preferences>): Set<String> =
        dataStore.data.first()[KEY].orEmpty()

    suspend fun save(dataStore: DataStore<Preferences>, packageNames: Set<String>) {
        dataStore.edit { preferences ->
            if (packageNames.isEmpty()) preferences.remove(KEY)
            else preferences[KEY] = packageNames
        }
    }
}
