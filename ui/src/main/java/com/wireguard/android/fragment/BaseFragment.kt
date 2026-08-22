/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.BaseActivity
import com.wireguard.android.activity.BaseActivity.OnSelectedTunnelChangedListener
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.routing.RoutingListsMissingException
import com.wireguard.android.util.ErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base class for fragments that need to know the currently-selected tunnel. Only does anything when
 * attached to a `BaseActivity`.
 */
abstract class BaseFragment : Fragment(), OnSelectedTunnelChangedListener {
    private var pendingTunnel: ObservableTunnel? = null
    private var pendingTunnelUp: Boolean? = null
    private var pendingTunnelCompletion: ((Boolean) -> Unit)? = null
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val tunnel = pendingTunnel
        val checked = pendingTunnelUp
        val completion = pendingTunnelCompletion
        pendingTunnel = null
        pendingTunnelUp = null
        pendingTunnelCompletion = null
        if (tunnel != null && checked != null) {
            activity?.lifecycleScope?.launch {
                setTunnelStateWithPermissionsResult(tunnel, checked, completion)
            } ?: completion?.invoke(false)
        } else {
            completion?.invoke(false)
        }
    }

    protected var selectedTunnel: ObservableTunnel?
        get() = (activity as? BaseActivity)?.selectedTunnel
        protected set(tunnel) {
            (activity as? BaseActivity)?.selectedTunnel = tunnel
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (activity as? BaseActivity)?.addOnSelectedTunnelChangedListener(this)
    }

    override fun onDetach() {
        (activity as? BaseActivity)?.removeOnSelectedTunnelChangedListener(this)
        super.onDetach()
    }

    fun setTunnelState(view: View, checked: Boolean) {
        val tunnel = when (val binding = DataBindingUtil.findBinding<ViewDataBinding>(view)) {
            is TunnelDetailFragmentBinding -> binding.tunnel
            is TunnelListItemBinding -> binding.item
            else -> return
        } ?: return
        requestTunnelState(tunnel, view, checked)
    }

    protected fun requestTunnelState(
        tunnel: ObservableTunnel,
        sourceView: View,
        checked: Boolean,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Job? {
        val activity = activity
        if (activity == null) {
            onComplete?.invoke(false)
            return null
        }
        return activity.lifecycleScope.launch {
            if (Application.getBackend() is GoBackend) {
                try {
                    val intent = GoBackend.VpnService.prepare(activity)
                    if (intent != null) {
                        pendingTunnel = tunnel
                        pendingTunnelUp = checked
                        pendingTunnelCompletion = onComplete
                        permissionActivityResultLauncher.launch(intent)
                        return@launch
                    }
                } catch (e: Throwable) {
                    val message = activity.getString(R.string.error_prepare, ErrorMessages[e])
                    Snackbar.make(sourceView, message, Snackbar.LENGTH_LONG)
                        .setBackgroundTint(activity.getColor(R.color.rabbit_surface_high))
                        .setTextColor(activity.getColor(R.color.rabbit_text_primary))
                        .setActionTextColor(activity.getColor(R.color.rabbit_accent_soft))
                        .show()
                    Log.e(TAG, message, e)
                    onComplete?.invoke(false)
                    return@launch
                }
            }
            setTunnelStateWithPermissionsResult(tunnel, checked, onComplete)
        }
    }

    protected fun cancelPendingTunnelRequest(tunnel: ObservableTunnel): Boolean {
        if (pendingTunnel !== tunnel) return false
        val completion = pendingTunnelCompletion
        pendingTunnel = null
        pendingTunnelUp = null
        pendingTunnelCompletion = null
        completion?.invoke(false)
        return true
    }

    private suspend fun setTunnelStateWithPermissionsResult(
        tunnel: ObservableTunnel,
        checked: Boolean,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val activity = activity
        if (activity == null) {
            onComplete?.invoke(false)
            return
        }
        try {
            tunnel.setStateAsync(Tunnel.State.of(checked))
            onComplete?.invoke(true)
        } catch (e: CancellationException) {
            Log.d(TAG, "Tunnel state request cancelled for ${tunnel.name}")
            onComplete?.invoke(false)
            throw e
        } catch (e: Throwable) {
                val error = ErrorMessages[e]
                val messageResId = if (checked) R.string.error_up else R.string.error_down
                val message = activity.getString(messageResId, error)
                val view = view
                if (view != null) {
                    val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                        .setBackgroundTint(activity.getColor(R.color.rabbit_surface_high))
                        .setTextColor(activity.getColor(R.color.rabbit_text_primary))
                        .setActionTextColor(activity.getColor(R.color.rabbit_accent_soft))
                    if (findCause<RoutingListsMissingException>(e) != null) {
                        snackbar.duration = Snackbar.LENGTH_INDEFINITE
                        snackbar.setAction(R.string.routing_lists_download_action) {
                            lifecycleScope.launch {
                                try {
                                    Application.getRoutingListManager().update()
                                    setTunnelStateWithPermissionsResult(tunnel, true)
                                } catch (updateError: Throwable) {
                                    Toast.makeText(activity, ErrorMessages[updateError], Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    snackbar.show()
                }
                else
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
                onComplete?.invoke(false)
        }
    }

    companion object {
        private const val TAG = "WireGuard/BaseFragment"
    }

    private inline fun <reified T : Throwable> findCause(throwable: Throwable): T? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}
