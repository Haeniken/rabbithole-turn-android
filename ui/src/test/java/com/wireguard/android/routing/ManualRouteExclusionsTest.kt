/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRouteExclusionsTest {
    @Test
    fun defaultRoutesCoverLocalAndSpecialDestinations() {
        assertEquals(
            listOf(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.0.0/16",
                "224.0.0.0/4",
                "255.255.255.255/32",
            ),
            ManualRouteExclusions.parse(ManualRouteExclusions.DEFAULT_ROUTES).map { it.toString() },
        )
    }

    @Test
    fun acceptsCommentsAndRemovesDuplicates() {
        assertEquals(
            listOf("10.0.0.0/8", "192.168.0.0/16"),
            ManualRouteExclusions.parse(
                """10.0.0.0/8 # local
                    10.0.0.0/8; 192.168.0.0/16
                """.trimIndent(),
            ).map { it.toString() },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRoute() {
        ManualRouteExclusions.parse("not-a-route")
    }
}
