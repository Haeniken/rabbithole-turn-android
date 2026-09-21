/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProfileChangesTest {
    @Test
    fun addsDaenerysWithoutReplacingExistingProfiles() {
        val changes = SubscriptionProfileChanges.between(listOf("alice", "dragon"), listOf("alice", "daenerys", "dragon"))
        assertEquals(setOf("alice", "dragon"), changes.retained)
        assertEquals(setOf("daenerys"), changes.added)
        assertTrue(changes.removed.isEmpty())
        assertTrue(changes.membershipChanged)
    }

    @Test
    fun removesOnlyProfilesNoLongerReturned() {
        val changes = SubscriptionProfileChanges.between(listOf("alice", "daenerys", "dragon"), listOf("daenerys", "dragon"))
        assertEquals(setOf("daenerys", "dragon"), changes.retained)
        assertEquals(setOf("alice"), changes.removed)
        assertTrue(changes.added.isEmpty())
    }

    @Test
    fun canReplaceTheEntireSetAndRefreshItAgain() {
        val changes = SubscriptionProfileChanges.between(listOf("alice", "dragon"), listOf("daenerys"))
        assertTrue(changes.retained.isEmpty())
        assertEquals(setOf("alice", "dragon"), changes.removed)
        assertEquals(setOf("daenerys"), changes.added)
        val next = SubscriptionProfileChanges.between(listOf("daenerys"), listOf("daenerys"))
        assertFalse(next.membershipChanged)
        assertEquals(setOf("daenerys"), next.retained)
    }

    @Test
    fun orderChangesDoNotReplaceProfiles() {
        val changes = SubscriptionProfileChanges.between(listOf("alice", "dragon"), listOf("dragon", "alice"))
        assertFalse(changes.membershipChanged)
        assertEquals(setOf("alice", "dragon"), changes.retained)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAmbiguousStoredIds() {
        SubscriptionProfileChanges.between(listOf("alice", "alice"), listOf("alice"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAmbiguousReceivedIds() {
        SubscriptionProfileChanges.between(listOf("alice"), listOf("alice", "alice"))
    }
}
