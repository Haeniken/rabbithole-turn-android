/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class LogGroupClassifierTest {
    @Test
    fun routesBackendDestinationMessagesToRouting() {
        assertEquals(
            LogGroupKind.ROUTING,
            classifyLogGroup("WireGuard/GoBackend", "Applying 734 direct destination routes"),
        )
    }

    @Test
    fun routesApplicationStartupToApplication() {
        assertEquals(
            LogGroupKind.APP,
            classifyLogGroup("WireGuard/Application", "WireGuard/1.0.4 (Android 36)"),
        )
    }

    @Test
    fun routesSuccessfulChecksToSubscriptions() {
        assertEquals(
            LogGroupKind.SUBSCRIPTION,
            classifyLogGroup("RabbitHole/SubscriptionManager", "Subscription check finished: up-to-date"),
        )
    }
}
