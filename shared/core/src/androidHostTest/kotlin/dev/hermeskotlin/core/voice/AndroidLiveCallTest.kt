package dev.hermeskotlin.core.voice

import org.webrtc.PeerConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLiveCallTest {

    @Test
    fun theNetworkMonitorIsOff() {
        // With it on, gathering can finish before Android has reported a network, and the offer goes out empty.
        assertTrue(liveCallOptions().disableNetworkMonitor)
    }

    @Test
    fun loopbackStaysOutOfTheOffer() {
        // WebRTC ignores loopback by default, but not once options are passed: the mask then is the one given.
        assertEquals(PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK, liveCallOptions().networkIgnoreMask)
    }
}
