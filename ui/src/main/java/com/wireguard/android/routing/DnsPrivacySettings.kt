/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide DNS policy mirrored from settings for the synchronous VPN startup path. */
object DnsPrivacySettings {
    const val ADGUARD_ENABLED_KEY = "adguard_dns_enabled"
    const val PRIMARY = "94.140.14.14"
    const val SECONDARY = "94.140.15.15"
    const val SERVERS = "$PRIMARY,$SECONDARY"

    private val adGuardEnabled = AtomicBoolean(true)

    fun observe(dataStore: DataStore<Preferences>, scope: CoroutineScope) {
        dataStore.data.map { it[booleanPreferencesKey(ADGUARD_ENABLED_KEY)] ?: true }
            .distinctUntilChanged()
            .onEach(adGuardEnabled::set)
            .launchIn(scope)
    }

    fun isAdGuardEnabled(): Boolean = adGuardEnabled.get()
    /** Replaces profile DNS only in the effective in-memory configuration. The saved profile is unchanged. */
    fun apply(config: Config): Config {
        if (!adGuardEnabled.get()) return config
        val source = config.`interface`
        val iface = Interface.Builder()
            .addAddresses(source.addresses)
            .addDnsServer(InetAddress.getByAddress(byteArrayOf(94, 140.toByte(), 14, 14)))
            .addDnsServer(InetAddress.getByAddress(byteArrayOf(94, 140.toByte(), 15, 15)))
            .addDnsSearchDomains(source.dnsSearchDomains)
            .setKeyPair(source.keyPair)
            .excludeApplications(source.excludedApplications)
            .includeApplications(source.includedApplications)
        source.listenPort.ifPresent(iface::setListenPort)
        source.mtu.ifPresent(iface::setMtu)
        return Config.Builder().setInterface(iface.build()).addPeers(config.peers).build()
    }
}
