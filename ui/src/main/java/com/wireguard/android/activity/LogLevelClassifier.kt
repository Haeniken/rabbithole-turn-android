/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import java.util.Locale

/** Normalizes native lifecycle messages that may otherwise look like failures to the log UI. */
internal fun effectiveLogLevel(rawLevel: String, rawMessage: String): String {
    val message = rawMessage.lowercase(Locale.ROOT)
    if (message.contains("[proxy] hub starting")) return "I"
    if (rawLevel != "I") return rawLevel
    if (
        (message.contains("[dns] server") && message.contains(" failed:")) ||
        message.contains("getcallpreview request failed:") ||
        message.contains("auto captcha failed") ||
        message.contains("watchdog recycling stream") ||
        message.contains("use of closed network connection") ||
        message.contains("read/write on closed pipe")
    ) return "W"
    return if (
        listOf(
            " error:",
            " failed:",
            " failed ",
            "unable to ",
            "cannot ",
            "could not ",
            "exception",
            "invalid resource",
            "timed out",
            "timeout",
        )
            .any(message::contains)
    ) "E" else rawLevel
}
