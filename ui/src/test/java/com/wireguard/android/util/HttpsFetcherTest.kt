/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HttpsFetcherTest {
    @Test
    fun acceptsSubscriptionLabelWithCyrillicAndSpaces() {
        val uri = HttpsFetcher.validateUrl(" https://example.com/dive-bundle/test-token#Кроличья нора ")
        assertEquals("https://example.com/dive-bundle/test-token", uri.toString())
        assertNull(uri.fragment)
    }

    @Test
    fun labelsDoNotChangeSubscriptionIdentity() {
        val url = "https://example.com/dive-bundle/test-token"
        for (label in listOf("", "#", "#Кроличья нора", "#Other subscription", "#%D0%BD%D0%BE%D1%80%D0%B0")) {
            assertEquals(url, HttpsFetcher.validateUrl(url + label).toString())
        }
    }

    @Test
    fun preservesEscapedHashesAndQueryParameters() {
        val url = "https://example.com/sub/a%23b?token=c%23d&mode=bundle"
        assertEquals(url, HttpsFetcher.validateUrl("$url#Название подписки").toString())
    }

    @Test
    fun stillRejectsInvalidRequestAddresses() {
        for (url in listOf(
            "http://example.com/sub#Кроличья нора",
            "https://user:password@example.com/sub#Label",
            "https:///sub#Label",
            "https://example.com/invalid path#Label",
            "https://example.com/sub?token=invalid value#Label",
        )) {
            try {
                HttpsFetcher.validateUrl(url)
                fail("Invalid request URL was accepted")
            } catch (_: IOException) {
                // Only labels are stripped; HTTPS, authority and request syntax stay validated.
            }
        }
    }

    @Test
    fun malformedUrlErrorsDoNotExposeSubscriptionTokens() {
        try {
            HttpsFetcher.validateUrl("https://example.com/sub/private-test-token invalid")
            fail("Malformed URL was accepted")
        } catch (e: IOException) {
            assertFalse(e.stackTraceToString().contains("private-test-token"))
            assertNull(e.cause)
        }
    }
}
