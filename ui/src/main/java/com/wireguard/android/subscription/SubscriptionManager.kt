/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import android.content.Context
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.turn.TurnConfigProcessor
import com.wireguard.android.util.HttpsFetcher
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class SubscriptionManager(
    private val context: Context,
    private val store: SubscriptionStore,
) {
    sealed class UpdateResult {
        data class Added(val tunnel: ObservableTunnel) : UpdateResult()
        data object Unchanged : UpdateResult()
        data object Updated : UpdateResult()
        data object Disabled : UpdateResult()
        data object Enabled : UpdateResult()
    }

    suspend fun add(url: String): UpdateResult.Added {
        val response = HttpsFetcher.get(url, MAX_CONFIG_BYTES)
        if (response.status != 200) throw IOException(context.getString(R.string.subscription_add_http_error, response.status))
        val config = parseConfig(response.body)
        val tunnelManager = Application.getTunnelManager()
        val tunnelName = uniqueName(suggestName(response.contentDisposition, url))
        val turnSettings = TurnConfigProcessor.extractTurnSettings(config)
        val tunnel = tunnelManager.create(tunnelName, config, turnSettings)
        try {
            store.save(
                SubscriptionStore.Record(
                    tunnelName = tunnel.name,
                    url = url.trim(),
                    etag = response.etag,
                    lastModified = response.lastModified,
                    contentHash = sha256(response.body),
                    enabled = true,
                    lastCheckedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: Throwable) {
            tunnel.deleteAsync()
            throw e
        }
        return UpdateResult.Added(tunnel)
    }

    suspend fun update(tunnelName: String): UpdateResult {
        val record = store.load(tunnelName) ?: throw IllegalArgumentException(context.getString(R.string.subscription_not_found))
        val response = HttpsFetcher.get(
            record.url,
            MAX_CONFIG_BYTES,
            record.etag,
            record.lastModified,
        )
        val checkedAt = System.currentTimeMillis()
        if (response.status == 304) {
            store.save(record.copy(lastCheckedAt = checkedAt))
            return UpdateResult.Unchanged
        }
        if (response.status in DISABLED_STATUS_CODES) {
            disableTunnelIfRunning(tunnelName)
            store.save(record.copy(enabled = false, etag = null, lastModified = null, lastCheckedAt = checkedAt))
            return UpdateResult.Disabled
        }
        if (response.status != 200) throw IOException(context.getString(R.string.subscription_update_http_error, response.status))

        val config = parseConfig(response.body)
        val newHash = sha256(response.body)
        val wasDisabled = !record.enabled
        if (newHash != record.contentHash) {
            val tunnel = Application.getTunnelManager().getTunnels()[tunnelName]
                ?: throw IllegalStateException(context.getString(R.string.subscription_tunnel_missing))
            Application.getTunnelManager().setTunnelConfig(
                tunnel,
                config,
                TurnConfigProcessor.extractTurnSettings(config),
            )
        }
        store.save(
            record.copy(
                etag = response.etag,
                lastModified = response.lastModified,
                contentHash = newHash,
                enabled = true,
                lastCheckedAt = checkedAt,
            ),
        )
        return when {
            wasDisabled -> UpdateResult.Enabled
            newHash != record.contentHash -> UpdateResult.Updated
            else -> UpdateResult.Unchanged
        }
    }

    suspend fun updateAll(): Boolean {
        var allSuccessful = true
        for (record in store.enumerate()) {
            try {
                update(record.tunnelName)
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to update subscription for ${record.tunnelName}", e)
                allSuccessful = false
            }
        }
        return allSuccessful
    }

    fun isSubscribed(tunnelName: String): Boolean = store.isSubscribed(tunnelName)

    private suspend fun disableTunnelIfRunning(tunnelName: String) {
        val tunnel = Application.getTunnelManager().getTunnels()[tunnelName] ?: return
        if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
    }

    private suspend fun uniqueName(base: String): String {
        val tunnels = Application.getTunnelManager().getTunnels()
        if (!tunnels.containsKey(base)) return base
        for (suffix in 2..999) {
            val candidate = "$base-$suffix"
            if (!tunnels.containsKey(candidate)) return candidate
        }
        throw IllegalStateException(context.getString(R.string.subscription_name_exhausted))
    }

    private fun suggestName(contentDisposition: String?, url: String): String {
        val headerName = contentDisposition
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', '\'')
        val pathName = try {
            URI(url).path.substringAfterLast('/')
        } catch (_: Throwable) {
            ""
        }
        val raw = headerName?.ifBlank { null } ?: pathName.ifBlank { "subscription" }
        val withoutExtension = raw.substringBeforeLast('.', raw)
        val sanitized = withoutExtension
            .lowercase(Locale.ENGLISH)
            .replace(Regex("[^a-z0-9_=+.-]"), "-")
            .trim('-', '.')
            .take(15)
        return sanitized.ifBlank { "subscription" }
    }

    private fun parseConfig(bytes: ByteArray): Config {
        if (bytes.isEmpty()) throw IOException(context.getString(R.string.subscription_empty_config))
        return Config.parse(ByteArrayInputStream(bytes))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "RabbitHole/SubscriptionManager"
        private val DISABLED_STATUS_CODES = setOf(401, 403, 404, 410)
        private const val MAX_CONFIG_BYTES = 256 * 1024
    }
}
