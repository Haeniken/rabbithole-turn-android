/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPolicySettingsTest {
    @Test
    fun defaultsKeepBackgroundUpdatesAndNormalMotion() {
        assertEquals(
            PowerPolicySettings.Snapshot(backgroundUpdatesEnabled = true, powerSavingEnabled = true),
            PowerPolicySettings.fromPreferences(emptyPreferences()),
        )
    }

    @Test
    fun readsBothClientOwnedSwitches() {
        val preferences = preferencesOf(
            booleanPreferencesKey(PowerPolicySettings.BACKGROUND_UPDATES_KEY_NAME) to false,
            booleanPreferencesKey(PowerPolicySettings.POWER_SAVING_KEY_NAME) to true,
        )

        assertEquals(
            PowerPolicySettings.Snapshot(backgroundUpdatesEnabled = false, powerSavingEnabled = true),
            PowerPolicySettings.fromPreferences(preferences),
        )
    }
}
