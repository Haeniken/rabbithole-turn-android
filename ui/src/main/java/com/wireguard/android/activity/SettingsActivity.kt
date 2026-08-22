/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import com.wireguard.android.subscription.SubscriptionSettings
import com.wireguard.android.subscription.SubscriptionUpdateWorker
import com.wireguard.android.updater.Updater
import com.wireguard.android.util.AdminKnobs
import com.wireguard.android.util.CaptchaBrowserProfile
import com.wireguard.android.util.GlobalAppExclusions
import com.wireguard.android.util.QuantityFormatter
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
            preferenceScreen.initialExpandedChildrenCount = 5

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
            preferenceManager.findPreference<Preference>("background_activity")?.setOnPreferenceClickListener {
                openBackgroundActivitySettings()
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

        private fun openBackgroundActivitySettings() {
            val context = requireContext()
            val packageUri = Uri.parse("package:${context.packageName}")
            val powerManager = context.getSystemService(PowerManager::class.java)
            val intents = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true
                ) {
                    add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            }
            val opened = intents.any { intent ->
                try {
                    startActivity(intent)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            if (!opened)
                Toast.makeText(context, R.string.background_activity_open_failed, Toast.LENGTH_LONG).show()
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

    companion object {
        private const val EXTRA_PAGE = "settings_page"
        private const val PAGE_SUBSCRIPTIONS = "subscriptions"
        private const val PAGE_ROUTING = "routing"

        private fun createIntent(context: Context, page: String): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
