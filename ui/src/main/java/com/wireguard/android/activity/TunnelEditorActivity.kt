/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.os.Bundle
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.launch

/** Standalone editor opened by a long press on a tunnel in the main connection list. */
class TunnelEditorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tunnel_creator_activity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit)
        val tunnelName = intent.getStringExtra(EXTRA_TUNNEL_NAME)
        if (tunnelName == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val tunnel = Application.getTunnelManager().getTunnels()[tunnelName]
            if (tunnel == null) finish() else selectedTunnel = tunnel
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?,
    ): Boolean {
        if (newTunnel == null) finish()
        return true
    }

    companion object {
        const val EXTRA_TUNNEL_NAME = "tunnel_editor_name"
    }
}
