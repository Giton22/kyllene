package dev.hermeskotlin.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundHoldTest {

    private class Service(var allowed: Boolean = true) {
        val calls = mutableListOf<String>()
        val hold = ForegroundHold(
            startService = {
                calls += "start"
                allowed
            },
            stopService = { calls += "stop" },
        )
    }

    @Test
    fun aReleaseBeforeTheServiceIsInTheForegroundLeavesItToStopItself() {
        val service = Service()

        service.hold.hold()
        // The turn ended before Android ran onStartCommand.
        service.hold.release()

        assertEquals(listOf("start"), service.calls)
        assertFalse(service.hold.started())
    }

    @Test
    fun aReleaseOnceInTheForegroundStopsIt() {
        val service = Service()

        service.hold.hold()
        assertTrue(service.hold.started())
        service.hold.release()

        assertEquals(listOf("start", "stop"), service.calls)
        assertFalse(service.hold.wanted)
    }

    @Test
    fun heldAgainWhileStartingItStaysUp() {
        val service = Service()

        service.hold.hold()
        service.hold.release()
        service.hold.hold()

        assertTrue(service.hold.started())
        assertEquals(listOf("start", "start"), service.calls)
        service.hold.release()
        assertEquals(listOf("start", "start", "stop"), service.calls)
    }

    @Test
    fun aNewServiceAskedForBeforeTheOldOneIsGoneIsNotStoppedBeforeItIsInTheForeground() {
        val service = Service()

        service.hold.hold()
        service.hold.started()
        service.hold.release()
        // A new turn starts and ends before the stopped one's onDestroy.
        service.hold.hold()
        service.hold.release()

        assertEquals(listOf("start", "stop", "start"), service.calls)
        service.hold.stopped()
        assertFalse(service.hold.started())
    }

    @Test
    fun aSecondHoldDoesNotAskAgain() {
        val service = Service()

        service.hold.hold()
        service.hold.hold()

        assertEquals(listOf("start"), service.calls)
    }

    @Test
    fun aRefusedStartIsNotHeldAndIsNotStopped() {
        val service = Service(allowed = false)

        assertFalse(service.hold.hold())
        service.hold.release()

        assertFalse(service.hold.wanted)
        assertEquals(listOf("start"), service.calls)
    }

    @Test
    fun aStoppedServiceIsNotStoppedAgain() {
        val service = Service()

        service.hold.hold()
        service.hold.started()
        service.hold.stopped()
        service.hold.release()

        assertEquals(listOf("start"), service.calls)
    }

    @Test
    fun oneThatCouldNotGoForegroundIsAskedForAgain() {
        val service = Service()

        service.hold.hold()
        service.hold.lost()
        service.hold.hold()

        assertEquals(listOf("start", "start"), service.calls)
    }
}
