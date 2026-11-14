/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.updater

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.R
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QuantityFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class SnackbarUpdateShower(private val fragment: Fragment) {
    private var lastUserIntervention: Updater.Progress.NeedsUserIntervention? = null
    private val intentLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lastUserIntervention?.markAsDone()
    }

    private class SwapableSnackbar(fragment: Fragment, view: View, anchor: View?) {
        private val lifecycleOwner = fragment.viewLifecycleOwner
        private val actionSnackbar = makeSnackbar(fragment, view, anchor)
        private val statusSnackbar = makeSnackbar(fragment, view, anchor)
        private var showingAction: Boolean = false
        private var showingStatus: Boolean = false

        private fun makeSnackbar(fragment: Fragment, view: View, anchor: View?): Snackbar {
            val snackbar = Snackbar.make(fragment.requireContext(), view, "", Snackbar.LENGTH_INDEFINITE)
            if (anchor != null)
                snackbar.anchorView = anchor
            snackbar.setBackgroundTint(fragment.requireContext().getColor(R.color.rabbit_surface_high))
            snackbar.setTextColor(fragment.requireContext().getColor(R.color.rabbit_text_primary))
            snackbar.setActionTextColor(fragment.requireContext().getColor(R.color.rabbit_accent_soft))
            snackbar.setTextMaxLines(6)
            snackbar.behavior = object : BaseTransientBottomBar.Behavior() {
                override fun canSwipeDismissView(child: View): Boolean {
                    return false
                }
            }
            snackbar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(snackbar: Snackbar?, @DismissEvent event: Int) {
                    super.onDismissed(snackbar, event)
                    if (event == DISMISS_EVENT_MANUAL || event == DISMISS_EVENT_ACTION ||
                        (snackbar == actionSnackbar && !showingAction) || (snackbar == statusSnackbar && !showingStatus)
                    )
                        return
                    lifecycleOwner.lifecycleScope.launch {
                        delay(1.seconds)
                        if ((snackbar === actionSnackbar && showingAction) || (snackbar === statusSnackbar && showingStatus)) snackbar.show()
                    }
                }
            })
            return snackbar
        }

        fun showAction(text: String, action: String, listener: View.OnClickListener) {
            if (showingStatus) {
                showingStatus = false
                statusSnackbar.dismiss()
            }
            actionSnackbar.setText(text)
            actionSnackbar.setAction(action, listener)
            showingAction = true
            if (!actionSnackbar.isShownOrQueued) actionSnackbar.show()
        }

        fun showText(text: String) {
            if (showingAction) {
                showingAction = false
                actionSnackbar.dismiss()
            }
            statusSnackbar.setText(text)
            showingStatus = true
            if (!statusSnackbar.isShownOrQueued) statusSnackbar.show()
        }

        fun dismiss() {
            showingAction = false
            showingStatus = false
            actionSnackbar.dismiss()
            statusSnackbar.dismiss()
        }
    }

    fun attach(view: View, anchor: View?) {
        Log.i("WireGuard/Updater", "Update indicator attached to view")
        val snackbar = SwapableSnackbar(fragment, view, anchor)
        val context = fragment.requireContext()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    Updater.state.collect { progress ->
                        when (progress) {
                            is Updater.Progress.Complete ->
                                snackbar.dismiss()

                            is Updater.Progress.Available ->
                                snackbar.showAction(context.getString(R.string.updater_avalable), context.getString(R.string.updater_action)) {
                                    progress.update()
                                }

                            is Updater.Progress.NeedsUserIntervention -> {
                                if (lastUserIntervention !== progress) {
                                    lastUserIntervention = progress
                                    intentLauncher.launch(progress.intent)
                                }
                            }

                            is Updater.Progress.Installing ->
                                snackbar.showText(context.getString(R.string.updater_installing))

                            is Updater.Progress.Rechecking ->
                                snackbar.showText(context.getString(R.string.updater_rechecking))

                            is Updater.Progress.Downloading -> {
                                if (progress.bytesTotal != 0UL) {
                                    snackbar.showText(
                                        context.getString(
                                            R.string.updater_download_progress,
                                            QuantityFormatter.formatBytes(progress.bytesDownloaded.toLong()),
                                            QuantityFormatter.formatBytes(progress.bytesTotal.toLong()),
                                            progress.bytesDownloaded.toFloat() * 100.0 / progress.bytesTotal.toFloat()
                                        )
                                    )
                                } else {
                                    snackbar.showText(
                                        context.getString(
                                            R.string.updater_download_progress_nototal,
                                            QuantityFormatter.formatBytes(progress.bytesDownloaded.toLong())
                                        )
                                    )
                                }
                            }

                            is Updater.Progress.Retrying -> snackbar.showText(context.getString(
                                R.string.updater_download_retry, QuantityFormatter.formatBytes(progress.bytesDownloaded), progress.attempt))
                            is Updater.Progress.Failure -> snackbar.showAction(
                                context.getString(R.string.updater_failure, ErrorMessages[progress.error]), context.getString(R.string.updater_action)
                            ) { progress.retry() }

                            is Updater.Progress.Corrupt -> {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.updater_corrupt_title)
                                    .setMessage(R.string.updater_corrupt_message)
                                    .setPositiveButton(R.string.updater_corrupt_navigate) { _, _ ->
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.data = Uri.parse(progress.downloadUrl)
                                        try {
                                            context.startActivity(intent)
                                        } catch (e: Throwable) {
                                            Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_SHORT).show()
                                        }
                                    }.setCancelable(false).setOnDismissListener {
                                        val intent = Intent(Intent.ACTION_MAIN)
                                        intent.addCategory(Intent.CATEGORY_HOME)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                        System.exit(0)
                                    }.show()
                            }
                        }
                    }
                } finally {
                    Log.i("WireGuard/Updater", "Update indicator paused; downloader continues independently")
                    snackbar.dismiss()
                }
            }
        }
    }
}
