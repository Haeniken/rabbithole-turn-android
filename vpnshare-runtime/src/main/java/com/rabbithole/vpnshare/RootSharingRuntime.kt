/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.rabbithole.vpnshare

import android.content.Context
import androidx.core.content.edit
import be.mygod.vpnhotspot.App
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.root.daemon.MasqueradeMode
import kotlinx.coroutines.runBlocking

/** Transparent root routing backed by the pinned VPNHotspot daemon. */
object RootSharingRuntime {
    private const val KEY_ROOT_DNS = "service.upstream.rootDns"
    private val routings = linkedMapOf<String, Routing>()

    @JvmStatic
    @Synchronized
    fun sync(context: Context, downstreamInterfaces: Set<String>, dnsServers: String) {
        val app = App.ensureInitialized(context)
        app.pref.edit(commit = true) {
            remove("service.upstream") // VPN Network is the primary upstream.
            remove("service.upstream.fallback")
            if (dnsServers.isBlank()) remove(KEY_ROOT_DNS) else putString(KEY_ROOT_DNS, dnsServers)
            putBoolean("service.disableIpv6", true)
            putString("service.masqueradeMode", "Simple")
        }

        val wanted = downstreamInterfaces.map(String::trim).filter(String::isNotEmpty).toSet()
        val iterator = routings.iterator()
        while (iterator.hasNext()) {
            val (name, routing) = iterator.next()
            if (name !in wanted) {
                runBlocking { routing.revert() }
                iterator.remove()
            }
        }
        for (name in wanted - routings.keys) {
            val routing = Routing(RootSharingRuntime, name).apply {
                ipForward = true
                masqueradeMode = MasqueradeMode.MASQUERADE_MODE_SIMPLE
                ipv6Mode = Routing.Ipv6Mode.Block
            }
            if (routing.start()) routings[name] = routing
        }
    }

    @JvmStatic
    @Synchronized
    fun stop(context: Context) {
        App.ensureInitialized(context)
        routings.values.forEach { runBlocking { it.revert() } }
        routings.clear()
        runBlocking { Routing.clean() }
    }
}
