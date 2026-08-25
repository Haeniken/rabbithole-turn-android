/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SharingPrefixTest {
    @Test
    fun wlanRequiresAnExplicitTetheringBroadcast() {
        assertTrue(SharingType.WIFI.matches("wlan0"))
        assertFalse(SharingType.WIFI.matchesFallback("wlan0"))
        assertTrue(SharingType.WIFI.matchesFallback("ap0"))
    }

    @Test
    fun ipv4PrefixAcceptsOnlyTetherSubnet() {
        val prefix = SharingPrefix(InetAddress.getByName("192.168.43.1"), 24)

        assertTrue(prefix.contains(InetAddress.getByName("192.168.43.87")))
        assertFalse(prefix.contains(InetAddress.getByName("192.168.44.87")))
        assertFalse(prefix.contains(InetAddress.getByName("2001:db8::1")))
    }

    @Test
    fun ipv6PrefixUsesPartialByteMask() {
        val prefix = SharingPrefix(InetAddress.getByName("2001:db8:1234::1"), 61)

        assertTrue(prefix.contains(InetAddress.getByName("2001:db8:1234:7::2")))
        assertFalse(prefix.contains(InetAddress.getByName("2001:db8:1234:8::2")))
    }
}
