/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import java.util.Locale

internal enum class LogGroupKind {
    TUNNEL,
    TURN,
    SUBSCRIPTION,
    ROUTING,
    APP,
}

internal fun classifyLogGroup(tag: String, message: String): LogGroupKind {
    val searchable = "$tag $message".lowercase(Locale.ROOT)
    return when {
        searchable.containsAny("subscription", "subscrib", "profile update") -> LogGroupKind.SUBSCRIPTION
        searchable.containsAny(
            "routinglist",
            "routing list",
            "routeexcluder",
            "excluded route",
            "direct route",
            "direct destination",
            "vpn destination routes",
            "georouting",
            "geoip",
            "geosite",
            "ru-direct",
        ) -> LogGroupKind.ROUTING
        searchable.containsAny("turn", "captcha", "coturn", "webview") -> LogGroupKind.TURN
        tag == "WireGuard/Application" ||
            tag == "AndroidRuntime" ||
            searchable.containsAny("settingsactivity", "logvieweractivity", "rabbithole/application") -> LogGroupKind.APP
        searchable.containsAny("wireguard", "gobackend", "wgquick", "tunnel", "vpnservice", "vpn service") -> LogGroupKind.TUNNEL
        else -> LogGroupKind.APP
    }
}

private fun String.containsAny(vararg needles: String) = needles.any(::contains)
