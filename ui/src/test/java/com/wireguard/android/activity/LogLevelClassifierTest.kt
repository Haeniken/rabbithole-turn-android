/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLevelClassifierTest {
    @Test
    fun proxyHubStartupIsInformational() {
        assertEquals("I", effectiveLogLevel("I", "[PROXY] Hub starting on 127.0.0.1:51821"))
        assertEquals("I", effectiveLogLevel("E", "[PROXY] Hub starting on 127.0.0.1:51821"))
    }

    @Test
    fun actualFailuresRemainErrors() {
        assertEquals("E", effectiveLogLevel("I", "TURN allocation failed: timeout"))
    }
}
