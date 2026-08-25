/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.turn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single process-wide source of truth for connection orchestration.
 *
 * A [Session] is a capability tied to one monotonically increasing generation.
 * Every asynchronous continuation must present that capability before it can
 * update state. Starting or stopping a connection advances the generation, so
 * callbacks from an older attempt cannot mutate the newer session.
 */
class ConnectionStateMachine {
    enum class Phase {
        IDLE,
        PREPARING,
        DOWNLOADING_GEO_DATA,
        STARTING_VPN_SERVICE,
        AUTHORIZING,
        CAPTCHA_REQUIRED,
        CONNECTING_TRANSPORT,
        CONNECTING_TUNNEL,
        HANDING_OVER_NETWORK,
        CONNECTED,
        DEGRADED,
        STOPPING,
        FAILED,
    }

    data class Session(
        val generation: Long,
        val tunnelName: String,
    )

    data class Snapshot(
        val generation: Long = 0,
        val tunnelName: String? = null,
        val phase: Phase = Phase.IDLE,
        val detail: String? = null,
    )

    private val lock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow(Snapshot())

    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    fun begin(tunnelName: String): Session = synchronized(lock) {
        val session = Session(nextGeneration(), tunnelName)
        mutableState.value = Snapshot(session.generation, tunnelName, Phase.PREPARING)
        session
    }

    fun currentSession(): Session? = synchronized(lock) {
        val snapshot = mutableState.value
        snapshot.tunnelName?.let { Session(snapshot.generation, it) }
    }

    fun isCurrent(session: Session): Boolean = synchronized(lock) {
        val snapshot = mutableState.value
        snapshot.generation == session.generation && snapshot.tunnelName == session.tunnelName
    }

    fun isBusy(tunnelName: String): Boolean = synchronized(lock) {
        val snapshot = mutableState.value
        snapshot.tunnelName == tunnelName && snapshot.phase !in TERMINAL_PHASES
    }

    fun transition(session: Session, phase: Phase, detail: String? = null): Boolean = synchronized(lock) {
        val currentPhase = mutableState.value.phase
        if (!matches(session) || phase !in ALLOWED_TRANSITIONS.getValue(currentPhase)) return@synchronized false
        mutableState.value = Snapshot(session.generation, session.tunnelName, phase, detail)
        true
    }

    /** Invalidates the active generation and returns the generation used for cleanup. */
    fun beginStop(tunnelName: String): Session? = synchronized(lock) {
        val snapshot = mutableState.value
        if (snapshot.tunnelName != null && snapshot.tunnelName != tunnelName) return@synchronized null
        val session = Session(nextGeneration(), tunnelName)
        mutableState.value = Snapshot(session.generation, tunnelName, Phase.STOPPING)
        session
    }

    fun finishStop(session: Session) = finish(session, Phase.IDLE, null)

    fun fail(session: Session, detail: String?) = finish(session, Phase.FAILED, detail)

    fun markIdleIfCurrent(session: Session) = finish(session, Phase.IDLE, null)

    private fun finish(session: Session, phase: Phase, detail: String?) = synchronized(lock) {
        if (!matches(session)) return@synchronized
        val terminalGeneration = nextGeneration()
        mutableState.value = Snapshot(
            generation = terminalGeneration,
            tunnelName = if (phase == Phase.IDLE) null else session.tunnelName,
            phase = phase,
            detail = detail,
        )
    }

    private fun matches(session: Session): Boolean {
        val snapshot = mutableState.value
        return snapshot.generation == session.generation && snapshot.tunnelName == session.tunnelName
    }

    private fun nextGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    companion object {
        val CONNECTED_PHASES = setOf(Phase.CONNECTED, Phase.DEGRADED, Phase.HANDING_OVER_NETWORK)
        private val TERMINAL_PHASES = setOf(Phase.IDLE, Phase.FAILED)
        private val ALLOWED_TRANSITIONS = mapOf(
            Phase.IDLE to emptySet(),
            Phase.PREPARING to setOf(
                Phase.DOWNLOADING_GEO_DATA,
                Phase.STARTING_VPN_SERVICE,
                Phase.CONNECTING_TUNNEL,
            ),
            Phase.DOWNLOADING_GEO_DATA to setOf(
                Phase.STARTING_VPN_SERVICE,
                Phase.CONNECTING_TUNNEL,
            ),
            Phase.STARTING_VPN_SERVICE to setOf(
                Phase.AUTHORIZING,
                Phase.CONNECTING_TUNNEL,
            ),
            Phase.AUTHORIZING to setOf(
                Phase.CAPTCHA_REQUIRED,
                Phase.CONNECTING_TRANSPORT,
                Phase.CONNECTING_TUNNEL,
            ),
            Phase.CAPTCHA_REQUIRED to setOf(
                Phase.AUTHORIZING,
                Phase.CONNECTING_TRANSPORT,
                Phase.CONNECTED,
                Phase.DEGRADED,
                Phase.HANDING_OVER_NETWORK,
            ),
            Phase.CONNECTING_TRANSPORT to setOf(
                Phase.CAPTCHA_REQUIRED,
                Phase.CONNECTING_TUNNEL,
            ),
            Phase.CONNECTING_TUNNEL to setOf(Phase.CONNECTED),
            Phase.HANDING_OVER_NETWORK to setOf(Phase.CONNECTED, Phase.DEGRADED, Phase.CAPTCHA_REQUIRED),
            Phase.CONNECTED to setOf(Phase.HANDING_OVER_NETWORK, Phase.DEGRADED, Phase.CAPTCHA_REQUIRED),
            Phase.DEGRADED to setOf(
                Phase.AUTHORIZING,
                Phase.CAPTCHA_REQUIRED,
                Phase.CONNECTING_TRANSPORT,
                Phase.CONNECTING_TUNNEL,
                Phase.HANDING_OVER_NETWORK,
                Phase.CONNECTED,
            ),
            Phase.STOPPING to emptySet(),
            Phase.FAILED to emptySet(),
        )
    }
}
