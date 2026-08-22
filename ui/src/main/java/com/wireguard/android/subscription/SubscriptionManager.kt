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
import com.wireguard.android.turn.TurnSettings
import com.wireguard.android.util.HttpsFetcher
import com.wireguard.config.Config
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        data class Added(
            val tunnels: List<ObservableTunnel>,
            val subscriptionName: String? = null,
        ) : UpdateResult()

        data object Unchanged : UpdateResult()
        data object Updated : UpdateResult()
        data object Disabled : UpdateResult()
        data object Enabled : UpdateResult()
    }

    suspend fun add(url: String): UpdateResult.Added = operationMutex.withLock {
        val normalizedUrl = url.trim()
        val response = fetchSubscription(normalizedUrl, MAX_BUNDLE_BYTES)
        if (response.status != 200) throw IOException(context.getString(R.string.subscription_add_http_error, response.status))
        if (SubscriptionBundle.isMediaType(response.contentType)) {
            addBundle(normalizedUrl, response)
        } else {
            addLegacy(normalizedUrl, response)
        }
    }

    suspend fun update(tunnelName: String): UpdateResult = operationMutex.withLock {
        updateLocked(tunnelName)
    }

    suspend fun updateAll(): Boolean = operationMutex.withLock {
        var allSuccessful = true
        val targets = store.enumerate()
            .groupBy { it.bundleId ?: "legacy:${it.tunnelName}" }
            .values
            .mapNotNull { it.firstOrNull() }
        for (record in targets) {
            try {
                updateLocked(record.tunnelName)
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to update subscription for ${record.tunnelName}", e)
                allSuccessful = false
            }
        }
        allSuccessful
    }

    fun isSubscribed(tunnelName: String): Boolean = store.isSubscribed(tunnelName)

    fun bundleSummary(tunnelName: String): SubscriptionStore.BundleSummary? = store.summaryForTunnel(tunnelName)

    private suspend fun addLegacy(url: String, response: HttpsFetcher.Response): UpdateResult.Added {
        val config = parseConfig(response.body)
        val tunnelManager = Application.getTunnelManager()
        val tunnelName = uniqueName(suggestName(response.contentDisposition, url))
        val turnSettings = TurnConfigProcessor.extractTurnSettings(config)
        val tunnel = tunnelManager.create(tunnelName, config, turnSettings)
        try {
            store.save(
                SubscriptionStore.Record(
                    tunnelName = tunnel.name,
                    url = url,
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
        return UpdateResult.Added(listOf(tunnel))
    }

    private suspend fun addBundle(url: String, response: HttpsFetcher.Response): UpdateResult.Added {
        val bundle = SubscriptionBundle.parse(response.body)
        val parsedProfiles = parseBundleProfiles(bundle)
        val subscriptionName = commonSubscriptionName(parsedProfiles)
        val bundleId = sha256(url.toByteArray(StandardCharsets.UTF_8))
        if (store.recordsForBundle(bundleId).isNotEmpty())
            throw IOException(context.getString(R.string.subscription_bundle_already_added))

        val tunnelManager = Application.getTunnelManager()
        val created = mutableListOf<ObservableTunnel>()
        try {
            for (profile in parsedProfiles) {
                val tunnelName = uniqueBundleName(profile.name)
                val tunnel = tunnelManager.create(tunnelName, profile.config, profile.turnSettings)
                created += tunnel
                store.save(
                    SubscriptionStore.Record(
                        tunnelName = tunnel.name,
                        url = url,
                        etag = response.etag,
                        lastModified = response.lastModified,
                        contentHash = profile.contentHash,
                        enabled = true,
                        lastCheckedAt = System.currentTimeMillis(),
                        bundleId = bundleId,
                        profileId = profile.id,
                        subscriptionName = subscriptionName,
                        expiresAt = bundle.expiresAt,
                    ),
                )
            }
        } catch (e: Throwable) {
            created.asReversed().forEach { tunnel ->
                store.delete(tunnel.name)
                try {
                    tunnel.deleteAsync()
                } catch (cleanupError: Throwable) {
                    Log.w(TAG, "Unable to roll back bundle tunnel ${tunnel.name}", cleanupError)
                }
            }
            throw e
        }
        return UpdateResult.Added(created, subscriptionName)
    }

    private suspend fun updateLocked(tunnelName: String): UpdateResult {
        val record = store.load(tunnelName) ?: throw IllegalArgumentException(context.getString(R.string.subscription_not_found))
        return if (record.bundleId == null) updateLegacy(record) else updateBundle(record)
    }

    private suspend fun updateLegacy(record: SubscriptionStore.Record): UpdateResult {
        val response = fetchUpdate(record, MAX_CONFIG_BYTES)
        val checkedAt = System.currentTimeMillis()
        if (response.status == 304) {
            store.save(record.copy(lastCheckedAt = checkedAt))
            return UpdateResult.Unchanged
        }
        if (response.status in DISABLED_STATUS_CODES) {
            disableTunnelIfRunning(record.tunnelName)
            store.save(record.copy(enabled = false, etag = null, lastModified = null, lastCheckedAt = checkedAt))
            return UpdateResult.Disabled
        }
        if (response.status != 200) throw IOException(context.getString(R.string.subscription_update_http_error, response.status))
        if (SubscriptionBundle.isMediaType(response.contentType))
            throw IOException(context.getString(R.string.subscription_format_changed))

        val config = parseConfig(response.body)
        val newHash = sha256(response.body)
        val wasDisabled = !record.enabled
        if (newHash != record.contentHash) {
            val tunnel = requireTunnel(record.tunnelName)
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

    private suspend fun updateBundle(target: SubscriptionStore.Record): UpdateResult {
        val bundleId = target.bundleId ?: error("Missing bundle ID")
        val records = store.recordsForBundle(bundleId)
        if (records.isEmpty()) throw IOException(context.getString(R.string.subscription_metadata_invalid))
        val response = fetchUpdate(target, MAX_BUNDLE_BYTES)
        val checkedAt = System.currentTimeMillis()
        if (response.status == 304) {
            records.forEach { store.save(it.copy(lastCheckedAt = checkedAt)) }
            return UpdateResult.Unchanged
        }
        if (response.status in DISABLED_STATUS_CODES) {
            records.forEach { disableTunnelIfRunning(it.tunnelName) }
            records.forEach {
                store.save(it.copy(enabled = false, etag = null, lastModified = null, lastCheckedAt = checkedAt))
            }
            return UpdateResult.Disabled
        }
        if (response.status != 200) throw IOException(context.getString(R.string.subscription_update_http_error, response.status))
        if (!SubscriptionBundle.isMediaType(response.contentType))
            throw IOException(context.getString(R.string.subscription_format_changed))

        val bundle = SubscriptionBundle.parse(response.body)
        val profiles = parseBundleProfiles(bundle)
        val subscriptionName = commonSubscriptionName(profiles)
        val recordByProfile = records.associateBy { it.profileId }
        val profileIds = profiles.map { it.id }.toSet()
        val storedProfileIds = recordByProfile.keys.filterNotNull().toSet()
        if (recordByProfile.keys.any { it == null } || profileIds != storedProfileIds)
            throw IOException(context.getString(R.string.subscription_bundle_profiles_changed))

        data class Previous(
            val tunnel: ObservableTunnel,
            val config: Config,
            val turnSettings: TurnSettings?,
        )
        val changed = profiles.filter { profile ->
            recordByProfile[profile.id]?.contentHash?.let { it != profile.contentHash } == true
        }
        val previous = mutableListOf<Previous>()
        try {
            for (profile in changed) {
                val record = recordByProfile.getValue(profile.id)
                val tunnel = requireTunnel(record.tunnelName)
                previous += Previous(tunnel, tunnel.getConfigAsync(), tunnel.getTurnSettingsAsync())
                Application.getTunnelManager().setTunnelConfig(tunnel, profile.config, profile.turnSettings)
            }
        } catch (e: Throwable) {
            previous.asReversed().forEach { old ->
                try {
                    Application.getTunnelManager().setTunnelConfig(old.tunnel, old.config, old.turnSettings)
                } catch (rollbackError: Throwable) {
                    Log.e(TAG, "Unable to roll back bundle profile ${old.tunnel.name}", rollbackError)
                }
            }
            throw e
        }

        val wasDisabled = records.any { !it.enabled }
        for (profile in profiles) {
            val record = recordByProfile[profile.id] ?: continue
            store.save(
                record.copy(
                    etag = response.etag,
                    lastModified = response.lastModified,
                    contentHash = profile.contentHash,
                    enabled = true,
                    lastCheckedAt = checkedAt,
                    subscriptionName = subscriptionName,
                    expiresAt = bundle.expiresAt,
                ),
            )
        }
        return when {
            wasDisabled -> UpdateResult.Enabled
            changed.isNotEmpty() -> UpdateResult.Updated
            else -> UpdateResult.Unchanged
        }
    }

    private suspend fun fetchUpdate(record: SubscriptionStore.Record, maxBytes: Int) = fetchSubscription(
        record.url,
        maxBytes,
        record.etag,
        record.lastModified,
    )

    private suspend fun fetchSubscription(
        url: String,
        maxBytes: Int,
        etag: String? = null,
        lastModified: String? = null,
    ): HttpsFetcher.Response {
        val settings = SubscriptionSettings.load(Application.getPreferencesDataStore())
        val timeoutMs = settings.requestTimeoutSeconds * 1_000
        return HttpsFetcher.get(
            url = url,
            maxBytes = maxBytes,
            etag = etag,
            lastModified = lastModified,
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
            userAgent = settings.userAgent,
        )
    }

    private fun parseBundleProfiles(bundle: SubscriptionBundle): List<ParsedProfile> = bundle.profiles.map { profile ->
        val config = parseConfig(profile.config)
        ParsedProfile(
            id = profile.id,
            name = profile.name,
            config = config,
            turnSettings = TurnConfigProcessor.extractTurnSettings(config),
            subscriptionName = subscriptionName(config),
            contentHash = sha256(profile.config),
        )
    }

    private fun commonSubscriptionName(profiles: List<ParsedProfile>): String {
        val names = profiles.map { it.subscriptionName }.toSet()
        if (names.size != 1) throw IOException(context.getString(R.string.subscription_bundle_name_invalid))
        return names.single()
    }

    private fun subscriptionName(config: Config): String {
        val value = config.peers.asSequence()
            .flatMap { it.extraLines.asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith(SUBSCRIPTION_NAME_PREFIX, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 64 }
        return value ?: throw IOException(context.getString(R.string.subscription_bundle_name_invalid))
    }

    private suspend fun disableTunnelIfRunning(tunnelName: String) {
        val tunnel = Application.getTunnelManager().getTunnels()[tunnelName] ?: return
        if (tunnel.state == Tunnel.State.UP) tunnel.setStateAsync(Tunnel.State.DOWN)
    }

    private suspend fun requireTunnel(tunnelName: String): ObservableTunnel =
        Application.getTunnelManager().getTunnels()[tunnelName]
            ?: throw IllegalStateException(context.getString(R.string.subscription_tunnel_missing))

    private suspend fun uniqueName(base: String): String {
        return uniqueAvailableName(sanitizeTunnelName(base))
    }

    private suspend fun uniqueBundleName(base: String): String {
        val sanitized = base
            .replace(Regex("[^A-Za-z0-9_=+.-]"), "-")
            .trim('-', '.')
            .take(15)
            .ifBlank { "Subscription" }
        return uniqueAvailableName(sanitized)
    }

    private suspend fun uniqueAvailableName(sanitizedBase: String): String {
        val tunnels = Application.getTunnelManager().getTunnels()
        if (!tunnels.containsKey(sanitizedBase)) return sanitizedBase
        for (suffix in 2..999) {
            val candidate = "${sanitizedBase.take(12)}-$suffix"
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
        return sanitizeTunnelName(raw.substringBeforeLast('.', raw))
    }

    private fun sanitizeTunnelName(raw: String): String {
        val sanitized = raw
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

    private data class ParsedProfile(
        val id: String,
        val name: String,
        val config: Config,
        val turnSettings: TurnSettings?,
        val subscriptionName: String,
        val contentHash: String,
    )

    companion object {
        private const val TAG = "RabbitHole/SubscriptionManager"
        private val DISABLED_STATUS_CODES = setOf(401, 403, 404, 410)
        private const val MAX_CONFIG_BYTES = 256 * 1024
        private const val MAX_BUNDLE_BYTES = 1024 * 1024
        private const val SUBSCRIPTION_NAME_PREFIX = "#@rhv:SubscriptionName"
    }

    private val operationMutex = Mutex()
}
