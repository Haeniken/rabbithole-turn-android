package com.wireguard.android.turn

import com.wireguard.config.InetEndpoint
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToLong

/** Checks the server's HTTPS listener without starting VK or WireGuard. */
internal object TurnServerLatency {
    fun target(settings: TurnSettings): InetSocketAddress {
        if (!settings.enabled || settings.peer.isBlank()) throw IOException("TURN server is not configured")
        val host = InetEndpoint.parse(settings.peer).host.removePrefix("[").removeSuffix("]")
        if (host.isBlank() || host == "localhost" || host.startsWith("127.") || host == "::1")
            throw IOException("No remote TURN server address")
        return InetSocketAddress.createUnresolved(host, 443)
    }

    fun measure(settings: TurnSettings, timeoutMs: Int): Long {
        val target = target(settings)
        val address = InetSocketAddress(target.hostString, target.port)
        val startedAt = System.nanoTime()
        Socket().use { it.connect(address, timeoutMs) }
        return ((System.nanoTime() - startedAt) / 1_000_000.0).roundToLong().coerceAtLeast(1)
    }
}
