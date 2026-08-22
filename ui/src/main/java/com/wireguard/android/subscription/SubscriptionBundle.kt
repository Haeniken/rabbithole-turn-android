/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant

internal data class SubscriptionBundle(
    val expiresAt: Long,
    val profiles: List<Profile>,
) {
    data class Profile(
        val id: String,
        val name: String,
        val config: ByteArray,
    )

    companion object {
        const val MEDIA_TYPE = "application/vnd.rabbithole.turn-bundle+json"
        private const val TYPE = "rabbithole-turn-bundle"
        private const val VERSION = 1
        private const val MAX_PROFILES = 8
        private const val MAX_PROFILE_BYTES = 256 * 1024

        fun isMediaType(contentType: String?): Boolean = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.equals(MEDIA_TYPE, ignoreCase = true) == true

        fun parse(bytes: ByteArray): SubscriptionBundle {
            if (bytes.isEmpty()) throw IOException("The subscription bundle is empty")
            try {
                val document = JSONObject(String(bytes, StandardCharsets.UTF_8))
                if (document.getInt("version") != VERSION || document.getString("type") != TYPE)
                    throw IOException("Unsupported subscription bundle format")
                val expiresAt = Instant.parse(document.getString("expiresAt")).toEpochMilli()
                val sourceProfiles = document.getJSONArray("profiles")
                if (sourceProfiles.length() !in 1..MAX_PROFILES)
                    throw IOException("The subscription bundle has an invalid profile count")
                val ids = mutableSetOf<String>()
                val profiles = buildList(sourceProfiles.length()) {
                    for (index in 0 until sourceProfiles.length()) {
                        val source = sourceProfiles.getJSONObject(index)
                        val id = source.getString("id").trim()
                        val name = source.getString("name").trim()
                        val config = source.getString("config").toByteArray(StandardCharsets.UTF_8)
                        if (!PROFILE_ID.matches(id) || name.isBlank() || name.length > 64)
                            throw IOException("The subscription bundle contains invalid profile metadata")
                        if (!ids.add(id)) throw IOException("The subscription bundle contains duplicate profile IDs")
                        if (config.isEmpty() || config.size > MAX_PROFILE_BYTES)
                            throw IOException("The subscription bundle contains an invalid profile")
                        add(Profile(id, name, config))
                    }
                }
                return SubscriptionBundle(expiresAt, profiles)
            } catch (e: IOException) {
                throw e
            } catch (e: JSONException) {
                throw IOException("Unable to parse the subscription bundle", e)
            } catch (e: RuntimeException) {
                throw IOException("Unable to parse the subscription bundle", e)
            }
        }

        private val PROFILE_ID = Regex("[a-z0-9][a-z0-9._-]{0,31}")
    }
}
