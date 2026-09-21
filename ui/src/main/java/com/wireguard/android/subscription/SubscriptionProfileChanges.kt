/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.subscription

/** Match by provider ID, independent of profile order and locally chosen tunnel names. */
internal data class SubscriptionProfileChanges(
    val retained: Set<String>,
    val added: Set<String>,
    val removed: Set<String>,
) {
    val membershipChanged: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    companion object {
        fun between(storedIds: Collection<String>, receivedIds: Collection<String>): SubscriptionProfileChanges {
            require(storedIds.size == storedIds.toSet().size) { "Duplicate stored subscription profile IDs" }
            require(receivedIds.size == receivedIds.toSet().size) { "Duplicate received subscription profile IDs" }
            val stored = storedIds.toSet()
            val received = receivedIds.toSet()
            return SubscriptionProfileChanges(stored intersect received, received - stored, stored - received)
        }
    }
}
