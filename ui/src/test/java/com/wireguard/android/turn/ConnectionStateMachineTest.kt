/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {
    @Test
    fun newerGenerationRejectsStaleCallbacks() {
        val machine = ConnectionStateMachine()
        val alice = machine.begin("Alice")
        val dragon = machine.begin("Dragon")

        assertFalse(machine.transition(alice, ConnectionStateMachine.Phase.CONNECTED))
        assertTrue(machine.transition(dragon, ConnectionStateMachine.Phase.CONNECTING_TUNNEL))
        assertEquals("Dragon", machine.state.value.tunnelName)
        assertEquals(ConnectionStateMachine.Phase.CONNECTING_TUNNEL, machine.state.value.phase)
    }

    @Test
    fun stoppingOldTunnelDoesNotInvalidateNewTunnel() {
        val machine = ConnectionStateMachine()
        machine.begin("Alice")
        val dragon = machine.begin("Dragon")

        assertNull(machine.beginStop("Alice"))
        assertTrue(machine.isCurrent(dragon))
    }

    @Test
    fun completedStopInvalidatesCleanupGeneration() {
        val machine = ConnectionStateMachine()
        val session = machine.begin("Alice")
        val stop = machine.beginStop("Alice")!!

        assertFalse(machine.isCurrent(session))
        machine.finishStop(stop)
        assertFalse(machine.isCurrent(stop))
        assertEquals(ConnectionStateMachine.Phase.IDLE, machine.state.value.phase)
        assertNull(machine.state.value.tunnelName)
    }

    @Test
    fun failedGenerationCannotReturnToConnected() {
        val machine = ConnectionStateMachine()
        val session = machine.begin("Alice")

        machine.fail(session, "boom")

        assertFalse(machine.transition(session, ConnectionStateMachine.Phase.CONNECTED))
        assertEquals(ConnectionStateMachine.Phase.FAILED, machine.state.value.phase)
        assertEquals("boom", machine.state.value.detail)
    }

    @Test
    fun impossiblePhaseJumpIsRejected() {
        val machine = ConnectionStateMachine()
        val session = machine.begin("Alice")

        assertFalse(machine.transition(session, ConnectionStateMachine.Phase.CONNECTED))
        assertEquals(ConnectionStateMachine.Phase.PREPARING, machine.state.value.phase)
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.STARTING_VPN_SERVICE))
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.AUTHORIZING))
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TRANSPORT))
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TUNNEL))
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.CONNECTED))
    }

    @Test
    fun connectedCaptchaCanReturnToTheSameSession() {
        val machine = ConnectionStateMachine()
        val session = machine.begin("Alice")
        machine.transition(session, ConnectionStateMachine.Phase.STARTING_VPN_SERVICE)
        machine.transition(session, ConnectionStateMachine.Phase.AUTHORIZING)
        machine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TRANSPORT)
        machine.transition(session, ConnectionStateMachine.Phase.CONNECTING_TUNNEL)
        machine.transition(session, ConnectionStateMachine.Phase.CONNECTED)

        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.CAPTCHA_REQUIRED))
        assertTrue(machine.transition(session, ConnectionStateMachine.Phase.CONNECTED))
        assertEquals(ConnectionStateMachine.Phase.CONNECTED, machine.state.value.phase)
    }
}
