/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wireguard.config.InetNetwork
import kotlinx.coroutines.flow.first

/** User-managed destination networks that always use the physical connection. */
object ManualRouteExclusions {
    suspend fun load(dataStore: DataStore<Preferences>): List<InetNetwork> {
        val raw = dataStore.data.first()[PREFERENCE_KEY] ?: DEFAULT_ROUTES
        return parse(raw)
    }

    fun parse(raw: String): List<InetNetwork> {
        val tokens = raw.lineSequence()
            .flatMap { line ->
                line.substringBefore('#')
                    .splitToSequence(Regex("[,;\\s]+"))
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
            .toList()
        require(tokens.size <= MAX_ROUTES) { "Too many excluded routes (maximum $MAX_ROUTES)" }
        return tokens.map { token ->
            try {
                InetNetwork.parse(token)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Invalid excluded route: $token", e)
            }
        }.distinct()
    }

    const val PREFERENCE_KEY_NAME = "routing_excluded_routes"
    const val DEFAULT_ROUTES = """10.0.0.0/8
172.16.0.0/12
192.168.0.0/16
169.254.0.0/16
224.0.0.0/4
255.255.255.255/32"""
    private const val MAX_ROUTES = 512
    private val PREFERENCE_KEY = stringPreferencesKey(PREFERENCE_KEY_NAME)
}
