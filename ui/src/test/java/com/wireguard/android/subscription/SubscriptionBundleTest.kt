/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

class SubscriptionBundleTest {
    @Test
    fun parsesVersionOneBundle() {
        val bundle = SubscriptionBundle.parse(
            """{
                "version":1,
                "type":"rabbithole-turn-bundle",
                "expiresAt":"2100-01-30T20:59:00Z",
                "profiles":[
                    {"id":"alice","name":"Alice","config":"[Interface]\\nPrivateKey = key\\n"},
                    {"id":"dragon","name":"Dragon","config":"[Interface]\\nPrivateKey = key\\n"}
                ]
            }""".trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals(2, bundle.profiles.size)
        assertEquals("alice", bundle.profiles[0].id)
        assertEquals("Dragon", bundle.profiles[1].name)
        assertTrue(bundle.expiresAt > 0)
    }

    @Test(expected = IOException::class)
    fun rejectsDuplicateProfileIds() {
        SubscriptionBundle.parse(
            """{
                "version":1,
                "type":"rabbithole-turn-bundle",
                "expiresAt":"2100-01-30T20:59:00Z",
                "profiles":[
                    {"id":"dragon","name":"Dragon","config":"a"},
                    {"id":"dragon","name":"Dragon 2","config":"b"}
                ]
            }""".trimIndent().toByteArray(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun acceptsVersionedMediaType() {
        assertTrue(SubscriptionBundle.isMediaType("application/vnd.rabbithole.turn-bundle+json; version=1"))
    }
}
