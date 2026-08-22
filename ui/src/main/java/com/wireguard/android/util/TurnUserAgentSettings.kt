/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

object TurnUserAgentSettings {
    @Volatile
    private var override: String = ""

    fun observe(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        dataStore.data.onEach { preferences ->
            override = preferences[USER_AGENT_KEY]?.trim().orEmpty()
        }.launchIn(scope)
    }

    fun resolve(defaultValue: String): String =
        override.takeIf(::isValidUserAgent) ?: defaultValue

    fun isValidUserAgent(value: String): Boolean =
        value.length in 1..MAX_USER_AGENT_LENGTH && value.all { it.code in 0x20..0x7e }

    const val USER_AGENT_KEY_NAME = "turn_user_agent"
    private const val MAX_USER_AGENT_LENGTH = 512
    private val USER_AGENT_KEY = stringPreferencesKey(USER_AGENT_KEY_NAME)
}
