/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wireguard.android.BuildConfig
import kotlinx.coroutines.flow.first

object SubscriptionSettings {
    data class Snapshot(
        val automaticUpdates: Boolean,
        val intervalHours: Int,
        val updateOnOpen: Boolean,
        val requestTimeoutSeconds: Int,
        val userAgent: String,
    )

    suspend fun load(dataStore: DataStore<Preferences>): Snapshot {
        val preferences = dataStore.data.first()
        return Snapshot(
            automaticUpdates = preferences[AUTOMATIC_UPDATES_KEY] ?: true,
            intervalHours = normalizeIntervalHours(preferences[UPDATE_INTERVAL_KEY]),
            updateOnOpen = preferences[UPDATE_ON_OPEN_KEY] ?: false,
            requestTimeoutSeconds = normalizeTimeoutSeconds(preferences[REQUEST_TIMEOUT_KEY]),
            userAgent = resolveUserAgent(preferences[USER_AGENT_KEY]),
        )
    }

    fun defaultUserAgent(): String = defaultUserAgent(BuildConfig.VERSION_NAME, BuildConfig.SOURCE_COMMIT)

    internal fun defaultUserAgent(version: String, sourceCommit: String): String =
        "Rabbithole/$version/Android/${sourceCommit.ifBlank { "unknown" }}"

    internal fun normalizeIntervalHours(raw: String?): Int =
        (raw?.trim()?.toIntOrNull() ?: DEFAULT_UPDATE_INTERVAL_HOURS)
            .coerceIn(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS)

    internal fun normalizeTimeoutSeconds(raw: Int?): Int =
        (raw ?: DEFAULT_REQUEST_TIMEOUT_SECONDS)
            .coerceIn(MIN_REQUEST_TIMEOUT_SECONDS, MAX_REQUEST_TIMEOUT_SECONDS)

    fun resolveUserAgent(override: String?): String {
        val candidate = override?.trim().orEmpty()
        return if (isValidUserAgent(candidate)) candidate else defaultUserAgent()
    }

    fun isValidUserAgent(value: String): Boolean =
        value.length in 1..MAX_USER_AGENT_LENGTH && value.all { it.code in 0x20..0x7e }

    const val AUTOMATIC_UPDATES_KEY_NAME = "subscription_automatic_updates"
    const val UPDATE_INTERVAL_KEY_NAME = "subscription_update_interval_hours"
    const val UPDATE_ON_OPEN_KEY_NAME = "subscription_update_on_open"
    const val REQUEST_TIMEOUT_KEY_NAME = "subscription_request_timeout_seconds"
    const val USER_AGENT_KEY_NAME = "subscription_user_agent"

    const val DEFAULT_UPDATE_INTERVAL_HOURS = 12
    const val MIN_UPDATE_INTERVAL_HOURS = 1
    const val MAX_UPDATE_INTERVAL_HOURS = 730
    const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 10
    const val MIN_REQUEST_TIMEOUT_SECONDS = 5
    const val MAX_REQUEST_TIMEOUT_SECONDS = 30
    private const val MAX_USER_AGENT_LENGTH = 256

    private val AUTOMATIC_UPDATES_KEY = booleanPreferencesKey(AUTOMATIC_UPDATES_KEY_NAME)
    private val UPDATE_INTERVAL_KEY = stringPreferencesKey(UPDATE_INTERVAL_KEY_NAME)
    private val UPDATE_ON_OPEN_KEY = booleanPreferencesKey(UPDATE_ON_OPEN_KEY_NAME)
    private val REQUEST_TIMEOUT_KEY = intPreferencesKey(REQUEST_TIMEOUT_KEY_NAME)
    private val USER_AGENT_KEY = stringPreferencesKey(USER_AGENT_KEY_NAME)
}
