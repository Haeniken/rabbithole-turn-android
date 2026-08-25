/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.wireguard.android.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Observes the system Battery Saver and reduced-motion preference. */
class MotionPolicyObserver(
    context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private var registered = false
    private var clientPolicyJob: Job? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = notifyCurrentPolicy()
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(
                applicationContext,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        if (clientPolicyJob == null) {
            clientPolicyJob = Application.getCoroutineScope().launch {
                PowerPolicySettings.state.collect { notifyCurrentPolicy() }
            }
        }
        notifyCurrentPolicy()
    }

    fun stop() {
        if (!registered) return
        applicationContext.unregisterReceiver(receiver)
        registered = false
        clientPolicyJob?.cancel()
        clientPolicyJob = null
    }

    fun allowsDecorativeMotion(): Boolean = allowsDecorativeMotion(applicationContext)

    private fun notifyCurrentPolicy() = onChanged(allowsDecorativeMotion())

    companion object {
        fun allowsDecorativeMotion(context: Context): Boolean {
            if (PowerPolicySettings.current().powerSavingEnabled) return false
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (powerManager?.isPowerSaveMode == true) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) > 0f
            }
        }
    }
}
