/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.PreferenceFragmentCompat
import com.wireguard.android.Application
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.routing.RoutingListUpdateWorker
import com.wireguard.android.updater.Updater
import com.wireguard.android.util.AdminKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Interface for changing application-global persistent settings.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.commit {
                add(android.R.id.content, SettingsFragment())
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

    class SettingsFragment : PreferenceFragmentCompat() {

        // Since this is pretty much abandoned by androidx, it never got updated for proper EdgeToEdge support,
        // which is enabled everywhere for API 35. So handle the insets manually here.
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.fitsSystemWindows = true
            return view
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
            addPreferencesFromResource(R.xml.preferences)
            preferenceScreen.initialExpandedChildrenCount = 5

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
                val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
                quickTile?.parent?.removePreference(quickTile)
                --preferenceScreen.initialExpandedChildrenCount
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val darkTheme = preferenceManager.findPreference<Preference>("dark_theme")
                darkTheme?.parent?.removePreference(darkTheme)
                --preferenceScreen.initialExpandedChildrenCount
            }
            if (AdminKnobs.disableConfigExport) {
                val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
                zipExporter?.parent?.removePreference(zipExporter)
            }
            val wgQuickOnlyPrefs = arrayOf(
                preferenceManager.findPreference("tools_installer"),
                preferenceManager.findPreference("restore_on_boot"),
                preferenceManager.findPreference<Preference>("multiple_tunnels")
            ).filterNotNull()
            wgQuickOnlyPrefs.forEach { it.isVisible = false }
            lifecycleScope.launch {
                if (Application.getBackend() is WgQuickBackend) {
                    ++preferenceScreen.initialExpandedChildrenCount
                    wgQuickOnlyPrefs.forEach { it.isVisible = true }
                } else {
                    wgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
                }
            }
            preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LogViewerActivity::class.java))
                true
            }
            val appUpdate = preferenceManager.findPreference<Preference>("app_update")
            appUpdate?.setOnPreferenceClickListener {
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
                            Toast.makeText(requireContext(), getString(R.string.app_update_failed, message), Toast.LENGTH_LONG).show()
                        }
                    }
                    appUpdate.isEnabled = true
                }
                true
            }
            val routingListsUpdate = preferenceManager.findPreference<Preference>("routing_lists_update")
            fun refreshRoutingStatus() {
                val status = Application.getRoutingListManager().status()
                routingListsUpdate?.summary = if (status == null) {
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
            refreshRoutingStatus()
            routingListsUpdate?.setOnPreferenceClickListener {
                routingListsUpdate.isEnabled = false
                routingListsUpdate.summary = getString(R.string.routing_lists_updating)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Application.getRoutingListManager().update() }
                        RoutingListUpdateWorker.schedulePeriodic(requireContext())
                        refreshRoutingStatus()
                        Toast.makeText(requireContext(), R.string.routing_lists_updated, Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) {
                        val error = e.localizedMessage ?: e.javaClass.simpleName
                        routingListsUpdate.summary = if (Application.getRoutingListManager().hasData()) {
                            getString(R.string.routing_lists_update_failed_using_cache, error)
                        } else {
                            getString(R.string.routing_lists_update_failed, error)
                        }
                    } finally {
                        routingListsUpdate.isEnabled = true
                    }
                }
                true
            }
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
}
