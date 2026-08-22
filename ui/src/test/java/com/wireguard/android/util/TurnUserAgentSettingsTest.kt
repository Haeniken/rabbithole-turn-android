/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnUserAgentSettingsTest {
    @Test
    fun acceptsBrowserUserAgentAndRejectsHeaderInjection() {
        assertTrue(TurnUserAgentSettings.isValidUserAgent("Mozilla/5.0 Chrome/146.0.0.0"))
        assertFalse(TurnUserAgentSettings.isValidUserAgent(""))
        assertFalse(TurnUserAgentSettings.isValidUserAgent("Mozilla/5.0\r\nX-Test: injected"))
    }
}
