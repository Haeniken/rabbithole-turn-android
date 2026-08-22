/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import com.wireguard.config.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingPolicyTest {
    @Test
    fun profileWithoutDirectiveKeepsNormalRouting() {
        assertEquals(RoutingPolicy.TUNNEL_ALL, RoutingPolicy.fromConfig(parseConfig(null)))
    }

    @Test
    fun androidDirectiveEnablesRussianDirectRouting() {
        assertEquals(
            RoutingPolicy.DIRECT_RUSSIA,
            RoutingPolicy.fromConfig(parseConfig(RoutingPolicy.DIRECT_RUSSIA_COMMENT)),
        )
    }

    private fun parseConfig(directive: String?): Config {
        val extra = directive?.let { "$it\n" } ?: ""
        return Config.parse(
            """
            [Interface]
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            Address = 192.0.2.2/32

            [Peer]
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
            AllowedIPs = 0.0.0.0/0, ::/0
            $extra
            """.trimIndent().byteInputStream(),
        )
    }
}
