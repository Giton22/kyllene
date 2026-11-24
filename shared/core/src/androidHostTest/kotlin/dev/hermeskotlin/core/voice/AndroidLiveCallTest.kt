package dev.hermeskotlin.core.voice

import org.webrtc.PeerConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLiveCallTest {

    @Test
    fun theNetworkMonitorIsOffOnlyWhileAVpnIsUp() {
        assertTrue(liveCallOptions(vpnUp = true).disableNetworkMonitor)
        assertFalse(liveCallOptions(vpnUp = false).disableNetworkMonitor)
    }

    @Test
    fun loopbackStaysOutOfTheOffer() {
        // WebRTC ignores loopback by default, but not once options are passed: the mask then is the one given.
        for (vpnUp in listOf(true, false)) {
            assertEquals(PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK, liveCallOptions(vpnUp).networkIgnoreMask)
        }
    }
}
