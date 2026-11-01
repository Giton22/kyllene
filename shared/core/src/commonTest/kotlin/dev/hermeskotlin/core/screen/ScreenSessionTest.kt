package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A bot's screen, and the app's hold on it, when the link to the gateway is not what it should be.
 *
 * The phone is on Wi-Fi and the gateway may restart, so the link drops while the screen is being watched.
 * That is not the screen's fault and it is not a reason for the app to fall over.
 */
class ScreenSessionTest {

    private val url = FakeGateway.URL

    private val status = """{"profile":"scribe","supported":true,"installed":true,"running":true,
        "geometry":"1440x900","lease":{"holder":"agent","epoch":3},"profile_key":"scribe"}"""

    /** A gateway that answers `display.status` with [status]; the link never comes back on its own. */
    private suspend fun gateway(scope: CoroutineScope) = FakeGateway(scope, reconnects = false).also {
        it.answer = { call -> if (call.method == "display.status") status else "{}" }
        it.start()
    }

    private fun session(gateway: FakeGateway, scope: CoroutineScope) =
        ScreenSession("scribe", gateway.connection, url, gateway.client, scope)

    /** Drops the link and waits until the app knows it is gone. */
    private suspend fun drop(gateway: FakeGateway) {
        gateway.socket.serverClose(1006)
        gateway.connection.state.first { it !is ConnectionState.Connected }
    }

    @Test
    fun aDroppedLinkIsNotACrash() = runTest {
        val gateway = gateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.refresh()
        assertEquals("1440x900", session.state.value.status?.geometry)

        drop(gateway)

        // Asking anyway must not take the app down.
        session.refresh()
        assertNotNull(session.state.value.error)
    }

    @Test
    fun theLastAnswerStandsWhileTheLinkIsDown() = runTest {
        val gateway = gateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        session.refresh()

        drop(gateway)
        session.refresh()

        // The screen may well be fine: what the gateway last said still stands.
        assertEquals("1440x900", session.state.value.status?.geometry)
        assertEquals(DisplayLease.AGENT, session.state.value.lease?.holder)
    }

    @Test
    fun aRefusalIsTheGatewaysOwnWords() = runTest {
        val gateway = FakeGateway(backgroundScope).also {
            it.answer = { call -> call.error(5300, "a human holds this screen") }
            it.start()
        }
        val session = session(gateway, backgroundScope)

        session.refresh()

        // The gateway answered and said no: its words are the best explanation.
        assertEquals("a human holds this screen", session.state.value.error)
    }

    @Test
    fun takingOverWithNoLinkIsNotACrash() = runTest {
        val gateway = gateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        drop(gateway)

        session.takeOver()

        assertNotNull(session.state.value.error)
    }

    @Test
    fun handingBackWithNoLinkIsNotACrash() = runTest {
        val gateway = gateway(backgroundScope)
        val session = session(gateway, backgroundScope)
        drop(gateway)

        session.handBack()

        assertNotNull(session.state.value.error)
    }

    @Test
    fun aKeyWithNoStreamIsNotACrash() = runTest {
        val gateway = gateway(backgroundScope)
        val session = session(gateway, backgroundScope)

        // There is no stream yet, so there is nowhere to send the key: that is not an error.
        session.pressKey(0x41, true)
        session.movePointer(10, 10, 1)

        assertNull(session.state.value.error)
    }
}
