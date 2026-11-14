package com.wireguard.android.turn

import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class TurnPresentationTest {
    private fun config(address: String = "10.78.1.1/32", subtitle: String = "Портал", subscription: String = "old-name") = Config.parse(ByteArrayInputStream("""
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = $address
        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 127.0.0.1:9000
        #@rhv:ProfileSubtitle = $subtitle
        #@rhv:SubscriptionName = $subscription
    """.trimIndent().toByteArray()))

    @Test fun recommendationAndNameArePresentationOnly() {
        val old = TurnSettings(enabled=true,peer="example.invalid:56010",vkLink="https://vk.com/call/join/test")
        val new = old.copy(profileName="Noah",profileOrder=10,profileSubtitle="Портал ⭐ оптимальный")
        assertTrue(TurnPresentation.onlyPresentationChanged(config(),old,config(subtitle=new.profileSubtitle,subscription="Кроличья нора - haeniken"),new))
        assertFalse(TurnPresentation.onlyPresentationChanged(config(),old,config("10.78.2.2/32"),new))
        assertFalse(TurnPresentation.onlyPresentationChanged(config(),old,config(),new.copy(peer="other.invalid:56010")))
        assertFalse(TurnPresentation.onlyPresentationChanged(config(),null,config(),new))
    }

    @Test fun providerNameOrderAndMarkRoundTripWithoutChangingTransport() {
        val old=TurnSettings(enabled=true,peer="example.invalid:56010",vkLink="https://vk.com/call/join/test")
        val expected=old.copy(profileName="Noah",profileOrder=10,profileSubtitle="Портал ⭐ оптимальный")
        assertEquals(expected,TurnSettings.fromComments(expected.toComments()))
        assertEquals(old.connectionSettings(),expected.connectionSettings())
    }
}
