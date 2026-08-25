/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors physical networks (WiFi, Cellular) and provides the "best" available one.
 * Ignores VPN interfaces to avoid tracking our own tunnel.
 * Priority: WiFi > Cellular.
 */
class PhysicalNetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _bestNetwork = MutableStateFlow<Network?>(null)
    
    /**
     * Flow of the best available physical network.
     * Includes a 1500ms debounce to filter out rapid transitions and flickering.
     */
    val bestNetwork = _bestNetwork.asStateFlow()
        .debounce(1500)
        .distinctUntilChanged()

    /**
     * Synchronously get the current best network without debounce.
     */
    val currentNetwork: Network?
        get() {
            update()
            return _bestNetwork.value
        }

    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Ignore VPNs to avoid feedback loops with our own tunnel
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            
            // We only care about networks with internet
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                networks.remove(network)
            } else {
                networks[network] = caps
            }
            update()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            update()
        }
    }

    private fun update() {
        // A delayed onLost callback must not leave a vanished Android network handle selected.
        // Prefer validated physical paths, but keep an INTERNET-capable fallback for networks
        // whose OEM validation endpoint is unavailable.
        val available = networks.keys.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (
                caps == null ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                networks.remove(network)
                null
            } else {
                networks[network] = caps
                network to caps
            }
        }
        val validated = available.filter { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val candidates = validated.ifEmpty { available }
        val wifi = candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }?.first
        val cell = candidates.firstOrNull { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }?.first
        
        // If no WiFi/Cellular found, take the first available network from our list
        val best = wifi ?: cell ?: candidates.firstOrNull()?.first
        _bestNetwork.value = best
    }

    fun start() {
        // Initial state: identify current best physical network before registering callback
        // We look through all networks because activeNetwork might be the VPN itself
        cm.allNetworks.forEach { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && 
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && 
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                networks[network] = caps
            }
        }
        update()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Ignore
        }
        networks.clear()
        _bestNetwork.value = null
    }
}
