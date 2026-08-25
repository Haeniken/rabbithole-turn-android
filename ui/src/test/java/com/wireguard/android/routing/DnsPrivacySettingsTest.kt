/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.crypto.KeyPair
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPrivacySettingsTest {
    @Test
    fun adGuardReplacementPreservesNonDnsInterfaceSettings() {
        val source = Interface.Builder()
            .setKeyPair(KeyPair())
            .addDnsServer(InetAddress.getByName("8.8.8.8"))
            .addDnsSearchDomain("internal.example")
            .setListenPort(51820)
            .setMtu(1360)
            .build()
        val result = DnsPrivacySettings.apply(Config.Builder().setInterface(source).build()).`interface`

        assertEquals(setOf("94.140.14.14", "94.140.15.15"), result.dnsServers.mapTo(linkedSetOf()) { it.hostAddress })
        assertEquals(source.dnsSearchDomains, result.dnsSearchDomains)
        assertEquals(source.keyPair.privateKey, result.keyPair.privateKey)
        assertEquals(source.listenPort, result.listenPort)
        assertEquals(source.mtu, result.mtu)
    }
}
