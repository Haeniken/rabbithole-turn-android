/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.util.ErrorMessages

class VersionPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun getSummary() = context.getString(R.string.version_and_support, BuildConfig.VERSION_NAME)

    override fun getTitle() = context.getString(R.string.app_name)

    override fun onClick() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(SUPPORT_URL)
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, ErrorMessages[e], Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val SUPPORT_URL = "https://t.me/rabbit_hole_news?direct"
    }
}
