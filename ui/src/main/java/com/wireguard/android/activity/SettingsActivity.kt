/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.Application
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.fragment.AppListDialogFragment
import com.wireguard.android.preference.DynamicDefaultEditTextPreference
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.routing.ManualRouteExclusions
import com.wireguard.android.routing.RoutingListManager
import com.wireguard.android.routing.RoutingListUpdateWorker
import com.wireguard.android.sharing.SharingController
import com.wireguard.android.sharing.SharingSettings
import com.wireguard.android.sharing.SharingType
import com.wireguard.android.subscription.SubscriptionSettings
import com.wireguard.android.subscription.SubscriptionUpdateWorker
import com.wireguard.android.updater.Updater
import com.wireguard.android.util.AdminKnobs
import com.wireguard.android.util.CaptchaBrowserProfile
import com.wireguard.android.util.GlobalAppExclusions
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.PowerPolicySettings
import com.wireguard.android.util.TurnUserAgentSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Interface for changing application-global persistent settings. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val page = intent.getStringExtra(EXTRA_PAGE)
        val (title, fragment) = when (page) {
            PAGE_SUBSCRIPTIONS -> R.string.subscription_settings_title to SubscriptionSettingsFragment()
            PAGE_ROUTING -> R.string.routing_settings_title to RoutingSettingsFragment()
            PAGE_POWER -> R.string.power_settings_title to PowerSettingsFragment()
            PAGE_SHARING -> R.string.sharing_settings_title to SharingSettingsFragment()
            else -> R.string.settings to SettingsFragment()
        }
        supportActionBar?.setTitle(title)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.commit {
                add(android.R.id.content, fragment)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    abstract class StyledSettingsFragment : PreferenceFragmentCompat() {
        @get:XmlRes
        protected abstract val preferenceResource: Int

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            return super.onCreateView(inflater, container, savedInstanceState).also { it.fitsSystemWindows = true }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val verticalPadding = (8 * resources.displayMetrics.density).toInt()
            listView.setBackgroundResource(R.drawable.rabbit_main_background)
            listView.setPadding(0, verticalPadding, 0, verticalPadding * 2)
            listView.clipToPadding = false
            setDivider(null)
            setDividerHeight(0)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(
                lifecycleScope,
                Application.getPreferencesDataStore(),
            )
            addPreferencesFromResource(preferenceResource)
        }

        protected fun openPage(page: String) {
            startActivity(createIntent(requireContext(), page))
        }

        protected fun configureSingleLineEditor(preference: EditTextPreference?) {
            preference?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editText.isSingleLine = true
            }
        }
    }

    class SettingsFragment : StyledSettingsFragment() {
        override val preferenceResource = R.xml.preferences

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            preferenceScreen.initialExpandedChildrenCount = 7

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
                val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
                quickTile?.parent?.removePreference(quickTile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val darkTheme = preferenceManager.findPreference<Preference>("dark_theme")
                darkTheme?.parent?.removePreference(darkTheme)
            }
            if (AdminKnobs.disableConfigExport) {
                val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
                zipExporter?.parent?.removePreference(zipExporter)
            }

            val wgQuickOnlyPrefs = arrayOf(
                preferenceManager.findPreference("tools_installer"),
                preferenceManager.findPreference("restore_on_boot"),
                preferenceManager.findPreference<Preference>("multiple_tunnels"),
            ).filterNotNull()
            wgQuickOnlyPrefs.forEach { it.isVisible = false }
            lifecycleScope.launch {
                if (Application.getBackend() is WgQuickBackend) {
                    wgQuickOnlyPrefs.forEach { it.isVisible = true }
                } else {
                    wgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
                }
            }

            preferenceManager.findPreference<Preference>("subscription_settings")?.setOnPreferenceClickListener {
                openPage(PAGE_SUBSCRIPTIONS)
                true
            }
            preferenceManager.findPreference<Preference>("routing_settings")?.setOnPreferenceClickListener {
                openPage(PAGE_ROUTING)
                true
            }
            preferenceManager.findPreference<Preference>("power_settings")?.setOnPreferenceClickListener {
                openPage(PAGE_POWER)
                true
            }
            preferenceManager.findPreference<Preference>("sharing_settings")?.setOnPreferenceClickListener {
                openPage(PAGE_SHARING)
                true
            }
            preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LogViewerActivity::class.java))
                true
            }
            configureApplicationUpdate()
            configureKernelModulePreference()
        }

        private fun configureApplicationUpdate() {
            val appUpdate = preferenceManager.findPreference<Preference>("app_update") ?: return
            appUpdate.setOnPreferenceClickListener {
                appUpdate.isEnabled = false
                appUpdate.summary = getString(R.string.app_update_checking)
                lifecycleScope.launch {
                    when (val result = Updater.checkNow()) {
                        Updater.CheckResult.UpToDate -> {
                            appUpdate.summary = getString(R.string.app_update_up_to_date)
                            Toast.makeText(requireContext(), R.string.app_update_up_to_date, Toast.LENGTH_SHORT).show()
                        }

                        is Updater.CheckResult.UpdateAvailable -> {
                            appUpdate.summary = getString(R.string.app_update_available, result.version)
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.app_update_dialog_title)
                                .setMessage(getString(R.string.app_update_dialog_message, result.version))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.updater_action) { _, _ ->
                                    Updater.installAvailableUpdate()
                                    startActivity(
                                        Intent(requireContext(), MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                    )
                                    requireActivity().finish()
                                }
                                .show()
                        }

                        is Updater.CheckResult.Failed -> {
                            val message = result.error.localizedMessage ?: result.error.javaClass.simpleName
                            appUpdate.summary = getString(R.string.app_update_failed, message)
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.app_update_failed, message),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    appUpdate.isEnabled = true
                }
                true
            }
        }

        private fun configureKernelModulePreference() {
            val kernelModuleEnabler = preferenceManager.findPreference<Preference>("kernel_module_enabler")
            if (WgQuickBackend.hasKernelSupport()) {
                lifecycleScope.launch {
                    if (Application.getBackend() !is WgQuickBackend) {
                        try {
                            withContext(Dispatchers.IO) { Application.getRootShell().start() }
                        } catch (_: Throwable) {
                            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                        }
                    }
                }
            } else {
                kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
            }
        }
    }

    class SubscriptionSettingsFragment : StyledSettingsFragment() {
        override val preferenceResource = R.xml.preferences_subscriptions

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            val automatic = preferenceManager.findPreference<CheckBoxPreference>(
                SubscriptionSettings.AUTOMATIC_UPDATES_KEY_NAME,
            )
            val interval = preferenceManager.findPreference<EditTextPreference>(
                SubscriptionSettings.UPDATE_INTERVAL_KEY_NAME,
            )
            interval?.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.isSingleLine = true
                editText.selectAll()
            }
            interval?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val value = SubscriptionSettings.normalizeIntervalHours(preference.text).toString()
                getString(R.string.subscription_update_interval_summary, value)
            }
            interval?.setOnPreferenceChangeListener { _, rawValue ->
                val value = rawValue?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in SubscriptionSettings.MIN_UPDATE_INTERVAL_HOURS..SubscriptionSettings.MAX_UPDATE_INTERVAL_HOURS) {
                    Toast.makeText(
                        requireContext(),
                        R.string.subscription_update_interval_invalid,
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                } else {
                    rescheduleSubscriptions(intervalOverride = value)
                    true
                }
            }
            automatic?.setOnPreferenceChangeListener { _, rawValue ->
                rescheduleSubscriptions(enabledOverride = rawValue as Boolean)
                true
            }

            val userAgent = preferenceManager.findPreference<DynamicDefaultEditTextPreference>(
                SubscriptionSettings.USER_AGENT_KEY_NAME,
            )
            userAgent?.setDynamicDefault(SubscriptionSettings.defaultUserAgent())
            configureSingleLineEditor(userAgent)
            userAgent?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text }
            userAgent?.setOnPreferenceChangeListener { _, rawValue ->
                validateUserAgent(rawValue?.toString(), SubscriptionSettings::isValidUserAgent)
            }
        }

        private fun rescheduleSubscriptions(
            enabledOverride: Boolean? = null,
            intervalOverride: Int? = null,
        ) {
            lifecycleScope.launch {
                val settings = SubscriptionSettings.load(Application.getPreferencesDataStore())
                SubscriptionUpdateWorker.schedulePeriodic(
                    requireContext(),
                    enabledOverride ?: settings.automaticUpdates,
                    intervalOverride ?: settings.intervalHours,
                )
            }
        }

        private fun validateUserAgent(value: String?, validator: (String) -> Boolean): Boolean {
            val candidate = value?.trim().orEmpty()
            if (candidate.isEmpty() || validator(candidate)) return true
            Toast.makeText(requireContext(), R.string.user_agent_invalid, Toast.LENGTH_LONG).show()
            return false
        }
    }

    class RoutingSettingsFragment : StyledSettingsFragment() {
        override val preferenceResource = R.xml.preferences_routing

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            configureRoutingDataUpdate()
            configureGlobalAppExclusions()
            configureManualRoutes()
            configureTurnUserAgent()
        }

        private fun configureRoutingDataUpdate() {
            val update = preferenceManager.findPreference<Preference>("routing_lists_update") ?: return
            fun refreshStatus() {
                val status = Application.getRoutingListManager().status()
                update.summary = if (status == null) {
                    getString(R.string.routing_lists_never_updated)
                } else {
                    getString(
                        R.string.routing_lists_status,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.updatedAt)),
                        status.cidrCount,
                        status.domainCount,
                        status.resolvedAddressCount,
                    )
                }
            }
            refreshStatus()
            update.setOnPreferenceClickListener {
                update.isEnabled = false
                update.summary = getString(R.string.routing_lists_updating)
                val hostView = requireView()
                val progressSnackbar = Snackbar.make(hostView, "", Snackbar.LENGTH_INDEFINITE)
                    .setBackgroundTint(requireContext().getColor(R.color.rabbit_surface_high))
                    .setTextColor(requireContext().getColor(R.color.rabbit_text_primary))
                    .setActionTextColor(requireContext().getColor(R.color.rabbit_accent_soft))
                    .setTextMaxLines(4)
                fun showProgress(progress: RoutingListManager.UpdateProgress) {
                    hostView.post {
                        if (!isAdded) return@post
                        val text = when (progress) {
                            is RoutingListManager.UpdateProgress.Downloading -> {
                                if (progress.bytesTotal > 0) {
                                    getString(
                                        R.string.routing_lists_download_progress,
                                        progress.fileName,
                                        QuantityFormatter.formatBytes(progress.bytesDownloaded),
                                        QuantityFormatter.formatBytes(progress.bytesTotal),
                                        progress.bytesDownloaded.toDouble() * 100.0 / progress.bytesTotal.toDouble(),
                                    )
                                } else {
                                    getString(
                                        R.string.routing_lists_download_progress_nototal,
                                        progress.fileName,
                                        QuantityFormatter.formatBytes(progress.bytesDownloaded),
                                    )
                                }
                            }
                            RoutingListManager.UpdateProgress.Validating ->
                                getString(R.string.routing_lists_validating)
                        }
                        progressSnackbar.setText(text)
                        if (!progressSnackbar.isShown) progressSnackbar.show()
                    }
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            Application.getRoutingListManager().update(::showProgress)
                        }
                        RoutingListUpdateWorker.schedulePeriodic(requireContext())
                        progressSnackbar.dismiss()
                        refreshStatus()
                        Toast.makeText(requireContext(), R.string.routing_lists_updated, Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) {
                        val error = e.localizedMessage ?: e.javaClass.simpleName
                        val usingCache = Application.getRoutingListManager().hasData()
                        val failureText = if (usingCache) {
                            getString(R.string.routing_lists_update_failed_using_cache, error)
                        } else {
                            getString(R.string.routing_lists_update_failed, error)
                        }
                        update.summary = failureText
                        progressSnackbar.duration = Snackbar.LENGTH_LONG
                        progressSnackbar.setText(failureText)
                        progressSnackbar.show()
                    } finally {
                        update.isEnabled = true
                    }
                }
                true
            }
        }

        private fun configureGlobalAppExclusions() {
            val preference = preferenceManager.findPreference<Preference>("global_app_exclusions") ?: return
            fun refreshSummary(selected: Set<String>) {
                preference.summary = if (selected.isEmpty()) {
                    getString(R.string.global_app_exclusions_summary_none)
                } else {
                    resources.getQuantityString(
                        R.plurals.global_app_exclusions_count,
                        selected.size,
                        selected.size,
                    )
                }
            }
            lifecycleScope.launch {
                refreshSummary(withContext(Dispatchers.IO) {
                    GlobalAppExclusions.load(Application.getPreferencesDataStore())
                })
            }
            childFragmentManager.setFragmentResultListener(
                AppListDialogFragment.REQUEST_SELECTION,
                this,
            ) { _, bundle ->
                val selected = bundle.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS)
                    .orEmpty()
                    .filterNot { it == requireContext().packageName }
                    .toSet()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        GlobalAppExclusions.save(Application.getPreferencesDataStore(), selected)
                    }
                    refreshSummary(selected)
                }
            }
            preference.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val selected = withContext(Dispatchers.IO) {
                        GlobalAppExclusions.load(Application.getPreferencesDataStore())
                    }
                    AppListDialogFragment.newInstance(
                        ArrayList<String?>(selected.size).apply { addAll(selected) },
                        isExcluded = true,
                        excludeOnly = true,
                    ).show(childFragmentManager, "global-app-exclusions")
                }
                true
            }
        }

        private fun configureManualRoutes() {
            val routes = preferenceManager.findPreference<EditTextPreference>(
                ManualRouteExclusions.PREFERENCE_KEY_NAME,
            ) ?: return
            routes.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editText.isSingleLine = false
                editText.minLines = 6
                editText.maxLines = 14
                editText.setHorizontallyScrolling(false)
            }
            routes.setOnPreferenceChangeListener { _, rawValue ->
                try {
                    ManualRouteExclusions.parse(rawValue?.toString().orEmpty())
                    true
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.routing_excluded_routes_invalid, e.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        }

        private fun configureTurnUserAgent() {
            val preference = preferenceManager.findPreference<DynamicDefaultEditTextPreference>(
                TurnUserAgentSettings.USER_AGENT_KEY_NAME,
            ) ?: return
            preference.setDynamicDefault(CaptchaBrowserProfile.defaultUserAgent(requireContext()))
            configureSingleLineEditor(preference)
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text }
            preference.setOnPreferenceChangeListener { _, rawValue ->
                val candidate = rawValue?.toString()?.trim().orEmpty()
                if (candidate.isEmpty() || TurnUserAgentSettings.isValidUserAgent(candidate)) {
                    true
                } else {
                    Toast.makeText(requireContext(), R.string.user_agent_invalid, Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
    }

    class PowerSettingsFragment : StyledSettingsFragment() {
        override val preferenceResource = R.xml.preferences_power

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            preferenceManager.findPreference<CheckBoxPreference>(
                PowerPolicySettings.BACKGROUND_UPDATES_KEY_NAME,
            )?.setOnPreferenceChangeListener { _, rawValue ->
                applyPowerPolicy(backgroundUpdatesEnabled = rawValue as Boolean)
                true
            }
            preferenceManager.findPreference<CheckBoxPreference>(
                PowerPolicySettings.POWER_SAVING_KEY_NAME,
            )?.setOnPreferenceChangeListener { _, rawValue ->
                applyPowerPolicy(powerSavingEnabled = rawValue as Boolean)
                true
            }
        }

        private fun applyPowerPolicy(
            backgroundUpdatesEnabled: Boolean? = null,
            powerSavingEnabled: Boolean? = null,
        ) {
            val current = PowerPolicySettings.current()
            val updated = current.copy(
                backgroundUpdatesEnabled = backgroundUpdatesEnabled ?: current.backgroundUpdatesEnabled,
                powerSavingEnabled = powerSavingEnabled ?: current.powerSavingEnabled,
            )
            PowerPolicySettings.applyImmediate(updated)
            lifecycleScope.launch {
                if (!updated.backgroundUpdatesEnabled) {
                    RoutingListUpdateWorker.cancelAll(requireContext())
                    SubscriptionUpdateWorker.cancelAll(requireContext())
                } else if (backgroundUpdatesEnabled != null) {
                    RoutingListUpdateWorker.configureAtStartup(
                        requireContext(),
                        Application.getRoutingListManager().hasData(),
                        updated,
                    )
                    SubscriptionUpdateWorker.configureAtStartup(requireContext(), updated)
                } else {
                    if (Application.getRoutingListManager().hasData()) {
                        RoutingListUpdateWorker.schedulePeriodic(requireContext(), updated)
                    }
                    val subscriptionSettings = SubscriptionSettings.load(
                        Application.getPreferencesDataStore(),
                    )
                    SubscriptionUpdateWorker.schedulePeriodic(
                        requireContext(),
                        subscriptionSettings.automaticUpdates,
                        subscriptionSettings.intervalHours,
                        updated,
                    )
                }
            }
        }
    }

    class SharingSettingsFragment : StyledSettingsFragment() {
        override val preferenceResource = R.xml.preferences_sharing

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            configureType("sharing_wifi", SharingType.WIFI)
            configureType("sharing_usb", SharingType.USB)
            configureType("sharing_bluetooth", SharingType.BLUETOOTH)
            configureType("sharing_ethernet", SharingType.ETHERNET)

            preferenceManager.findPreference<EditTextPreference>("sharing_proxy_port")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER
                    editText.isSingleLine = true
                    editText.selectAll()
                }
                setOnPreferenceChangeListener { _, value ->
                    val port = value?.toString()?.toIntOrNull()
                    if (port == null || port !in SharingSettings.MIN_PROXY_PORT..65535) {
                        Toast.makeText(requireContext(), R.string.sharing_proxy_port_invalid, Toast.LENGTH_LONG).show()
                        false
                    } else {
                        true
                    }
                }
            }
            preferenceManager.findPreference<Preference>("sharing_system_settings")?.setOnPreferenceClickListener {
                openTetherSettings()
                true
            }
            preferenceManager.findPreference<Preference>("sharing_windows_help")?.setOnPreferenceClickListener {
                val status = SharingController.status.value
                val gateway = status.gateways.firstOrNull()
                val port = status.proxyPort
                val singBoxConfig = gateway?.let { phoneAddress ->
                    val routeExcludeAddress = SharingSettings.routeExcludeAddress(phoneAddress)
                    """
                    {
                      "dns": {
                        "servers": [
                          {
                            "type": "udp",
                            "tag": "adguard",
                            "server": "94.140.14.14",
                            "server_port": 53,
                            "detour": "phone"
                          },
                          {
                            "type": "udp",
                            "tag": "adguard-backup",
                            "server": "94.140.15.15",
                            "server_port": 53,
                            "detour": "phone"
                          }
                        ],
                        "final": "adguard"
                      },
                      "inbounds": [
                        {
                          "type": "tun",
                          "tag": "tun-in",
                          "address": ["172.19.0.1/30"],
                          "mtu": 1280,
                          "auto_route": true,
                          "strict_route": true,
                          "route_exclude_address": ["$routeExcludeAddress"]
                        }
                      ],
                      "outbounds": [
                        {
                          "type": "socks",
                          "tag": "phone",
                          "server": "$phoneAddress",
                          "server_port": $port,
                          "version": "5"
                        }
                      ],
                      "route": {
                        "auto_detect_interface": true,
                        "rules": [
                          {
                            "action": "hijack-dns",
                            "protocol": "dns"
                          }
                        ],
                        "final": "phone"
                      }
                    }
                    """.trimIndent()
                }
                val message = if (gateway == null || singBoxConfig == null) {
                    getString(R.string.sharing_windows_help_message_pending, port)
                } else {
                    getString(R.string.sharing_windows_help_message, gateway, port, singBoxConfig)
                }
                val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sharing_windows_help_title)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.sharing_windows_open_sing_box_short) { _, _ ->
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://sing-box.sagernet.org/installation/package-manager/"),
                            ),
                        )
                    }
                if (singBoxConfig != null) {
                    dialogBuilder.setNeutralButton(R.string.sharing_windows_copy_config, null)
                }
                val dialog = dialogBuilder.create()
                dialog.setOnShowListener {
                    dialog.findViewById<android.widget.TextView>(android.R.id.message)
                        ?.setTextIsSelectable(true)
                    singBoxConfig?.let { config ->
                        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                            requireContext().getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(getString(R.string.sharing_windows_config_label), config),
                            )
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.sharing_windows_config_copied,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                dialog.show()
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    SharingController.status.collect(::showStatus)
                }
            }
        }

        private fun configureType(key: String, type: SharingType) {
            preferenceManager.findPreference<CheckBoxPreference>(key)?.setOnPreferenceChangeListener { _, value ->
                SharingController.requestTetheringChange(type, value as Boolean)
                true
            }
        }

        private fun showStatus(status: SharingController.Status) {
            val preference = preferenceManager.findPreference<Preference>("sharing_status") ?: return
            val endpoints = status.gateways.joinToString().ifEmpty { status.interfaces.joinToString() }
            preference.summary = when (status.mode) {
                SharingController.Mode.DISABLED -> getString(R.string.sharing_status_disabled)
                SharingController.Mode.WAITING_VPN -> getString(R.string.sharing_status_waiting_vpn)
                SharingController.Mode.WAITING_INTERFACE -> getString(R.string.sharing_status_waiting_interface)
                SharingController.Mode.ROOT -> getString(R.string.sharing_status_root, endpoints)
                SharingController.Mode.PROXY -> getString(
                    R.string.sharing_status_proxy,
                    endpoints,
                    status.proxyPort,
                )
                SharingController.Mode.ERROR -> getString(
                    R.string.sharing_status_error,
                    status.error.orEmpty(),
                )
            }
        }

        private fun openTetherSettings() {
            val intents = listOf(
                Intent("android.settings.TETHER_SETTINGS"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            intents.firstOrNull { it.resolveActivity(requireContext().packageManager) != null }
                ?.let(::startActivity)
        }
    }

    companion object {
        private const val EXTRA_PAGE = "settings_page"
        private const val PAGE_SUBSCRIPTIONS = "subscriptions"
        private const val PAGE_ROUTING = "routing"
        private const val PAGE_POWER = "power"
        private const val PAGE_SHARING = "sharing"

        private fun createIntent(context: Context, page: String): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
