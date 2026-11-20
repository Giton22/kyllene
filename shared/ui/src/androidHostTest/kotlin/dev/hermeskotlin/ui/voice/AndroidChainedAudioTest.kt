package dev.hermeskotlin.ui.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.ChatAudioRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidChainedAudioTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    @Test
    fun aChainedChatOnThePhoneSpeakerUsesCallRoutingAndRestoresItsMode() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        close()
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    /** Earbuds without a call profile would move to the phone's speaker, and the rest would drop to call quality. */
    @Test
    fun headphonesAndBluetoothKeepTheirUsualRoute() {
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            shadowOf(manager).setOutputDevices(listOf(speaker, device(type)))
            shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
            manager.mode = AudioManager.MODE_NORMAL
            val close = AndroidSpeechPlayer(context).chainedChatAudio()
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            assertNull(manager.communicationDevice)
            close()
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        }
    }

    /** Call mode without the speaker would read the reply into the earpiece; the chat keeps its usual route instead. */
    @Test
    fun aRouteThatWontTakeLeavesTheChatAudioAlone() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        manager.mode = AudioManager.MODE_NORMAL
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test
    fun aStaleCloseCannotRestoreOverANewerChat() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        val player = AndroidSpeechPlayer(context)
        manager.mode = AudioManager.MODE_NORMAL
        val first = player.chainedChatAudio()
        val second = player.chainedChatAudio()
        first()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        second()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    /** Headphones plugged in mid-chat take the reply; unplugged, the speaker gets call routing again. */
    @Test
    fun headphonesThatComeOrGoMidChatChangeTheRoute() {
        val headphones = device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)

        shadowOf(manager).addOutputDevice(headphones, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)

        shadowOf(manager).removeOutputDevice(headphones, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)

        close()
        shadowOf(manager).addOutputDevice(headphones, true)
        shadowOf(manager).removeOutputDevice(headphones, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    /** The chat, a phone call and the assistant panel share the player: one chat ending leaves the other's route. */
    @Test
    fun aChatThatEndsFirstLeavesTheOtherChatsRoute() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        val player = AndroidSpeechPlayer(context)
        manager.mode = AudioManager.MODE_NORMAL
        val first = player.chainedChatAudio()
        val second = player.chainedChatAudio()
        second()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        first()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test
    fun theModeBeforeTheChatComesBack() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        AndroidSpeechPlayer(context).chainedChatAudio()()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertNull(manager.communicationDevice)
    }

    /** The system drops call mode after a quiet spell between turns; the next listen takes it up again (#408). */
    @Test
    fun aModeTheSystemResetIsTakenUpAgainForTheNextListen() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        val close = AndroidSpeechPlayer(context, route).chainedChatAudio()
        manager.mode = AudioManager.MODE_NORMAL
        manager.clearCommunicationDevice()
        assertTrue(route.callAudio())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        close()
        assertFalse(route.callAudio())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    /** A phone call ringing or in progress owns the mode: the chat neither takes it over nor puts it back. */
    @Test
    fun aPhoneCallsModeIsLeftAlone() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        val close = AndroidSpeechPlayer(context, route).chainedChatAudio()
        for (call in listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL)) {
            manager.mode = call
            shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
            route.callAudio()
            assertEquals(call, manager.mode)
        }
        close()
    }

    /** Taken up again on the earpiece only, the reply would go there; the chat lets call routing go instead. */
    @Test
    fun aResetRouteThatWontTakeAgainIsLetGo() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        val close = AndroidSpeechPlayer(context, route).chainedChatAudio()
        manager.mode = AudioManager.MODE_NORMAL
        shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        assertFalse(route.callAudio())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    /** A route that didn't take is tried again when the outputs change, not before every listen and reply. */
    @Test
    fun aRouteThatDidntTakeIsntTriedAgainEachTurn() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        val close = AndroidSpeechPlayer(context, route).chainedChatAudio()
        assertFalse(route.callAudio())
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        assertFalse(route.callAudio())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
        close()
    }

    /** Headphones keep their usual route, and the recorder its usual microphone. */
    @Test
    fun noCallAudioWithoutCallRouting() {
        shadowOf(manager).setOutputDevices(listOf(speaker, device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val route = ChatAudioRoute()
        val player = AndroidSpeechPlayer(context, route)
        assertFalse(route.callAudio())
        val close = player.chainedChatAudio()
        assertFalse(route.callAudio())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close()
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun olderAndroidRestoresItsSpeakerphoneSetting() {
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(true, manager.isSpeakerphoneOn)
        close()
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun olderAndroidLeavesBluetoothEarbudsAlone() {
        shadowOf(manager).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close()
    }
}
