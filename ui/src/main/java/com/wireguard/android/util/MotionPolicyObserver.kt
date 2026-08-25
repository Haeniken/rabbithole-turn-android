/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.wireguard.android.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Observes the client-owned power policy and the system reduced-motion preference. */
class MotionPolicyObserver(
    context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private var clientPolicyJob: Job? = null

    fun start() {
        if (clientPolicyJob == null) {
            clientPolicyJob = Application.getCoroutineScope().launch {
                PowerPolicySettings.state.collect { notifyCurrentPolicy() }
            }
        }
        notifyCurrentPolicy()
    }

    fun stop() {
        clientPolicyJob?.cancel()
        clientPolicyJob = null
    }

    fun allowsDecorativeMotion(): Boolean = allowsDecorativeMotion(applicationContext)

    private fun notifyCurrentPolicy() = onChanged(allowsDecorativeMotion())

    companion object {
        fun allowsDecorativeMotion(context: Context): Boolean {
            if (PowerPolicySettings.current().powerSavingEnabled) return false
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
