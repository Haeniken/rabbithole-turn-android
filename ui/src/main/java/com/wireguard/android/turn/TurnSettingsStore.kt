/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import android.content.Context
import android.util.Log
import com.wireguard.android.util.SecureFileStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Simple JSON-based storage for per-tunnel TURN settings.
 *
 * TURN settings are stored alongside the .conf files used by [com.wireguard.android.configStore.FileConfigStore],
 * using file names of the form "<tunnel>.turn.json".
 */
class TurnSettingsStore(private val context: Context) {
    private val secureStore = SecureFileStore(context)

    fun migrateLegacyFiles() {
        context.fileList()
            .filter { it.endsWith(TURN_SUFFIX) }
            .forEach { fileName ->
                val file = File(context.filesDir, fileName)
                try {
                    val stored = secureStore.read(file)
                    secureStore.migrateIfNeeded(file, stored)
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to migrate protected TURN settings $fileName", e)
                }
            }
    }

    private fun fileFor(name: String): File {
        return File(context.filesDir, "$name$TURN_SUFFIX")
    }

    fun load(name: String): TurnSettings? {
        val file = fileFor(name)
        if (!file.isFile) return null
        return try {
            secureStore.read(file).let { stored ->
                val bytes = stored.bytes
                val json = JSONObject(String(bytes, StandardCharsets.UTF_8))

                // Backward compatibility: derive peerType from legacy noDtls
                val noDtlsLegacy = json.optBoolean("noDtls", false)
                val peerTypeDefault = if (noDtlsLegacy) "wireguard" else "proxy_v2"

                val settings = TurnSettings(
                    enabled = json.optBoolean("enabled", false),
                    peer = json.optString("peer", ""),
                    vkLink = json.optString("vkLink", ""),
                    streams = json.optInt("streams", 4),
                    useUdp = json.optBoolean("useUdp", false),
                    localPort = json.optInt("localPort", 9000),
                    turnIp = json.optString("turnIp", ""),
                    turnPort = json.optInt("turnPort", 0),
                    peerType = json.optString("peerType", peerTypeDefault),
                    streamsPerCred = json.optInt("streamsPerCred", 4),
                    watchdogTimeout = json.optInt("watchdogTimeout", 0),
                    useWrap = json.optBoolean("useWrap", false),
                    wrapKeyHex = json.optString("wrapKeyHex", ""),
                    profileSubtitle = json.optString("profileSubtitle", ""),
                )
                secureStore.migrateIfNeeded(file, stored)
                settings
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load TURN settings for tunnel $name", t)
            null
        }
    }

    fun save(name: String, settings: TurnSettings?) {
        val file = fileFor(name)
        if (settings == null) {
            if (file.isFile && !file.delete()) {
                Log.w(TAG, "Failed to delete TURN settings file for $name")
            }
            return
        }

        val json = JSONObject()
            .put("enabled", settings.enabled)
            .put("peer", settings.peer)
            .put("vkLink", settings.vkLink)
            .put("streams", settings.streams)
            .put("useUdp", settings.useUdp)
            .put("localPort", settings.localPort)
            .put("turnIp", settings.turnIp)
            .put("turnPort", settings.turnPort)
            .put("peerType", settings.peerType)
            .put("streamsPerCred", settings.streamsPerCred)
            .put("watchdogTimeout", settings.watchdogTimeout)
            .put("useWrap", settings.useWrap)
            .put("wrapKeyHex", settings.wrapKeyHex)
            .put("profileSubtitle", settings.profileSubtitle)

        file.parentFile?.mkdirs()
        secureStore.write(file, json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun delete(name: String) {
        val file = fileFor(name)
        if (file.isFile && !file.delete()) {
            Log.w(TAG, "Failed to delete TURN settings file for $name")
        }
    }

    fun rename(name: String, replacement: String) {
        val file = fileFor(name)
        if (!file.isFile) return
        val replacementFile = fileFor(replacement)
        if (replacementFile.isFile && !replacementFile.delete()) {
            Log.w(TAG, "Failed to delete existing TURN settings for $replacement")
        }
        try {
            val stored = secureStore.read(file)
            secureStore.write(replacementFile, stored.bytes)
            if (!file.delete()) Log.w(TAG, "Failed to delete old TURN settings for $name")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to rename TURN settings from $name to $replacement", t)
            replacementFile.delete()
        }
    }

    companion object {
        private const val TAG = "WireGuard/TurnSettingsStore"
        private const val TURN_SUFFIX = ".turn.json"
    }
}
