/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sharing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.rabbithole.vpnshare.RootSharingRuntime
import com.wireguard.android.Application
import com.wireguard.android.routing.DnsPrivacySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress

object SharingController {
    enum class Mode { DISABLED, WAITING_VPN, WAITING_INTERFACE, ROOT, PROXY, ERROR }

    data class Status(
        val mode: Mode = Mode.DISABLED,
        val gateways: List<String> = emptyList(),
        val interfaces: List<String> = emptyList(),
        val proxyPort: Int = SharingSettings.DEFAULT_PROXY_PORT,
        val error: String? = null,
    )

    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    @Volatile private var initialized = false
    @Volatile private var context: Context? = null
    @Volatile private var store: DataStore<Preferences>? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var rootAvailable: Boolean? = null
    @Volatile private var previousOffloadSetting: String? = null
    @Volatile private var downstreams: List<DownstreamInterface> = emptyList()
    private val tetheredInterfaces = linkedSetOf<String>()

    private val tetherStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val names = intent.getStringArrayListExtra("tetherArray")?.toSet()
                ?: intent.getStringArrayExtra("tetherArray")?.toSet()
                ?: emptySet()
            synchronized(tetheredInterfaces) {
                tetheredInterfaces.clear()
                tetheredInterfaces.addAll(names)
            }
            scope?.launch { reconcile() }
        }
    }

    fun initialize(context: Context, store: DataStore<Preferences>, scope: CoroutineScope) {
        if (initialized) return
        this.context = context.applicationContext
        this.store = store
        this.scope = scope
        initialized = true
        ContextCompat.registerReceiver(
            context.applicationContext,
            tetherStateReceiver,
            IntentFilter(ACTION_TETHER_STATE_CHANGED),
            // Tethering broadcasts may be sent by a privileged system component rather than
            // the system UID, which AndroidX only delivers to an exported dynamic receiver.
            // The payload is still validated against real, up network interfaces below.
            ContextCompat.RECEIVER_EXPORTED,
        )
        scope.launch {
            recoverStaleRootState()
            store.data.collect { reconcile() }
        }
    }

    fun onVpnStateChanged() {
        scope?.launch { reconcile() }
    }

    fun requestTetheringChange(type: SharingType, enabled: Boolean) {
        scope?.launch {
            val appContext = context ?: return@launch
            val root = withContext(Dispatchers.IO) { ensureRoot() }
            if (root && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    withContext(Dispatchers.IO) { setSystemTethering(type, enabled) }
                }.onFailure { Log.w(TAG, "Unable to change ${type.name} tethering", it) }
                delay(1_000)
            }
            reconcile()
        }
    }

    suspend fun reconcile(): Unit = mutex.withLock {
        val appContext = context ?: return
        val dataStore = store ?: return
        val settings = SharingSettings.load(dataStore)
        if (!settings.enabled) {
            downstreams = emptyList()
            stopAll(appContext)
            mutableStatus.value = Status()
            return
        }

        val tethered = synchronized(tetheredInterfaces) { tetheredInterfaces.toSet() }
        val active = withContext(Dispatchers.IO) {
            SharingNetworkInspector.activeDownstreams(settings.enabledTypes, tethered)
        }
        downstreams = active
        val interfaceNames = active.map { it.name }
        val gateways = active.mapNotNull { it.gatewayAddress }.distinct()
        val vpnInterface = if (isOwnVpnSessionActive()) {
            SharingNetworkInspector.vpnInterface(appContext)
        } else {
            null
        }

        if (active.isEmpty()) {
            stopAll(appContext)
            mutableStatus.value = Status(
                mode = Mode.WAITING_INTERFACE,
                proxyPort = settings.proxyPort,
            )
            return
        }

        val useRoot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && withContext(Dispatchers.IO) { ensureRoot() }
        if (useRoot) {
            TetherProxyService.stop(appContext)
            try {
                withContext(Dispatchers.IO) {
                    disableTetherOffload()
                    if (vpnInterface == null) {
                        RootSharingRuntime.stop(appContext)
                    } else {
                        val rootDns = if (DnsPrivacySettings.isAdGuardEnabled()) DnsPrivacySettings.SERVERS else ""
                        RootSharingRuntime.sync(appContext, interfaceNames.toSet(), rootDns)
                    }
                    applyRootFirewall(interfaceNames, vpnInterface)
                }
                mutableStatus.value = Status(
                    mode = if (vpnInterface == null) Mode.WAITING_VPN else Mode.ROOT,
                    gateways = gateways,
                    interfaces = interfaceNames,
                    proxyPort = settings.proxyPort,
                )
                return
            } catch (e: Throwable) {
                Log.e(TAG, "Transparent root sharing failed; falling back to proxy", e)
                withContext(Dispatchers.IO) {
                    runCatching { RootSharingRuntime.stop(appContext) }
                    cleanupRootFirewall()
                    restoreTetherOffload()
                }
            }
        }

        if (vpnInterface == null) {
            TetherProxyService.stop(appContext)
            mutableStatus.value = Status(
                mode = Mode.WAITING_VPN,
                gateways = gateways,
                interfaces = interfaceNames,
                proxyPort = settings.proxyPort,
            )
            return
        }
        try {
            TetherProxyService.start(appContext, settings.proxyPort)
            mutableStatus.value = Status(
                mode = Mode.PROXY,
                gateways = gateways,
                interfaces = interfaceNames,
                proxyPort = settings.proxyPort,
            )
        } catch (e: Throwable) {
            reportProxyError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun isAllowedClient(address: InetAddress): Boolean =
        downstreams.any { downstream -> downstream.prefixes.any { it.contains(address) } }

    fun isVpnActive(): Boolean = isOwnVpnSessionActive() &&
        context?.let(SharingNetworkInspector::vpnInterface) != null

    fun reportProxyError(message: String) {
        mutableStatus.value = mutableStatus.value.copy(mode = Mode.ERROR, error = message)
    }

    private fun ensureRoot(): Boolean {
        rootAvailable?.let { return it }
        val available = try {
            Application.getRootShell().start()
            true
        } catch (_: Throwable) {
            false
        }
        rootAvailable = available
        return available
    }

    private fun setSystemTethering(type: SharingType, enabled: Boolean) {
        val appContext = context ?: return
        val apk = shellQuote(appContext.applicationInfo.sourceDir)
        val action = if (enabled) "start" else "stop"
        val command = "CLASSPATH=$apk app_process /system/bin ${RootTetheringMain::class.java.name} $action ${type.tetheringType}"
        check(Application.getRootShell().run(null, command) == 0) { "Root tether command failed" }
    }

    private fun applyRootFirewall(downstreams: List<String>, vpnInterface: String?) {
        cleanupRootFirewall()
        if (downstreams.isEmpty()) return
        val commands = mutableListOf(
            "set -e",
            "iptables -w -N $FILTER_CHAIN",
            "iptables -w -I FORWARD 1 -j $FILTER_CHAIN",
            "iptables -w -t mangle -N $MSS_CHAIN",
            "iptables -w -t mangle -I FORWARD 1 -j $MSS_CHAIN",
            "command -v ip6tables >/dev/null && ip6tables -w -N $FILTER_CHAIN || true",
            "command -v ip6tables >/dev/null && ip6tables -w -I FORWARD 1 -j $FILTER_CHAIN || true",
        )
        downstreams.forEach { downstream ->
            val input = shellQuote(downstream)
            if (vpnInterface == null) {
                commands += "iptables -w -A $FILTER_CHAIN -i $input -j REJECT"
            } else {
                val vpn = shellQuote(vpnInterface)
                commands += "iptables -w -A $FILTER_CHAIN -i $input -o $vpn -j ACCEPT"
                commands += "iptables -w -A $FILTER_CHAIN -i $input ! -o $vpn -j REJECT"
                commands += "iptables -w -t mangle -A $MSS_CHAIN -i $input -o $vpn -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
                commands += "iptables -w -t mangle -A $MSS_CHAIN -i $vpn -o $input -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
            }
            commands += "command -v ip6tables >/dev/null && ip6tables -w -A $FILTER_CHAIN -i $input -j REJECT || true"
        }
        check(Application.getRootShell().run(null, commands.joinToString("; ")) == 0) {
            "Unable to install sharing firewall"
        }
    }

    private fun cleanupRootFirewall() {
        val command = listOf(
            "while iptables -w -D FORWARD -j $FILTER_CHAIN 2>/dev/null; do :; done",
            "iptables -w -F $FILTER_CHAIN 2>/dev/null || true",
            "iptables -w -X $FILTER_CHAIN 2>/dev/null || true",
            "while iptables -w -t mangle -D FORWARD -j $MSS_CHAIN 2>/dev/null; do :; done",
            "iptables -w -t mangle -F $MSS_CHAIN 2>/dev/null || true",
            "iptables -w -t mangle -X $MSS_CHAIN 2>/dev/null || true",
            "command -v ip6tables >/dev/null && while ip6tables -w -D FORWARD -j $FILTER_CHAIN 2>/dev/null; do :; done || true",
            "command -v ip6tables >/dev/null && ip6tables -w -F $FILTER_CHAIN 2>/dev/null || true",
            "command -v ip6tables >/dev/null && ip6tables -w -X $FILTER_CHAIN 2>/dev/null || true",
        ).joinToString("; ")
        runCatching { Application.getRootShell().run(null, command) }
    }

    private fun disableTetherOffload() {
        if (previousOffloadSetting == null) {
            val persisted = rootStatePreferences().getString(KEY_PREVIOUS_OFFLOAD, null)
            previousOffloadSetting = persisted ?: run {
                val output = mutableListOf<String>()
                Application.getRootShell().run(output, "settings get global tether_offload_disabled")
                output.firstOrNull()?.trim().orEmpty().also { previous ->
                    // Persist before changing the global value. A new process can then recover
                    // both the offload setting and stale firewall state after a hard kill.
                    check(rootStatePreferences().edit().putString(KEY_PREVIOUS_OFFLOAD, previous).commit()) {
                        "Unable to persist tethering offload rollback state"
                    }
                }
            }
        }
        check(Application.getRootShell().run(null, "settings put global tether_offload_disabled 1") == 0) {
            "Unable to disable tethering offload"
        }
    }

    private fun restoreTetherOffload() {
        val preferences = rootStatePreferences()
        val previous = previousOffloadSetting ?: preferences.getString(KEY_PREVIOUS_OFFLOAD, null) ?: return
        val command = if (previous == "0" || previous == "1") {
            "settings put global tether_offload_disabled $previous"
        } else {
            "settings delete global tether_offload_disabled"
        }
        if (runCatching { Application.getRootShell().run(null, command) }.getOrNull() == 0) {
            previousOffloadSetting = null
            preferences.edit().remove(KEY_PREVIOUS_OFFLOAD).apply()
        } else {
            Log.w(TAG, "Unable to restore tethering offload; rollback marker retained")
        }
    }

    private suspend fun stopAll(appContext: Context) {
        TetherProxyService.stop(appContext)
        val hasRollbackMarker = rootStatePreferences().contains(KEY_PREVIOUS_OFFLOAD)
        if (rootAvailable == true || hasRollbackMarker) {
            withContext(Dispatchers.IO) {
                if (ensureRoot()) {
                    runCatching { RootSharingRuntime.stop(appContext) }
                    cleanupRootFirewall()
                    restoreTetherOffload()
                }
            }
        }
    }

    private suspend fun recoverStaleRootState(): Unit = mutex.withLock {
        val appContext = context ?: return
        if (!rootStatePreferences().contains(KEY_PREVIOUS_OFFLOAD)) return
        withContext(Dispatchers.IO) {
            if (!ensureRoot()) return@withContext
            Log.i(TAG, "Recovering stale root sharing state from a previous process")
            runCatching { RootSharingRuntime.stop(appContext) }
            cleanupRootFirewall()
            restoreTetherOffload()
        }
    }

    private fun rootStatePreferences() = checkNotNull(context)
        .getSharedPreferences(ROOT_STATE_PREFERENCES, Context.MODE_PRIVATE)

    private fun isOwnVpnSessionActive(): Boolean =
        Application.getConnectionStateMachine().state.value.phase in
            com.wireguard.android.turn.ConnectionStateMachine.CONNECTED_PHASES

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private const val TAG = "RabbitHole/Sharing"
    private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val FILTER_CHAIN = "RH_VPN_SHARE"
    private const val MSS_CHAIN = "RH_VPN_MSS"
    private const val ROOT_STATE_PREFERENCES = "vpn_sharing_root_state"
    private const val KEY_PREVIOUS_OFFLOAD = "previous_tether_offload_disabled"
}
