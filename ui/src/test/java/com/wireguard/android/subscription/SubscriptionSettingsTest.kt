/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSettingsTest {
    @Test
    fun buildsVersionedUserAgentFromBuildMetadata() {
        assertEquals(
            "Rabbithole/1.0.3/Android/abc123",
            SubscriptionSettings.defaultUserAgent("1.0.3", "abc123"),
        )
    }

    @Test
    fun normalizesUpdateIntervalToSupportedRange() {
        assertEquals(SubscriptionSettings.DEFAULT_UPDATE_INTERVAL_HOURS, SubscriptionSettings.normalizeIntervalHours(null))
        assertEquals(SubscriptionSettings.MIN_UPDATE_INTERVAL_HOURS, SubscriptionSettings.normalizeIntervalHours("0"))
        assertEquals(SubscriptionSettings.MAX_UPDATE_INTERVAL_HOURS, SubscriptionSettings.normalizeIntervalHours("999"))
        assertEquals(48, SubscriptionSettings.normalizeIntervalHours(" 48 "))
    }

    @Test
    fun validatesSafeHttpUserAgent() {
        assertTrue(SubscriptionSettings.isValidUserAgent("Rabbithole/1.0.3/Android/abc123"))
        assertFalse(SubscriptionSettings.isValidUserAgent(""))
        assertFalse(SubscriptionSettings.isValidUserAgent("Rabbit\nHole"))
    }
}
