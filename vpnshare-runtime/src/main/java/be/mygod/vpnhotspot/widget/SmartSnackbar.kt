/* SPDX-License-Identifier: Apache-2.0 */
package be.mygod.vpnhotspot.widget

import timber.log.Timber

/** Headless error sink used by the embedded routing core. */
object SmartSnackbar {
    private var pending: Any? = null

    fun make(message: Any?): SmartSnackbar {
        pending = message
        return this
    }

    fun show() {
        when (val value = pending) {
            is Throwable -> Timber.w(value, "VPN sharing error")
            null -> Unit
            else -> Timber.w("VPN sharing: %s", value)
        }
        pending = null
    }
}
