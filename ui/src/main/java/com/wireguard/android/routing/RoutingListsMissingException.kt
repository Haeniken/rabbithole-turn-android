/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.routing

/** TURN is deliberately fail-closed until a valid IP bypass list is available. */
class RoutingListsMissingException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
