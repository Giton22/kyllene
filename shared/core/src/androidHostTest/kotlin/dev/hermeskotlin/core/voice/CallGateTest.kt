package dev.hermeskotlin.core.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock order of a call. WebRTC runs most calls on its signaling thread and waits for them there, and it
 * delivers its callbacks on that same thread; here one thread stands in for it.
 */
class CallGateTest {

    @Test
    fun aCallbackOnTheThreadACallWaitsForDoesNotStopIt() {
        val gate = CallGate()
        val signaling = Executors.newSingleThreadExecutor()
        try {
            val callbackRunning = CountDownLatch(1)
            val callInside = CountDownLatch(1)
            // A channel state change being delivered: it asks the gate whether the call is closed.
            val callback = signaling.submit<Boolean> {
                callbackRunning.countDown()
                check(callInside.await(10, TimeUnit.SECONDS))
                gate.closed
            }
            assertTrue(callbackRunning.await(10, TimeUnit.SECONDS))
            // send(): inside the gate, a call that waits for the signaling thread, which is still in the callback.
            val sent = thread(name = "app") {
                gate.use({}) {
                    callInside.countDown()
                    signaling.submit {}.get(10, TimeUnit.SECONDS)
                }
            }
            assertFalse(callback.get(10, TimeUnit.SECONDS))
            sent.join(10_000)
            assertFalse(sent.isAlive, "the call and the callback stopped each other")
        } finally {
            signaling.shutdownNow()
        }
    }

    @Test
    fun workInsideHoldsNoLockAndNeitherDoesTheRelease() {
        val gate = CallGate()
        assertEquals(false, gate.use({ null }) { gate.lockHeld })
        var heldInRelease = true
        assertTrue(gate.close { heldInRelease = gate.lockHeld })
        assertFalse(heldInRelease)
    }

    @Test
    fun closeWaitsForTheCallInsideWhileCallbacksStillRun() {
        val gate = CallGate()
        val signaling = Executors.newSingleThreadExecutor()
        try {
            val inside = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val order = mutableListOf<String>()
            val call = thread(name = "app") {
                gate.use({}) {
                    inside.countDown()
                    check(finish.await(10, TimeUnit.SECONDS))
                    synchronized(order) { order += "call returned" }
                }
            }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val closer = thread(name = "closer") { gate.close { synchronized(order) { order += "released" } } }
            // The hang-up is waiting for the call. A callback that comes now sees the call closed and isn't held up.
            while (!gate.closed) Thread.sleep(5)
            assertTrue(signaling.submit<Boolean> { gate.closed }.get(10, TimeUnit.SECONDS))
            assertTrue(closer.isAlive)
            assertTrue(synchronized(order) { order.isEmpty() })

            finish.countDown()
            call.join(10_000)
            closer.join(10_000)
            assertFalse(closer.isAlive)
            assertEquals(listOf("call returned", "released"), order)
        } finally {
            signaling.shutdownNow()
        }
    }

    @Test
    fun aClosedGateTurnsWorkAwayAndReleasesOnce() {
        val gate = CallGate()
        var released = 0
        assertTrue(gate.close { released++ })
        assertFalse(gate.close { released++ })
        assertEquals(1, released)
        assertEquals("closed", gate.use({ "closed" }) { "ran" })
    }
}
