/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.activity.CaptchaCoordinator
import com.wireguard.android.activity.QrCaptureActivity
import com.wireguard.android.activity.SettingsActivity
import com.wireguard.android.activity.TunnelEditorActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.subscription.SubscriptionManager
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

/**
 * Fragment containing a list of known WireGuard tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private var heroTunnel: ObservableTunnel? = null
    private var subscriptionTarget: ObservableTunnel? = null
    private var isPowerTransitioning = false
    private var isPowerStarting = false
    private var isPowerCancellationRequested = false
    private var powerTransitionJob: Job? = null
    private var connectionMessageJob: Job? = null
    private var lastConnectionMessageIndex = -1
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    importQrContent(result.text)
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { importQrContent(qrCode) }
        }
    }

    private val snackbarUpdateShower = SnackbarUpdateShower(this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.let { snackbarUpdateShower.attach(it.mainContainer, null) }
        parentFragmentManager.setFragmentResultListener(
            SubscriptionDialogFragment.REQUEST_KEY_SUBSCRIPTION_RESULT,
            viewLifecycleOwner,
        ) { _, result ->
            result.getString(SubscriptionDialogFragment.RESULT_MESSAGE)?.let { showSnackbar(it) }
            lifecycleScope.launch { refreshSubscriptionTarget() }
        }
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        val bottomSheet = AddTunnelsSheet()
        binding?.apply {
            settingsButton.setOnClickListener {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
            mainPowerButton.setOnClickListener { source ->
                val tunnel = heroTunnel ?: return@setOnClickListener
                if (isPowerTransitioning) {
                    if (isPowerStarting) cancelPowerTransition(tunnel)
                    return@setOnClickListener
                }
                if (isPowerCancellationRequested) return@setOnClickListener
                isPowerStarting = tunnel.state != Tunnel.State.UP
                setPowerTransitioning(true)
                powerTransitionJob = requestTunnelState(tunnel, source, isPowerStarting) {
                    powerTransitionJob = null
                    setPowerTransitioning(false)
                }
            }
            latencyCheck.setOnClickListener { checkLatency() }
            subscriptionRefresh.setOnClickListener { updateSubscriptionNow() }
            createFab.setOnClickListener {
                if (childFragmentManager.findFragmentByTag("BOTTOM_SHEET") != null)
                    return@setOnClickListener
                childFragmentManager.setFragmentResultListener(AddTunnelsSheet.REQUEST_KEY_NEW_TUNNEL, viewLifecycleOwner) { _, bundle ->
                    when (bundle.getString(AddTunnelsSheet.REQUEST_METHOD)) {
                        AddTunnelsSheet.REQUEST_CREATE -> {
                            startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                        }

                        AddTunnelsSheet.REQUEST_SUBSCRIPTION -> {
                            SubscriptionDialogFragment().show(parentFragmentManager, "SUBSCRIPTION")
                        }

                        AddTunnelsSheet.REQUEST_CLIPBOARD -> {
                            importFromClipboard()
                        }

                        AddTunnelsSheet.REQUEST_IMPORT -> {
                            tunnelFileImportResultLauncher.launch("*/*")
                        }

                        AddTunnelsSheet.REQUEST_SCAN -> {
                            qrImportResultLauncher.launch(
                                ScanOptions()
                                    .setCaptureActivity(QrCaptureActivity::class.java)
                                    .setOrientationLocked(false)
                                    .setBeepEnabled(false)
                            )
                        }
                    }
                }
                bottomSheet.showNow(childFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        connectionMessageJob?.cancel()
        connectionMessageJob = null
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        if (newTunnel != null) setHeroTunnel(newTunnel)
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            binding?.tunnels = tunnels
            setHeroTunnel(selectedTunnel ?: Application.getTunnelManager().lastUsedTunnel ?: tunnels.firstOrNull())
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        setHeroTunnel(item)
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    if (actionMode == null) {
                        setHeroTunnel(item)
                        startActivity(
                            Intent(requireContext(), TunnelEditorActivity::class.java)
                                .putExtra(TunnelEditorActivity.EXTRA_TUNNEL_NAME, item.name)
                        )
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(heroTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(binding.root.context.getColor(R.color.rabbit_surface_high))
                .setTextColor(binding.root.context.getColor(R.color.rabbit_text_primary))
                .setActionTextColor(binding.root.context.getColor(R.color.rabbit_accent_soft))
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun importQrContent(content: String) {
        if (!content.trim().startsWith("https://", ignoreCase = true)) {
            TunnelImporter.importTunnel(parentFragmentManager, content) { showSnackbar(it) }
            return
        }
        try {
            val result = Application.getSubscriptionManager().add(content)
            val message = if (result.subscriptionName != null) {
                resources.getQuantityString(
                    R.plurals.subscription_bundle_added,
                    result.tunnels.size,
                    result.subscriptionName,
                    result.tunnels.size,
                )
            } else {
                getString(R.string.subscription_added, result.tunnels.first().name)
            }
            showSnackbar(message)
            val tunnels = Application.getTunnelManager().getTunnels()
            binding?.tunnels = tunnels
            setHeroTunnel(result.tunnels.firstOrNull())
        } catch (e: Throwable) {
            showSnackbar(ErrorMessages[e])
        }
    }

    private fun importFromClipboard() {
        val context = context ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val content = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (content.isBlank()) {
            showSnackbar(getString(R.string.clipboard_empty_error))
            return
        }
        lifecycleScope.launch { importQrContent(content) }
    }

    private fun setHeroTunnel(tunnel: ObservableTunnel?) {
        val changed = heroTunnel != tunnel
        heroTunnel = tunnel
        binding?.heroTunnel = tunnel
        if (changed) binding?.tunnelList?.adapter?.notifyDataSetChanged()
        updatePowerControls()
        lifecycleScope.launch { refreshSubscriptionTarget() }
    }

    private fun setPowerTransitioning(transitioning: Boolean) {
        isPowerTransitioning = transitioning
        if (transitioning && isPowerStarting) startConnectionMessages() else stopConnectionMessages()
        updatePowerControls()
        if (!transitioning) isPowerStarting = false
    }

    private fun cancelPowerTransition(tunnel: ObservableTunnel) {
        if (isPowerCancellationRequested) return
        isPowerCancellationRequested = true
        powerTransitionJob?.cancel()
        powerTransitionJob = null
        cancelPendingTunnelRequest(tunnel)
        CaptchaCoordinator.cancelActive()
        setPowerTransitioning(false)

        lifecycleScope.launch {
            try {
                Application.getTurnProxyManager().stopForTunnel(tunnel.name)
                (Application.getBackend() as? GoBackend)?.stopVpnServiceIfIdle()
                showSnackbar(getString(R.string.main_connection_cancelled))
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to finish cancelled tunnel startup", e)
            } finally {
                isPowerCancellationRequested = false
                updatePowerControls()
            }
        }
    }

    private fun updatePowerControls() {
        binding?.apply {
            mainPowerButton.isEnabled = heroTunnel != null && !isPowerCancellationRequested && (!isPowerTransitioning || isPowerStarting)
            mainPowerButton.alpha = when {
                isPowerCancellationRequested -> 0.62f
                isPowerTransitioning -> 0.9f
                else -> 1f
            }
            mainPowerButton.contentDescription = getString(
                if (isPowerTransitioning && isPowerStarting) R.string.main_cancel_connection else R.string.main_toggle_tunnel
            )
            mainPowerButton.setAnimating(isPowerTransitioning)
            portalBackground.setConnecting(isPowerTransitioning && isPowerStarting)
            if (isPowerTransitioning) {
                heroTunnelName.setText(if (isPowerStarting) R.string.main_connecting else R.string.main_disconnecting)
                heroHint.visibility = if (isPowerStarting) View.VISIBLE else View.GONE
            } else {
                heroTunnelName.setText(
                    when {
                        heroTunnel == null -> R.string.main_no_tunnel
                        heroTunnel?.state == Tunnel.State.UP -> R.string.main_connected
                        else -> R.string.main_ready
                    }
                )
                heroHint.setText(R.string.main_ready_hint)
                heroHint.visibility = if (heroTunnel != null && heroTunnel?.state != Tunnel.State.UP) View.VISIBLE else View.GONE
            }
        }
    }

    private fun startConnectionMessages() {
        connectionMessageJob?.cancel()
        val messages = resources.getStringArray(R.array.connection_wonderland_messages)
        if (messages.isEmpty()) return
        connectionMessageJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isPowerTransitioning) {
                var nextIndex = Random.nextInt(messages.size)
                if (messages.size > 1 && nextIndex == lastConnectionMessageIndex) {
                    nextIndex = (nextIndex + 1 + Random.nextInt(messages.size - 1)) % messages.size
                }
                lastConnectionMessageIndex = nextIndex
                binding?.heroHint?.apply {
                    text = messages[nextIndex]
                    visibility = View.VISIBLE
                }
                delay(CONNECTION_MESSAGE_INTERVAL_MS)
            }
        }
    }

    private fun stopConnectionMessages() {
        connectionMessageJob?.cancel()
        connectionMessageJob = null
    }

    private suspend fun refreshSubscriptionTarget() {
        val binding = binding ?: return
        subscriptionTarget = heroTunnel?.takeIf { Application.getSubscriptionManager().isSubscribed(it.name) }
        val bundleSummary = heroTunnel?.let { Application.getSubscriptionManager().bundleSummary(it.name) }
        binding.subscriptionRefresh.visibility = if (heroTunnel == null) View.GONE else View.VISIBLE
        binding.subscriptionRefresh.alpha = if (subscriptionTarget == null) 0.62f else 1f
        binding.subscriptionRefresh.contentDescription = getString(
            if (subscriptionTarget == null) R.string.subscription_not_found else R.string.subscription_update
        )
        binding.subscriptionSummary.visibility = if (bundleSummary == null) View.GONE else View.VISIBLE
        if (bundleSummary != null) {
            binding.subscriptionSummaryName.text = bundleSummary.name
            val formattedExpiry = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(bundleSummary.expiresAt))
            binding.subscriptionSummaryExpiry.text = getString(R.string.subscription_expires, formattedExpiry)
            binding.subscriptionSummary.alpha = if (bundleSummary.enabled) 1f else 0.58f
        }
    }

    private fun updateSubscriptionNow() {
        val tunnel = subscriptionTarget
        if (tunnel == null) {
            showSnackbar(getString(R.string.subscription_not_found))
            return
        }
        val binding = binding ?: return
        binding.subscriptionRefresh.isEnabled = false
        binding.subscriptionRefreshIcon.animate().rotationBy(360f).setDuration(600L).start()
        lifecycleScope.launch {
            try {
                val message = when (Application.getSubscriptionManager().update(tunnel.name)) {
                    SubscriptionManager.UpdateResult.Unchanged -> R.string.subscription_unchanged
                    SubscriptionManager.UpdateResult.Updated -> R.string.subscription_updated
                    SubscriptionManager.UpdateResult.Disabled -> R.string.subscription_disabled
                    SubscriptionManager.UpdateResult.Enabled -> R.string.subscription_enabled
                    is SubscriptionManager.UpdateResult.Added -> R.string.subscription_updated
                }
                showSnackbar(getString(message))
            } catch (e: Throwable) {
                showSnackbar(ErrorMessages[e])
            } finally {
                binding.subscriptionRefresh.isEnabled = true
                refreshSubscriptionTarget()
            }
        }
    }

    private fun checkLatency() {
        val tunnel = heroTunnel
        if (tunnel == null || tunnel.state != Tunnel.State.UP) {
            showSnackbar(getString(R.string.latency_connect_first))
            return
        }
        val binding = binding ?: return
        binding.latencyCheck.isEnabled = false
        binding.latencyResult.visibility = View.GONE
        binding.latencyIcon.animate().rotationBy(360f).setDuration(600L).start()
        lifecycleScope.launch {
            try {
                val latency = measureLatency()
                binding.latencyResult.text = getString(R.string.latency_result, latency)
                binding.latencyResult.visibility = View.VISIBLE
            } catch (e: Throwable) {
                Log.w(TAG, "Latency check failed", e)
                showSnackbar(getString(R.string.latency_no_response))
            } finally {
                binding.latencyCheck.isEnabled = true
            }
        }
    }

    private suspend fun measureLatency(): Long = withContext(Dispatchers.IO) {
        var selectedEndpoint: LatencyEndpoint? = null
        val samples = mutableListOf<Long>()

        for (round in 0 until LATENCY_DISCOVERY_ROUNDS) {
            for (endpoint in LATENCY_ENDPOINTS) {
                try {
                    samples += measureLatency(endpoint)
                    selectedEndpoint = endpoint
                    break
                } catch (_: IOException) {
                    // Try the next independent endpoint; some networks block individual probes.
                }
            }
            if (selectedEndpoint != null) break
            if (round + 1 < LATENCY_DISCOVERY_ROUNDS) delay(LATENCY_RETRY_DELAY_MS)
        }

        val endpoint = selectedEndpoint ?: throw IOException("No latency endpoint responded")
        repeat(LATENCY_SAMPLE_COUNT - 1) {
            delay(LATENCY_SAMPLE_DELAY_MS)
            try {
                samples += measureLatency(endpoint)
            } catch (_: IOException) {
                // The successful discovery sample is sufficient if a follow-up probe is lost.
            }
        }
        samples.sorted()[samples.size / 2]
    }

    private fun measureLatency(endpoint: LatencyEndpoint): Long {
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), LATENCY_TIMEOUT_MS)
        }
        return (System.nanoTime() - startedAt) / 1_000_000L
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {
                }

                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }

                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private data class LatencyEndpoint(val host: String, val port: Int)

        private val LATENCY_ENDPOINTS = listOf(
            LatencyEndpoint("1.1.1.1", 443),
            LatencyEndpoint("8.8.8.8", 443),
            LatencyEndpoint("9.9.9.9", 443),
        )
        private const val LATENCY_DISCOVERY_ROUNDS = 2
        private const val LATENCY_SAMPLE_COUNT = 3
        private const val LATENCY_TIMEOUT_MS = 1_500
        private const val LATENCY_RETRY_DELAY_MS = 400L
        private const val LATENCY_SAMPLE_DELAY_MS = 120L
        private const val CONNECTION_MESSAGE_INTERVAL_MS = 2_300L
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
