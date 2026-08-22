/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wireguard.android.R
import com.wireguard.android.routing.V2RayGeoDataParser.DomainRule
import com.wireguard.android.routing.V2RayGeoDataParser.DomainType
import com.wireguard.android.util.HttpsFetcher
import com.wireguard.config.InetNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

internal fun parseRoutingCategories(raw: String?, defaults: List<String>): List<String> {
    val categories = raw
        ?.split(Regex("[,;\\s]+"))
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinctBy { it.lowercase(Locale.ROOT) }
        .orEmpty()
    return categories.ifEmpty { defaults }
}

/** Downloads and validates the GeoIP/GeoSite data used by direct-routing profiles. */
class RoutingListManager(
    private val context: Context,
    private val preferences: DataStore<Preferences>,
) {
    data class Status(
        val updatedAt: Long,
        val cidrCount: Int,
        val domainCount: Int,
        val resolvedAddressCount: Int,
    )

    sealed interface UpdateProgress {
        data class Downloading(
            val fileName: String,
            val bytesDownloaded: Long,
            val bytesTotal: Long,
        ) : UpdateProgress

        data object Validating : UpdateProgress
    }

    /**
     * Downloads both data files, validates the required categories, then atomically activates the
     * complete pair. The previously active slot remains untouched until every step succeeds.
     */
    suspend fun update(onProgress: ((UpdateProgress) -> Unit)? = null): Status = withContext(Dispatchers.IO) {
        val currentPreferences = preferences.data.first()
        val geoIpUrl = currentPreferences[GEOIP_URL_KEY]?.ifBlank { null } ?: DEFAULT_GEOIP_URL
        val geoSiteUrl = currentPreferences[GEOSITE_URL_KEY]?.ifBlank { null } ?: DEFAULT_GEOSITE_URL
        val geoIpCategories = parseRoutingCategories(
            currentPreferences[GEOIP_CATEGORIES_KEY],
            DEFAULT_GEOIP_CATEGORIES,
        )
        val geoSiteCategories = parseRoutingCategories(
            currentPreferences[GEOSITE_CATEGORIES_KEY],
            DEFAULT_GEOSITE_CATEGORIES,
        )
        Log.i(TAG, "Updating GeoIP/GeoSite data: geoip=${geoIpCategories.joinToString()}, geosite=${geoSiteCategories.joinToString()}")

        val geoIpResponse = HttpsFetcher.get(geoIpUrl, MAX_GEOIP_BYTES) { downloaded, total ->
            onProgress?.invoke(UpdateProgress.Downloading("GeoIP", downloaded, total))
        }
        if (geoIpResponse.status != 200)
            throw IOException("GeoIP server returned HTTP ${geoIpResponse.status}")
        val cidrs = geoIpCategories
            .flatMap { V2RayGeoDataParser.parseIpCategory(geoIpResponse.body, it) }
            .distinct()

        val geoSiteResponse = HttpsFetcher.get(geoSiteUrl, MAX_GEOSITE_BYTES) { downloaded, total ->
            onProgress?.invoke(UpdateProgress.Downloading("GeoSite", downloaded, total))
        }
        if (geoSiteResponse.status != 200)
            throw IOException("GeoSite server returned HTTP ${geoSiteResponse.status}")
        val domainRules = geoSiteCategories
            .flatMap { V2RayGeoDataParser.parseDomainCategory(geoSiteResponse.body, it) }
            .distinct()
        onProgress?.invoke(UpdateProgress.Validating)
        val resolvedAddresses = resolveDomains(domainRules)

        rootDirectory.mkdirs()
        val activeName = readActiveSlotName()
        val targetName = if (activeName == SLOT_A) SLOT_B else SLOT_A
        val target = File(rootDirectory, targetName).also { it.mkdirs() }

        replaceAtomically(File(target, GEOIP_FILE), geoIpResponse.body)
        replaceAtomically(File(target, GEOSITE_FILE), geoSiteResponse.body)
        replaceAtomically(
            File(target, CIDRS_FILE),
            cidrs.joinToString("\n", postfix = "\n") { it.toString() }.toByteArray(StandardCharsets.UTF_8),
        )
        replaceAtomically(
            File(target, DOMAINS_FILE),
            domainRules.joinToString("\n", postfix = "\n") { "${it.type.name}\t${it.value}" }
                .toByteArray(StandardCharsets.UTF_8),
        )
        replaceAtomically(
            File(target, DOMAIN_ADDRESSES_FILE),
            resolvedAddresses.joinToString("\n", postfix = if (resolvedAddresses.isEmpty()) "" else "\n")
                .toByteArray(StandardCharsets.UTF_8),
        )

        val status = Status(System.currentTimeMillis(), cidrs.size, domainRules.size, resolvedAddresses.size)
        replaceAtomically(
            File(target, STATUS_FILE),
            JSONObject()
                .put("updatedAt", status.updatedAt)
                .put("cidrCount", status.cidrCount)
                .put("domainCount", status.domainCount)
                .put("resolvedAddressCount", status.resolvedAddressCount)
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )

        // Validate the complete inactive slot once more before the one-file activation commit.
        readRoutes(target)
        replaceAtomically(activeSlotFile, "$targetName\n".toByteArray(StandardCharsets.UTF_8))
        Log.i(
            TAG,
            "GeoIP/GeoSite data updated: cidrs=${status.cidrCount}, domains=${status.domainCount}, resolved=${status.resolvedAddressCount}",
        )
        status
    }

    /** Uses a valid cache or downloads it on the first start of a profile that needs ru-direct. */
    suspend fun ensureDirectRoutes(): List<InetNetwork> = withContext(Dispatchers.IO) {
        try {
            return@withContext requireDirectRoutes().also {
                Log.i(TAG, "Using cached ru-direct routes: ${it.size} destinations")
            }
        } catch (_: RoutingListsMissingException) {
            // A first-use download is required only for a profile carrying the routing directive.
            Log.i(TAG, "No cached ru-direct data; downloading GeoIP/GeoSite before tunnel start")
        }
        try {
            update()
            requireDirectRoutes().also {
                Log.i(TAG, "Prepared ru-direct routes after download: ${it.size} destinations")
            }
        } catch (e: Throwable) {
            if (e is RoutingListsMissingException) throw e
            throw RoutingListsMissingException(context.getString(R.string.routing_lists_download_failed_error), e)
        }
    }

    /** Returns mandatory RU CIDRs and best-effort addresses resolved from CATEGORY-RU domains. */
    fun requireDirectRoutes(): List<InetNetwork> {
        val active = activeSlot()
            ?: throw RoutingListsMissingException(context.getString(R.string.routing_lists_missing_error))
        return try {
            readRoutes(active)
        } catch (e: RoutingListsMissingException) {
            throw e
        } catch (e: Throwable) {
            throw RoutingListsMissingException(context.getString(R.string.routing_lists_invalid_error), e)
        }
    }

    /** Lightweight startup check; full validation still happens before a ru-direct tunnel starts. */
    fun hasData(): Boolean {
        val slot = activeSlot() ?: return false
        return listOf(GEOIP_FILE, GEOSITE_FILE, CIDRS_FILE, STATUS_FILE)
            .all { name -> File(slot, name).let { it.isFile && it.length() > 0L } }
    }

    fun status(): Status? {
        val active = activeSlot() ?: return null
        val statusFile = File(active, STATUS_FILE)
        if (!statusFile.isFile) return null
        return try {
            val json = JSONObject(statusFile.readText(StandardCharsets.UTF_8))
            Status(
                json.getLong("updatedAt"),
                json.getInt("cidrCount"),
                json.getInt("domainCount"),
                json.getInt("resolvedAddressCount"),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to read GeoIP/GeoSite status", e)
            null
        }
    }

    private fun readRoutes(slot: File): List<InetNetwork> {
        val geoIpFile = File(slot, GEOIP_FILE)
        val geoSiteFile = File(slot, GEOSITE_FILE)
        val cidrFile = File(slot, CIDRS_FILE)
        val domainAddressesFile = File(slot, DOMAIN_ADDRESSES_FILE)
        if (!geoIpFile.isFile || geoIpFile.length() == 0L || !geoSiteFile.isFile || geoSiteFile.length() == 0L ||
            !cidrFile.isFile || cidrFile.length() == 0L
        ) {
            throw RoutingListsMissingException(context.getString(R.string.routing_lists_missing_error))
        }
        val cidrs = parseCachedNetworks(cidrFile, required = true)
        val domainAddresses = parseCachedNetworks(domainAddressesFile, required = false)
        return (cidrs + domainAddresses).distinct()
    }

    private fun parseCachedNetworks(file: File, required: Boolean): List<InetNetwork> {
        if (!file.isFile) {
            if (required) throw IOException("${file.name} is missing")
            return emptyList()
        }
        val result = file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    try {
                        InetNetwork.parse(it)
                    } catch (e: Throwable) {
                        throw IOException("${file.name} contains an invalid network", e)
                    }
                }
                .toList()
        }.distinct()
        if (required && result.isEmpty()) throw IOException("${file.name} is empty")
        return result
    }

    private suspend fun resolveDomains(rules: List<DomainRule>): List<InetNetwork> = coroutineScope {
        val domains = rules.asSequence()
            .filter { it.type == DomainType.DOMAIN || it.type == DomainType.FULL }
            .mapNotNull { normalizeDomain(it.value) }
            .distinct()
            .toList()
        val semaphore = Semaphore(DNS_CONCURRENCY)
        domains.map { domain ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        InetAddress.getAllByName(domain).map { address ->
                            InetNetwork.parse("${address.hostAddress}/${if (address.address.size == 4) 32 else 128}")
                        }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten().distinct().sortedBy { it.toString() }
    }

    private fun normalizeDomain(raw: String): String? = try {
        IDN.toASCII(raw.removeSuffix("."), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ENGLISH)
            .takeIf { DOMAIN_REGEX.matches(it) }
    } catch (_: Throwable) {
        null
    }

    private fun activeSlot(): File? {
        val name = readActiveSlotName() ?: return null
        return File(rootDirectory, name).takeIf { it.isDirectory }
    }

    private fun readActiveSlotName(): String? {
        if (!activeSlotFile.isFile) return null
        return try {
            activeSlotFile.readText(StandardCharsets.UTF_8).trim().takeIf { it == SLOT_A || it == SLOT_B }
        } catch (_: Throwable) {
            null
        }
    }

    private fun replaceAtomically(target: File, contents: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(contents)
            output.fd.sync()
        }
        try {
            Os.rename(temporary.path, target.path)
        } catch (e: ErrnoException) {
            temporary.delete()
            throw IOException("Unable to replace ${target.name}", e)
        }
    }

    private val rootDirectory = File(context.filesDir, "routing-lists")
    private val activeSlotFile = File(rootDirectory, "active-slot")

    companion object {
        private const val TAG = "RabbitHole/GeoRouting"
        const val DEFAULT_GEOIP_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
        const val DEFAULT_GEOSITE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
        val GEOIP_URL_KEY = stringPreferencesKey("routing_geoip_url")
        val GEOSITE_URL_KEY = stringPreferencesKey("routing_geosite_url")
        val GEOIP_CATEGORIES_KEY = stringPreferencesKey("routing_geoip_categories")
        val GEOSITE_CATEGORIES_KEY = stringPreferencesKey("routing_geosite_categories")
        val DEFAULT_GEOIP_CATEGORIES = listOf("RU")
        val DEFAULT_GEOSITE_CATEGORIES = listOf("CATEGORY-RU")
        private const val MAX_GEOIP_BYTES = 32 * 1024 * 1024
        private const val MAX_GEOSITE_BYTES = 32 * 1024 * 1024
        private const val DNS_CONCURRENCY = 24
        private const val SLOT_A = "slot-a"
        private const val SLOT_B = "slot-b"
        private const val GEOIP_FILE = "geoip.dat"
        private const val GEOSITE_FILE = "geosite.dat"
        private const val CIDRS_FILE = "direct-cidrs.lst"
        private const val DOMAINS_FILE = "direct-domains.lst"
        private const val DOMAIN_ADDRESSES_FILE = "domain-addresses.lst"
        private const val STATUS_FILE = "status.json"
        private val DOMAIN_REGEX = Regex("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    }
}
