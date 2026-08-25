/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application
import com.wireguard.android.Application.Companion.get
import com.wireguard.android.Application.Companion.getBackend
import com.wireguard.android.Application.Companion.getTunnelManager
import com.wireguard.android.Application.Companion.getTurnProxyManager
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.configStore.FileConfigStore
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.routing.ManualRouteExclusions
import com.wireguard.android.routing.DnsPrivacySettings
import com.wireguard.android.routing.RoutingListUpdateWorker
import com.wireguard.android.routing.RoutingPolicy
import com.wireguard.android.sharing.SharingController
import com.wireguard.android.turn.TurnConfigProcessor
import com.wireguard.android.turn.ConnectionStateMachine
import com.wireguard.android.turn.TurnProxyManager
import com.wireguard.android.turn.TurnSettings
import com.wireguard.android.turn.TurnSettingsStore
import com.wireguard.android.subscription.SubscriptionStore
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.GlobalAppExclusions
import com.wireguard.android.util.UserKnobs
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(
    private val configStore: ConfigStore,
    private val turnSettingsStore: TurnSettingsStore,
    private val subscriptionStore: SubscriptionStore,
    private val connectionStateMachine: ConnectionStateMachine,
) : BaseObservable() {
    private val tunnels = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        var turnSettings = turnSettingsStore.load(name)
        if (turnSettings == null && config != null) {
            turnSettings = TurnConfigProcessor.extractTurnSettings(config)
            if (turnSettings != null) {
                turnSettingsStore.save(name, turnSettings)
            }
        }
        tunnel.onTurnSettingsChanged(turnSettings)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun getTunnels(): ObservableSortedKeyedArrayList<String, ObservableTunnel> = tunnels.await()

    suspend fun create(
        name: String,
        config: Config?,
        turnSettings: TurnSettings? = null,
    ): ObservableTunnel = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        
        val configWithTurn = TurnConfigProcessor.injectTurnSettings(config!!, turnSettings)
        val savedConfig = withContext(Dispatchers.IO) { configStore.create(name, configWithTurn) }
        withContext(Dispatchers.IO) { turnSettingsStore.save(name, turnSettings) }
        addToList(name, savedConfig, Tunnel.State.DOWN)
    }

    suspend fun delete(tunnel: ObservableTunnel) = withContext(Dispatchers.Main.immediate) {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        try {
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }
            try {
                withContext(Dispatchers.IO) {
                    configStore.delete(tunnel.name)
                    turnSettingsStore.delete(tunnel.name)
                    subscriptionStore.delete(tunnel.name)
                }
            } catch (e: Throwable) {
                if (originalState == Tunnel.State.UP)
                    withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
                throw e
            }
        } catch (e: Throwable) {
            // Failure, put the tunnel back.
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
            throw e
        }
    }

    @get:Bindable
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            applicationScope.launch { UserKnobs.setLastUsedTunnel(value?.name) }
        }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main.immediate) {
        val config = withContext(Dispatchers.IO) { configStore.load(tunnel.name) }
        val extractedTurn = TurnConfigProcessor.extractTurnSettings(config)
        if (extractedTurn != null) {
            withContext(Dispatchers.IO) {
                turnSettingsStore.save(tunnel.name, extractedTurn)
            }
            tunnel.onTurnSettingsChanged(extractedTurn)
        }
        tunnel.onConfigChanged(config)!!
    }

    fun onCreate() {
        applicationScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (configStore as? FileConfigStore)?.migrateLegacyFiles()
                    turnSettingsStore.migrateLegacyFiles()
                    subscriptionStore.migrateLegacyFiles()
                }
                onTunnelsLoaded(
                    withContext(Dispatchers.IO) { configStore.enumerate() },
                    withContext(Dispatchers.IO) { getBackend().runningTunnelNames },
                )
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        applicationScope.launch {
            val lastUsedName = UserKnobs.lastUsedTunnel.first()
            if (lastUsedName != null)
                lastUsedTunnel = tunnelMap[lastUsedName]
            haveLoaded = true
            restoreState(true)
            tunnels.complete(tunnelMap)
        }
    }

    private fun refreshTunnelStates() {
        applicationScope.launch {
            try {
                val running = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
                for (tunnel in tunnelMap)
                    tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun restoreState(force: Boolean) {
        if (!haveLoaded || (!force && !UserKnobs.restoreOnBoot.first()))
            return
        val previouslyRunning = UserKnobs.runningTunnels.first()
        if (previouslyRunning.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                tunnelMap.filter { previouslyRunning.contains(it.name) }.map { async(Dispatchers.IO + SupervisorJob()) { setTunnelState(it, Tunnel.State.UP) } }
                    .awaitAll()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
    }

    suspend fun saveState() {
        UserKnobs.setRunningTunnels(tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet())
    }

    suspend fun setTunnelConfig(
        tunnel: ObservableTunnel,
        config: Config,
        turnSettings: TurnSettings? = null,
    ): Config = withContext(Dispatchers.Main.immediate) {
        val originalState = tunnel.state
        if (originalState == Tunnel.State.UP) {
            setTunnelState(tunnel, Tunnel.State.DOWN)
        }
        
        val configWithTurn = TurnConfigProcessor.injectTurnSettings(config, turnSettings)
        val result = tunnel.onConfigChanged(
            withContext(Dispatchers.IO) {
                configStore.save(tunnel.name, configWithTurn)
                configWithTurn
            },
        )!!
            .also {
                withContext(Dispatchers.IO) {
                    turnSettingsStore.save(tunnel.name, turnSettings)
                    tunnel.onTurnSettingsChanged(turnSettingsStore.load(tunnel.name))
                }
            }
        
        if (originalState == Tunnel.State.UP) {
            setTunnelState(tunnel, Tunnel.State.UP)
        }
        
        result
    }

    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withContext(Dispatchers.Main.immediate) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        var throwable: Throwable? = null
        var newName: String? = null
        try {
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.DOWN, null) }
            withContext(Dispatchers.IO) {
                configStore.rename(tunnel.name, name)
                turnSettingsStore.rename(tunnel.name, name)
                subscriptionStore.rename(tunnel.name, name)
            }
            newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP)
                withContext(Dispatchers.IO) { getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config) }
        } catch (e: Throwable) {
            throwable = e
            // On failure, we don't know what state the tunnel might be in. Fix that.
            getTunnelState(tunnel)
        }
        // Add the tunnel back to the manager, under whatever name it thinks it has.
        tunnelMap.add(tunnel)
        if (wasLastUsed)
            lastUsedTunnel = tunnel
        if (throwable != null)
            throw throwable
        newName!!
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        val pendingConnection = connectionStateMachine.isBusy(tunnel.name)
        val targetState = when (state) {
            Tunnel.State.TOGGLE -> if (tunnel.state == Tunnel.State.UP || pendingConnection) Tunnel.State.DOWN else Tunnel.State.UP
            else -> state
        }
        val requestedStart = targetState == Tunnel.State.UP
        val requestedStop = targetState == Tunnel.State.DOWN

        if (targetState == tunnel.state && !(requestedStop && pendingConnection)) {
            if (!requestedStart) return@withContext targetState
            val runningNames = withContext(Dispatchers.IO) { getBackend().runningTunnelNames }
            if (runningNames.contains(tunnel.name)) {
                Log.d(TAG, "Skip redundant UP call for ${tunnel.name}, already running")
                return@withContext targetState
            }
        }

        val connectionSession = if (requestedStart) connectionStateMachine.begin(tunnel.name) else null
        val stopSession = if (requestedStop) connectionStateMachine.beginStop(tunnel.name) else null
        val previouslyActive = if (requestedStart) {
            tunnelMap.filter { it !== tunnel && it.state == Tunnel.State.UP }
        } else {
            emptyList()
        }
        if (previouslyActive.isNotEmpty()) {
            Log.i(
                TAG,
                "Switching active tunnel from ${previouslyActive.joinToString { it.name }} to ${tunnel.name}",
            )
        }

        var newState = tunnel.state
        var throwable: Throwable? = null
        var ownedGenerationAtFailure = true

        fun requireCurrentGeneration() {
            if (connectionSession != null && !connectionStateMachine.isCurrent(connectionSession))
                throw CancellationException("Superseded connection generation ${connectionSession.generation}")
        }

        try {
            val backend = getBackend()
            requireCurrentGeneration()
            var configToUse = tunnel.getConfigAsync()
            if (requestedStart) configToUse = DnsPrivacySettings.apply(configToUse)
            requireCurrentGeneration()
            val turn = tunnel.turnSettings
            val turnEnabled = turn?.enabled == true
            val routingPolicy = RoutingPolicy.fromConfig(configToUse)

            if (requestedStart) subscriptionStore.requireEnabled(tunnel.name)
            requireCurrentGeneration()
            if (requestedStart && backend is GoBackend) {
                val globalExclusions = withContext(Dispatchers.IO) {
                    GlobalAppExclusions.load(Application.getPreferencesDataStore())
                }
                requireCurrentGeneration()
                backend.setGloballyExcludedApplications(globalExclusions)
            }

            suspend fun cleanupFailedTurnStartup(
                goBackend: GoBackend,
                session: ConnectionStateMachine.Session,
            ) {
                if (!connectionStateMachine.isCurrent(session)) return
                withContext(Dispatchers.IO) {
                    getTurnProxyManager().stopForTunnel(tunnel.name, session)
                    goBackend.setExcludedRoutes(emptyList())
                    goBackend.stopVpnServiceIfIdle()
                }
            }

            if (requestedStart) {
                val goBackend = backend as? GoBackend
                val manualRoutes = if (goBackend == null) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) {
                        ManualRouteExclusions.load(Application.getPreferencesDataStore())
                    }
                }
                requireCurrentGeneration()
                if (routingPolicy == RoutingPolicy.DIRECT_RUSSIA) {
                    if (goBackend == null)
                        throw IllegalStateException(context.getString(R.string.routing_requires_go_backend))
                    connectionStateMachine.transition(
                        connectionSession!!,
                        ConnectionStateMachine.Phase.DOWNLOADING_GEO_DATA,
                    )
                    val directRoutes = withContext(Dispatchers.IO) {
                        Application.getRoutingListManager().ensureDirectRoutes()
                    }
                    requireCurrentGeneration()
                    val excludedRoutes = (manualRoutes + directRoutes).distinct()
                    Log.i(
                        ROUTING_TAG,
                        "ru-direct enabled for ${tunnel.name}: geo=${directRoutes.size}, manual=${manualRoutes.size}, total=${excludedRoutes.size}",
                    )
                    goBackend.setExcludedRoutes(excludedRoutes)
                    RoutingListUpdateWorker.schedulePeriodic(context)
                } else {
                    Log.i(
                        ROUTING_TAG,
                        "Tunnel-all routing enabled for ${tunnel.name}: manual exclusions=${manualRoutes.size}",
                    )
                    goBackend?.setExcludedRoutes(manualRoutes)
                }
                requireCurrentGeneration()
            } else if (requestedStop) {
                (backend as? GoBackend)?.setExcludedRoutes(emptyList())
            }

            if (turnEnabled) {
                if (requestedStart) {
                    tunnelMap
                        .filter { it !== tunnel && it.state == Tunnel.State.UP }
                        .forEach { activeTunnel -> setTunnelState(activeTunnel, Tunnel.State.DOWN) }
                    requireCurrentGeneration()
                    configToUse = TurnConfigProcessor.modifyConfigForActiveTurn(configToUse, turn)
                } else if (requestedStop) {
                    withContext(Dispatchers.IO) {
                        getTurnProxyManager().stopForTunnel(tunnel.name)
                    }
                }
            }

            if (requestedStart && turnEnabled) {
                val session = connectionSession!!
                val goBackend = backend as? GoBackend
                    ?: throw IllegalStateException("TURN startup requires the Go backend")

                connectionStateMachine.transition(session, ConnectionStateMachine.Phase.STARTING_VPN_SERVICE)
                withContext(Dispatchers.IO) { goBackend.ensureVpnServiceReady() }
                requireCurrentGeneration()

                when (val turnResult = withContext(Dispatchers.IO) {
                    getTurnProxyManager().onTunnelEstablished(session, turn)
                }) {
                    TurnProxyManager.TurnStartResult.Success -> {
                        requireCurrentGeneration()
                        try {
                            newState = withContext(Dispatchers.IO) {
                                backend.setState(tunnel, targetState, configToUse)
                            }
                            requireCurrentGeneration()
                        } catch (e: Throwable) {
                            cleanupFailedTurnStartup(goBackend, session)
                            throw e
                        }
                    }
                    TurnProxyManager.TurnStartResult.Cancelled -> {
                        cleanupFailedTurnStartup(goBackend, session)
                        throw CancellationException("TURN startup cancelled by user")
                    }
                    is TurnProxyManager.TurnStartResult.Failure -> {
                        cleanupFailedTurnStartup(goBackend, session)
                        throw IllegalStateException(turnResult.message)
                    }
                }
            } else {
                if (requestedStart)
                    connectionStateMachine.transition(connectionSession!!, ConnectionStateMachine.Phase.CONNECTING_TUNNEL)
                newState = withContext(Dispatchers.IO) { backend.setState(tunnel, targetState, configToUse) }
                requireCurrentGeneration()
            }

            if (newState == Tunnel.State.UP) {
                lastUsedTunnel = tunnel
                connectionStateMachine.transition(connectionSession!!, ConnectionStateMachine.Phase.CONNECTED)
            } else if (requestedStop && stopSession != null) {
                connectionStateMachine.finishStop(stopSession)
            }
        } catch (e: Throwable) {
            throwable = e
            ownedGenerationAtFailure = connectionSession?.let(connectionStateMachine::isCurrent) ?: true
            if (connectionSession != null && ownedGenerationAtFailure)
                connectionStateMachine.fail(connectionSession, e.message)
            if (stopSession != null && connectionStateMachine.isCurrent(stopSession))
                connectionStateMachine.fail(stopSession, e.message)
        }
        tunnel.onStateChanged(newState)
        SharingController.onVpnStateChanged()
        if (
            throwable != null &&
            throwable !is CancellationException &&
            ownedGenerationAtFailure &&
            previouslyActive.isNotEmpty()
        ) {
            Log.e(TAG, "Unable to activate ${tunnel.name}; restoring the previous tunnel", throwable)
            previouslyActive.forEach { previous ->
                if (previous.state == Tunnel.State.UP) return@forEach
                try {
                    setTunnelState(previous, Tunnel.State.UP)
                    Log.i(TAG, "Restored previous tunnel ${previous.name}")
                } catch (restoreError: Throwable) {
                    throwable.addSuppressed(restoreError)
                    Log.e(TAG, "Unable to restore previous tunnel ${previous.name}", restoreError)
                }
            }
        }
        saveState()
        if (throwable != null)
            throw throwable
        newState
    }

    class IntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            applicationScope.launch {
                val manager = getTunnelManager()
                if (intent == null) return@launch
                val action = intent.action ?: return@launch
                if ("com.wireguard.android.action.REFRESH_TUNNEL_STATES" == action) {
                    manager.refreshTunnelStates()
                    return@launch
                }
                if (!UserKnobs.allowRemoteControlIntents.first())
                    return@launch
                val state = when (action) {
                    "com.wireguard.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                    "com.wireguard.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                    else -> return@launch
                }
                val tunnelName = intent.getStringExtra("tunnel") ?: return@launch
                val tunnels = manager.getTunnels()
                val tunnel = tunnels[tunnelName] ?: return@launch
                try {
                    manager.setTunnelState(tunnel, state)
                } catch (e: Throwable) {
                    Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        tunnel.onStateChanged(withContext(Dispatchers.IO) { getBackend().getState(tunnel) })
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main.immediate) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) })!!
    }

    companion object {
        private const val ROUTING_TAG = "RabbitHole/GeoRouting"
        private const val TAG = "WireGuard/TunnelManager"
    }
}
