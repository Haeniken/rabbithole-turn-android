/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSettingsTest {
    @Test
    fun profileSubtitleIsReadFromBundleCommentAndPreserved() {
        val settings = TurnSettings.fromComments(
            listOf(
                "#@wgt:EnableTURN = true",
                "#@wgt:IPPort = example.invalid:443",
                "#@wgt:VKLink = https://vk.ru/call/join/example",
                "#@rhv:ProfileSubtitle = Резерв",
            ),
        )

        assertEquals("Резерв", settings?.profileSubtitle)
        assertTrue(settings!!.toComments().contains("#@rhv:ProfileSubtitle = Резерв"))
    }
}
