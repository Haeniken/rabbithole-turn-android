/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import android.content.Context
import android.util.Log
import com.wireguard.android.util.SecureFileStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class SubscriptionStore(private val context: Context) {
    private val secureStore = SecureFileStore(context)

    fun migrateLegacyFiles() {
        directory.listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.forEach { file ->
                try {
                    val stored = secureStore.read(file)
                    secureStore.migrateIfNeeded(file, stored)
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to migrate protected subscription metadata ${file.name}", e)
                }
            }
    }

    data class Record(
        val tunnelName: String,
        val url: String,
        val etag: String? = null,
        val lastModified: String? = null,
        val contentHash: String? = null,
        val enabled: Boolean = true,
        val lastCheckedAt: Long = 0,
        val bundleId: String? = null,
        val profileId: String? = null,
        val subscriptionName: String? = null,
        val expiresAt: Long? = null,
    )

    fun load(tunnelName: String): Record? {
        val file = fileFor(tunnelName)
        if (!file.isFile) return null
        return try {
            val stored = secureStore.read(file)
            val json = JSONObject(String(stored.bytes, StandardCharsets.UTF_8))
            val record = Record(
                tunnelName,
                json.getString("url"),
                json.optString("etag").ifBlank { null },
                json.optString("lastModified").ifBlank { null },
                json.optString("contentHash").ifBlank { null },
                json.optBoolean("enabled", true),
                json.optLong("lastCheckedAt", 0),
                json.optString("bundleId").ifBlank { null },
                json.optString("profileId").ifBlank { null },
                json.optString("subscriptionName").ifBlank { null },
                json.optLong("expiresAt", 0).takeIf { it > 0 },
            )
            secureStore.migrateIfNeeded(file, stored)
            record
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to read subscription metadata for $tunnelName", e)
            null
        }
    }

    fun enumerate(): List<Record> = directory.listFiles { file -> file.name.endsWith(SUFFIX) }
        ?.mapNotNull { file -> load(file.name.removeSuffix(SUFFIX)) }
        ?: emptyList()

    fun save(record: Record) {
        directory.mkdirs()
        val target = fileFor(record.tunnelName)
        val json = JSONObject()
            .put("url", record.url)
            .put("etag", record.etag ?: "")
            .put("lastModified", record.lastModified ?: "")
            .put("contentHash", record.contentHash ?: "")
            .put("enabled", record.enabled)
            .put("lastCheckedAt", record.lastCheckedAt)
            .put("bundleId", record.bundleId ?: "")
            .put("profileId", record.profileId ?: "")
            .put("subscriptionName", record.subscriptionName ?: "")
            .put("expiresAt", record.expiresAt ?: 0)
        secureStore.write(target, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun delete(tunnelName: String) {
        val file = fileFor(tunnelName)
        if (file.isFile && !file.delete()) Log.w(TAG, "Unable to delete subscription metadata for $tunnelName")
    }

    fun rename(tunnelName: String, replacement: String) {
        val record = load(tunnelName) ?: return
        save(record.copy(tunnelName = replacement))
        delete(tunnelName)
    }

    fun isSubscribed(tunnelName: String): Boolean = fileFor(tunnelName).isFile

    fun recordsForBundle(bundleId: String): List<Record> = enumerate()
        .filter { it.bundleId == bundleId }

    fun summaryForTunnel(tunnelName: String): BundleSummary? {
        val record = load(tunnelName) ?: return null
        if (record.bundleId == null || record.subscriptionName == null || record.expiresAt == null) return null
        return BundleSummary(record.bundleId, record.subscriptionName, record.expiresAt, record.enabled)
    }

    fun requireEnabled(tunnelName: String) {
        if (!fileFor(tunnelName).isFile) return
        val record = load(tunnelName)
            ?: throw SubscriptionDisabledException(context.getString(com.wireguard.android.R.string.subscription_metadata_invalid))
        if (!record.enabled) throw SubscriptionDisabledException(context.getString(com.wireguard.android.R.string.subscription_disabled_error))
    }

    private fun fileFor(tunnelName: String) = File(directory, "$tunnelName$SUFFIX")
    private val directory = File(context.filesDir, "subscriptions")

    companion object {
        private const val TAG = "RabbitHole/SubscriptionStore"
        private const val SUFFIX = ".subscription.json"
    }

    data class BundleSummary(
        val bundleId: String,
        val name: String,
        val expiresAt: Long,
        val enabled: Boolean,
    )
}
