/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.util.ErrorMessages
import kotlinx.coroutines.launch

class SubscriptionDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = layoutInflater.inflate(R.layout.subscription_dialog, null, false)
        val urlInput = content.findViewById<TextInputEditText>(R.id.subscription_url)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_subscription)
            .setMessage(R.string.subscription_dialog_message)
            .setView(content)
            .setPositiveButton(R.string.add_subscription, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val url = urlInput.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    urlInput.error = getString(R.string.subscription_url_required)
                    return@setOnClickListener
                }
                positiveButton.isEnabled = false
                isCancelable = false
                lifecycleScope.launch {
                    try {
                        val result = Application.getSubscriptionManager().add(url)
                        val resultMessage = if (result.subscriptionName != null) {
                            resources.getQuantityString(
                                R.plurals.subscription_bundle_added,
                                result.tunnels.size,
                                result.subscriptionName,
                                result.tunnels.size,
                            )
                        } else {
                            getString(R.string.subscription_added, result.tunnels.first().name)
                        }
                        setFragmentResult(
                            REQUEST_KEY_SUBSCRIPTION_RESULT,
                            bundleOf(RESULT_MESSAGE to resultMessage),
                        )
                        dismiss()
                    } catch (e: Throwable) {
                        urlInput.error = ErrorMessages[e]
                        positiveButton.isEnabled = true
                        isCancelable = true
                    }
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return dialog
    }

    companion object {
        const val REQUEST_KEY_SUBSCRIPTION_RESULT = "subscription_result"
        const val RESULT_MESSAGE = "message"
    }
}
