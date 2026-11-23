package dev.hermeskotlin.android.notify

import androidx.core.telecom.CallAttributesCompat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
class TelecomCallAttributesTest {
    @Test
    fun outgoingCallUsesBotNameInBothTelecomFields() {
        val call = kylleneCallAttributes("Rook", incoming = false)

        assertEquals("Rook", call.displayName)
        assertEquals("sip:Rook@kyllene.invalid", call.address.toString())
        assertEquals(CallAttributesCompat.DIRECTION_OUTGOING, call.direction)
    }

    @Test
    fun incomingCallEncodesTheBotNameWithoutChangingItsDisplayName() {
        val call = kylleneCallAttributes("Róok @ home/office?", incoming = true)

        assertEquals("Róok @ home/office?", call.displayName)
        assertEquals("sip:R%C3%B3ok%20%40%20home%2Foffice%3F@kyllene.invalid", call.address.toString())
        assertEquals(CallAttributesCompat.DIRECTION_INCOMING, call.direction)
    }

    @Test
    fun blankNameUsesAppNameAndRepeatedNamesHaveTheSamePublicAddress() {
        val fallback = kylleneCallAttributes(" ", incoming = true)

        assertEquals("Kyllene", fallback.displayName)
        assertEquals("sip:Kyllene@kyllene.invalid", fallback.address.toString())
        assertEquals(
            kylleneCallAttributes("Rook", incoming = false).address,
            kylleneCallAttributes("Rook", incoming = true).address,
        )
    }
}
