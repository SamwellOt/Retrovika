package com.retrovika.app.remote

import com.retrovika.app.remote.LanAddress.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
    @Test
    fun prefersWifiOverMobileAndHotspot() {
        val list = listOf(
            Candidate("rmnet_data0", "10.45.3.2"),
            Candidate("ap0", "192.168.43.1"),
            Candidate("wlan0", "192.168.0.23"),
        )
        assertEquals("192.168.0.23", LanAddress.choose(list))
    }

    @Test
    fun usesHotspotWhenThereIsNoWifi() {
        val list = listOf(Candidate("rmnet_data0", "177.20.1.9"), Candidate("swlan0", "192.168.126.1"))
        assertEquals("192.168.126.1", LanAddress.choose(list))
    }

    @Test
    fun ignoresMobileDataEvenWithPrivateAddress() {
        // CGNAT: o dado móvel também tem 10.x, e sem Wi-Fi o QR apontaria para um endereço inalcançável.
        assertNull(LanAddress.choose(listOf(Candidate("rmnet_data0", "10.45.3.2"), Candidate("ccmni1", "10.1.2.3"), Candidate("tun0", "10.8.0.2"))))
        assertEquals("192.168.43.1", LanAddress.choose(listOf(Candidate("rmnet_data0", "10.45.3.2"), Candidate("ap0", "192.168.43.1"))))
    }

    @Test
    fun ignoresPublicAddresses() {
        assertNull(LanAddress.choose(listOf(Candidate("rmnet0", "177.20.1.9"))))
        assertTrue(LanAddress.isPrivate("172.20.0.5"))
        assertFalse(LanAddress.isPrivate("172.32.0.5"))
        assertFalse(LanAddress.isPrivate("fe80::1"))
    }
}
