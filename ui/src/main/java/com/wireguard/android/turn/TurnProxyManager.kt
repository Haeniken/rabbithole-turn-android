/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.TurnBackend
import com.wireguard.android.util.CaptchaBrowserProfile
import com.wireguard.android.util.DetailedDiagnostics
import com.wireguard.android.util.OptionalTurnUdp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.collectLatest

/**
 * Lightweight manager for per-tunnel TURN client processes and logs.
 *
 * Uses PhysicalNetworkMonitor to track stable internet connections and 
 * triggers restarts when the underlying network or IP changes.
 */
class TurnProxyManager(
    private val context: Context,
    private val connectionStateMachine: ConnectionStateMachine,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    sealed interface TurnStartResult {
        data object Success : TurnStartResult
        data object Cancelled : TurnStartResult
        data class Failure(val code: Int, val message: String) : TurnStartResult
    }
    
    // State
    @Volatile private var activeSession: ConnectionStateMachine.Session? = null
    private var activeSettings: TurnSettings? = null
    @Volatile private var userInitiatedStop: Boolean = false
    
    // Network tracking
    private val networkMonitor = PhysicalNetworkMonitor(context)
    @Volatile private var lastKnownNetwork: Network? = null
    
    init {
        networkMonitor.start()
        
        scope.launch {
            networkMonitor.bestNetwork.collectLatest { network ->
                if (network != null) {
                    handleNetworkChange(network)
                }
            }
        }
    }

    /**
     * Central handler for network changes from PhysicalNetworkMonitor.
     * The monitor already provides debounced stable networks.
     */
    private suspend fun handleNetworkChange(network: Network) {
        val session = activeSession ?: return
        if (userInitiatedStop || !connectionStateMachine.isCurrent(session)) return

        // 1. Initial baseline setting
        if (lastKnownNetwork == null) {
            Log.d(TAG, "Setting initial network baseline: $network")
            lastKnownNetwork = network
            return
        }

        // 2. Stability check
        if (lastKnownNetwork == network) {
            Log.d(TAG, "Network state stable for $network")
            return
        }

        // 3. Real change confirmed. Keep the established pool alive only while
        // Android still reports the old physical path. Otherwise skip straight
        // to a restart instead of waiting on sockets bound to a vanished handle.
        val oldNetwork = lastKnownNetwork
        if (!isNetworkAvailable(oldNetwork)) {
            Log.w(TAG, "Old physical network $oldNetwork is gone; restarting directly on $network")
            lastKnownNetwork = networkMonitor.currentNetwork
            connectionStateMachine.transition(
                session,
                ConnectionStateMachine.Phase.DEGRADED,
                "Physical network changed",
            )
            performRestartSequence(session)
            return
        }
        val networkHandle = network.getNetworkHandle()
        Log.d(TAG, "Network change confirmed: $oldNetwork -> $network. Preparing make-before-break handover.")
        connectionStateMachine.transition(session, ConnectionStateMachine.Phase.HANDING_OVER_NETWORK)
        val handoverResult = withContext(Dispatchers.IO) {
            operationMutex.lock()
            try {
                if (userInitiatedStop || activeSession != session || !connectionStateMachine.isCurrent(session)) {
                    TurnBackend.WG_TURN_PROXY_ERROR_GENERIC
                } else {
                    TurnBackend.wgTurnProxyHandover(networkHandle)
                }
            } finally {
                operationMutex.unlock()
            }
        }
        val latestNetwork = networkMonitor.currentNetwork
        lastKnownNetwork = latestNetwork
        if (
            handoverResult == TurnBackend.WG_TURN_PROXY_SUCCESS &&
            latestNetwork == network &&
            isNetworkAvailable(network)
        ) {
            Log.d(TAG, "TURN make-before-break handover completed on $network")
            connectionStateMachine.transition(session, ConnectionStateMachine.Phase.CONNECTED)
            return
        }

        // A disappeared old network or a credential/captcha timeout can make
        // overlap impossible. Preserve the previous restart path as a bounded
        // fallback instead of leaving the tunnel on a dead socket generation.
        Log.w(
            TAG,
            "Native handover failed or target vanished ($handoverResult, latest=$latestNetwork); " +
                "falling back to a full TURN restart",
        )
        connectionStateMachine.transition(
            session,
            ConnectionStateMachine.Phase.DEGRADED,
            "Network handover failed ($handoverResult)",
        )
        performRestartSequence(session)
    }

    private suspend fun performRestartSequence(session: ConnectionStateMachine.Session) {
        if (userInitiatedStop || activeSession != session || !connectionStateMachine.isCurrent(session)) return

        Log.d(TAG, "Stopping TURN proxy for restart...")
        TurnBackend.wgTurnProxyStop()
        
        // Critical: Notify Go backend to clear internal socket states/DNS cache
        Log.d(TAG, "Notifying Go layer of network change...")
        TurnBackend.wgNotifyNetworkChange()
        
        delay(500) // Give Go minimal time to react

        val settings = activeSettings ?: return

        var attempts = 0
        while (
            currentCoroutineContext().isActive &&
            !userInitiatedStop &&
            activeSession == session &&
            connectionStateMachine.isCurrent(session)
        ) {
            attempts++
            Log.d(TAG, "Starting TURN for ${session.tunnelName} (Attempt $attempts)")
            
            when (val result = startForTunnelInternal(session, settings)) {
                TurnStartResult.Success -> {
                    Log.d(TAG, "TURN restarted successfully on attempt $attempts")
                    connectionStateMachine.transition(session, ConnectionStateMachine.Phase.CONNECTED)
                    return // Exit loop on success
                }
                TurnStartResult.Cancelled -> {
                    Log.d(TAG, "TURN restart cancelled")
                    return
                }
                is TurnStartResult.Failure -> {
                    Log.w(TAG, "TURN restart attempt $attempts failed: ${result.message}")
                }
            }

            // Exponential backoff logic
            val delayMs = when {
                attempts <= 2 -> 2000L
                attempts <= 5 -> 5000L
                else -> 15000L
            }
            Log.w(TAG, "Restart failed, retrying in ${delayMs}ms...")
            delay(delayMs)
        }
    }

    private data class Instance(
        val log: StringBuilder = StringBuilder(),
        @Volatile var running: Boolean = false,
    )

    private val instances = ConcurrentHashMap<String, Instance>()
    // Mutex to serialize start/stop operations and prevent race conditions between
    // onTunnelEstablished and handleNetworkChange
    private val operationMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Called once VpnService is ready and TURN should gate WireGuard startup.
     */
    suspend fun onTunnelEstablished(
        session: ConnectionStateMachine.Session,
        turnSettings: TurnSettings?,
    ): TurnStartResult {
        val tunnelName = session.tunnelName
        Log.d(TAG, "onTunnelEstablished called for tunnel: $tunnelName generation=${session.generation}")

        if (!connectionStateMachine.isCurrent(session)) return TurnStartResult.Cancelled

        // Reset state for new session
        activeSession = session
        activeSettings = turnSettings
        userInitiatedStop = false
        
        // Initialize network baseline for the new session
        lastKnownNetwork = networkMonitor.currentNetwork
        Log.d(TAG, "Initial network for tunnel session: $lastKnownNetwork")

        if (turnSettings == null || !turnSettings.enabled) {
            Log.d(TAG, "TURN not enabled, skipping")
            return TurnStartResult.Success
        }

        val result = startForTunnelInternal(session, turnSettings)

        if (result == TurnStartResult.Success) {
            // After initial start, allow network changes to trigger restarts.
            // We delay slightly to ensure we don't catch the immediate network fluctuation caused by VPN itself.
            scope.launch {
                delay(2000)
                if (activeSession == session && connectionStateMachine.isCurrent(session))
                    Log.d(TAG, "Initialization phase complete, network monitoring active")
            }
        }

        return result
    }

    suspend fun startForTunnel(tunnelName: String, settings: TurnSettings): TurnStartResult {
        val session = connectionStateMachine.begin(tunnelName)
        return onTunnelEstablished(session, settings)
    }
    
    private suspend fun startForTunnelInternal(
        session: ConnectionStateMachine.Session,
        settings: TurnSettings,
    ): TurnStartResult =
        withContext(Dispatchers.IO) {
            val tunnelName = session.tunnelName
            operationMutex.lock()
            try {
                if (
                    !currentCoroutineContext().isActive ||
                    userInitiatedStop ||
                    activeSession != session ||
                    !connectionStateMachine.isCurrent(session)
                ) {
                    Log.d(TAG, "startForTunnelInternal cancelled before execution")
                    return@withContext TurnStartResult.Cancelled
                }

                val instance = instances.getOrPut(tunnelName) { Instance() }
                instance.running = false

                Log.d(TAG, "Stopping any existing TURN proxy...")
                TurnBackend.wgTurnProxyStop()
                // Give Go runtime a moment to fully clean up goroutines
                delay(200)
                if (userInitiatedStop || activeSession != session || !connectionStateMachine.isCurrent(session))
                    return@withContext TurnStartResult.Cancelled

                // Wait for JNI to be registered
                val jniReady = TurnBackend.waitForVpnServiceRegistered(2000)
                if (!jniReady) {
                    Log.e(TAG, "TIMEOUT waiting for JNI registration!")
                    return@withContext TurnStartResult.Failure(
                        ERROR_VPN_SERVICE_NOT_READY,
                        "TURN startup failed: VpnService was not registered in time"
                    )
                }

                // Re-read immediately before JNI start. A handover may have blocked while
                // Android replaced the candidate network, so lastKnownNetwork is advisory.
                val startNetwork = awaitAvailableNetwork()
                    ?: return@withContext TurnStartResult.Failure(
                        ERROR_PHYSICAL_NETWORK_NOT_READY,
                        "TURN startup postponed: no usable physical network",
                    )
                lastKnownNetwork = startNetwork
                val networkHandle = startNetwork.getNetworkHandle()
                val networkType = getNetworkTypeString(startNetwork)
                val preferences = Application.getPreferencesDataStore()
                val detailedDiagnostics = DetailedDiagnostics.isEnabled(preferences)
                val optionalTurnUdp = OptionalTurnUdp.isEnabled(preferences)
                // Keep accepting the established per-profile UseUDP flag, but
                // give it the same safe semantics as the global preference: UDP is
                // preferred and TCP remains an automatic fallback.
                val turnTransportMode = if (optionalTurnUdp || settings.useUdp) {
                    TURN_TRANSPORT_UDP_WITH_TCP_FALLBACK
                } else {
                    TURN_TRANSPORT_TCP_ONLY
                }
                Log.d(TAG, "Starting TURN proxy for $tunnelName with network: $startNetwork (type=$networkType, handle=$networkHandle)")
                Log.d(TAG, "Detailed TURN diagnostics: ${if (detailedDiagnostics) "enabled" else "disabled"}")
                Log.d(TAG, "TURN transport: ${describeTransportMode(turnTransportMode)}")
                connectionStateMachine.transition(session, ConnectionStateMachine.Phase.AUTHORIZING)
                connectionStateMachine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TRANSPORT)

                val ret = TurnBackend.wgTurnProxyStart(
                    settings.peer, settings.vkLink, "vk_link", settings.streams,
                    turnTransportMode,
                    "127.0.0.1:${settings.localPort}",
                    settings.turnIp,
                    settings.turnPort,
                    settings.peerType,
                    settings.streamsPerCred,
                    settings.watchdogTimeout,
                    if (settings.useWrap) 1 else 0,
                    settings.wrapKeyHex,
                    CaptchaBrowserProfile.get(context).toJson(),
                    if (detailedDiagnostics) 1 else 0,
                    networkHandle
                )

                val listenAddr = "127.0.0.1:${settings.localPort}"
                if (
                    userInitiatedStop ||
                    !currentCoroutineContext().isActive ||
                    activeSession != session ||
                    !connectionStateMachine.isCurrent(session)
                ) {
                    instance.running = false
                    Log.d(TAG, "TURN startup cancelled for $tunnelName")
                    // stopForTunnel() may have raced with the blocking native start and issued
                    // its stop before the new native generation became visible. Stop once more
                    // after start returns so a superseded generation cannot remain alive.
                    TurnBackend.wgTurnProxyStop()
                    TurnStartResult.Cancelled
                } else if (ret == TurnBackend.WG_TURN_PROXY_SUCCESS) {
                    instance.running = true
                    connectionStateMachine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TUNNEL)
                    val msg = "TURN started for tunnel \"$tunnelName\" listening on $listenAddr"
                    Log.d(TAG, msg)
                    appendLogLine(tunnelName, msg)
                    TurnStartResult.Success
                } else {
                    val msg = when (ret) {
                        TurnBackend.WG_TURN_PROXY_ERROR_VK_LINK_EXPIRED ->
                            "TURN startup failed: VK call link expired"
                        else -> "Failed to start TURN proxy (error $ret)"
                    }
                    Log.e(TAG, msg)
                    appendLogLine(tunnelName, msg)
                    TurnStartResult.Failure(ret, msg)
                }
            } finally {
                operationMutex.unlock()
            }
        }

    suspend fun stopForTunnel(
        tunnelName: String,
        expectedSession: ConnectionStateMachine.Session? = null,
    ) =
        withContext(Dispatchers.IO) {
            val session = activeSession
            if (session?.tunnelName != tunnelName) return@withContext
            if (expectedSession != null && session != expectedSession) return@withContext

            userInitiatedStop = true
            activeSession = null
            activeSettings = null
            lastKnownNetwork = null

            // Stop TURN proxy BEFORE acquiring mutex to avoid deadlock with startup wait
            TurnBackend.wgTurnProxyStop()

            operationMutex.lock()
            try {
                val instance = instances[tunnelName] ?: return@withContext
                instance.running = false
                val msg = "TURN stopped for tunnel \"$tunnelName\""
                Log.d(TAG, msg)
                appendLogLine(tunnelName, msg)
            } finally {
                operationMutex.unlock()
            }
        }

    fun isRunning(tunnelName: String): Boolean {
        return instances[tunnelName]?.running == true
    }

    fun onVpnServiceTerminated(reason: String) {
        val session = activeSession ?: connectionStateMachine.currentSession() ?: return
        activeSession = null
        activeSettings = null
        lastKnownNetwork = null
        instances[session.tunnelName]?.running = false
        appendLogLine(session.tunnelName, "VpnService terminated unexpectedly: $reason")
        connectionStateMachine.fail(session, "VpnService $reason")
    }

    fun getLog(tunnelName: String): String {
        return instances[tunnelName]?.log?.toString() ?: ""
    }

    fun clearLog(tunnelName: String) {
        instances[tunnelName]?.log?.setLength(0)
    }

    fun appendLogLine(tunnelName: String, line: String) {
        val instance = instances.getOrPut(tunnelName) { Instance() }
        val builder = instance.log
        synchronized(builder) {
            if (builder.isNotEmpty()) {
                builder.append('\n')
            }
            builder.append(line)
            if (builder.length > MAX_LOG_CHARS) builder.delete(0, builder.length - MAX_LOG_CHARS)
        }
    }

    /**
     * Returns a string representation of the network type (wifi, cellular, lan, unknown).
     */
    private fun getNetworkTypeString(network: Network?): String {
        if (network == null) return "unknown"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network)

        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "lan"
            else -> "unknown"
        }
    }

    private fun isNetworkAvailable(network: Network?): Boolean {
        if (network == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private suspend fun awaitAvailableNetwork(): Network? {
        repeat(PHYSICAL_NETWORK_RECHECKS) {
            val candidate = networkMonitor.currentNetwork
            if (isNetworkAvailable(candidate)) return candidate
            delay(PHYSICAL_NETWORK_RECHECK_DELAY_MS)
        }
        return null
    }

    companion object {
        private const val TURN_TRANSPORT_TCP_ONLY = 0
        private const val TURN_TRANSPORT_UDP_WITH_TCP_FALLBACK = 1

        private fun describeTransportMode(mode: Int): String = when (mode) {
            TURN_TRANSPORT_UDP_WITH_TCP_FALLBACK -> "UDP preferred with TCP fallback"
            else -> "TCP only"
        }

        private const val TAG = "WireGuard/TurnProxyManager"
        private const val MAX_LOG_CHARS = 128 * 1024
        private const val ERROR_VPN_SERVICE_NOT_READY = -1001
        private const val ERROR_PHYSICAL_NETWORK_NOT_READY = -1002
        private const val PHYSICAL_NETWORK_RECHECKS = 5
        private const val PHYSICAL_NETWORK_RECHECK_DELAY_MS = 200L
    }
}
