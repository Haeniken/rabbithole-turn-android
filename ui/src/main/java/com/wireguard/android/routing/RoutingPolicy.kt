/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import com.wireguard.config.Config
import java.util.Locale

enum class RoutingPolicy {
    TUNNEL_ALL,
    DIRECT_RUSSIA;

    companion object {
        fun fromConfig(config: Config): RoutingPolicy {
            val value = config.peers.asSequence()
                .flatMap { it.extraLines.asSequence() }
                .map { it.trim() }
                .firstOrNull { it.startsWith(PREFIX, ignoreCase = true) }
                ?.substringAfter('=', "")
                ?.trim()
                ?.lowercase(Locale.ENGLISH)
            return if (value == DIRECT_RUSSIA_VALUE) DIRECT_RUSSIA else TUNNEL_ALL
        }

        const val DIRECT_RUSSIA_COMMENT = "#@rhv:Routing = ru-direct"
        private const val PREFIX = "#@rhv:Routing"
        private const val DIRECT_RUSSIA_VALUE = "ru-direct"
    }
}
