package dev.hermeskotlin.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidLiveCallSdpTest {

    @Test
    fun createAndApplySdpResumeAndCancelCleanly() = runTest {
        val offer = SessionDescription(SessionDescription.Type.OFFER, "v=0")
        assertEquals(offer, createSdp { it.onCreateSuccess(offer) })
        applySdp { it.onSetSuccess() }

        assertFailsWith<IllegalStateException> { createSdp { it.onCreateFailure("boom") } }
        assertFailsWith<IllegalStateException> { applySdp { it.onSetFailure("refused") } }

        // Cancellation does not hang and ignores late WebRTC callbacks.
        val started = CompletableDeferred<SdpObserver>()
        val job = launch { createSdp { started.complete(it) } }
        val observer = started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        observer.onCreateSuccess(offer)
        observer.onCreateFailure("late")

        val applyStarted = CompletableDeferred<SdpObserver>()
        val applyJob = launch { applySdp { applyStarted.complete(it) } }
        val applyObserver = applyStarted.await()
        applyJob.cancel()
        applyJob.join()
        assertTrue(applyJob.isCancelled)
        applyObserver.onSetSuccess()
        applyObserver.onSetFailure("late")
    }
}
