/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sharing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class SharingType(
    val preferenceName: String,
    val tetheringType: Int,
    private val interfacePattern: Regex,
    private val fallbackPattern: Regex = interfacePattern,
) {
    WIFI(
        "sharing_wifi",
        0,
        Regex("^(ap|softap|swlan|wlan)[0-9._-]*$", RegexOption.IGNORE_CASE),
        // wlan0 is commonly the phone's ordinary Wi-Fi uplink. Only accept it when Android
        // explicitly reports it as tethered, never through the broadcast-less fallback.
        Regex("^(ap|softap|swlan)[0-9._-]*$", RegexOption.IGNORE_CASE),
    ),
    USB("sharing_usb", 1, Regex("^(rndis|usb)[0-9._-]*$", RegexOption.IGNORE_CASE)),
    BLUETOOTH("sharing_bluetooth", 2, Regex("^(bt-pan|bnep)[0-9._-]*$", RegexOption.IGNORE_CASE)),
    ETHERNET("sharing_ethernet", 5, Regex("^eth[0-9._-]*$", RegexOption.IGNORE_CASE));

    val preferenceKey = booleanPreferencesKey(preferenceName)

    fun matches(interfaceName: String): Boolean = interfacePattern.matches(interfaceName)
    fun matchesFallback(interfaceName: String): Boolean = fallbackPattern.matches(interfaceName)
}

data class SharingSettings(
    val enabledTypes: Set<SharingType>,
    val proxyPort: Int,
) {
    val enabled: Boolean get() = enabledTypes.isNotEmpty()

    companion object {
        const val DEFAULT_PROXY_PORT = 10808
        const val MIN_PROXY_PORT = 1024
        val PROXY_PORT_KEY = stringPreferencesKey("sharing_proxy_port")

        suspend fun load(store: DataStore<Preferences>): SharingSettings {
            val preferences = store.data.first()
            val types = SharingType.entries.filterTo(linkedSetOf()) { preferences[it.preferenceKey] == true }
            val port = preferences[PROXY_PORT_KEY]?.toIntOrNull()
                ?.takeIf { it in MIN_PROXY_PORT..65535 }
                ?: DEFAULT_PROXY_PORT
            return SharingSettings(types, port)
        }
    }
}

data class SharingPrefix(val address: InetAddress, val prefixLength: Short) {
    fun contains(candidate: InetAddress): Boolean {
        val network = address.address
        val other = candidate.address
        if (network.size != other.size) return false
        var remaining = prefixLength.toInt()
        for (index in network.indices) {
            if (remaining <= 0) return true
            val bits = minOf(8, remaining)
            val mask = (0xff shl (8 - bits)) and 0xff
            if ((network[index].toInt() and mask) != (other[index].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}

data class DownstreamInterface(
    val name: String,
    val prefixes: List<SharingPrefix>,
) {
    val gatewayAddress: String?
        get() = prefixes.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?: prefixes.firstOrNull()?.address?.hostAddress
}

object SharingNetworkInspector {
    private val safeInterfaceName = Regex("^[A-Za-z0-9_.:-]{1,32}$")

    fun activeDownstreams(
        enabledTypes: Set<SharingType>,
        tetheredInterfaces: Set<String>,
    ): List<DownstreamInterface> {
        if (enabledTypes.isEmpty()) return emptyList()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        return interfaces.mapNotNull { networkInterface ->
            val name = networkInterface.name ?: return@mapNotNull null
            if (!networkInterface.isUp || networkInterface.isLoopback || !safeInterfaceName.matches(name)) return@mapNotNull null
            val typeMatches = enabledTypes.any { it.matches(name) }
            if (!typeMatches) return@mapNotNull null
            val confirmedTether = name in tetheredInterfaces
            val safeFallback = tetheredInterfaces.isEmpty() && enabledTypes.any {
                it != SharingType.ETHERNET && it.matchesFallback(name)
            }
            if (!confirmedTether && !safeFallback) return@mapNotNull null
            val prefixes = networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                val address = interfaceAddress.address ?: return@mapNotNull null
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
                SharingPrefix(address, interfaceAddress.networkPrefixLength)
            }
            if (prefixes.isEmpty()) null else DownstreamInterface(name, prefixes)
        }.sortedBy { it.name }
    }

    fun vpnInterface(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivity.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            connectivity.getLinkProperties(network)?.interfaceName?.takeIf(safeInterfaceName::matches)
        }
    }
}
