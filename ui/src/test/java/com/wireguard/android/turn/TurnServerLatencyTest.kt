package com.wireguard.android.turn

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TurnServerLatencyTest {
    @Test fun checksRemoteTurnHostOnHttpsNotTheLocalWireGuardProxy() {
        val target=TurnServerLatency.target(TurnSettings(enabled=true,peer="alice.haeniken.com:56010"))
        assertEquals("alice.haeniken.com",target.hostString)
        assertEquals(443,target.port)
        assertTrue(target.isUnresolved)
    }
    @Test fun supportsIpv6AndDoesNotProbeLocalEndpoint() {
        assertEquals("2001:db8::1",TurnServerLatency.target(TurnSettings(enabled=true,peer="[2001:db8::1]:56010")).hostString)
        assertThrows(IOException::class.java) { TurnServerLatency.target(TurnSettings(enabled=true,peer="127.0.0.1:9000")) }
        assertThrows(IOException::class.java) { TurnServerLatency.target(TurnSettings()) }
    }
}
