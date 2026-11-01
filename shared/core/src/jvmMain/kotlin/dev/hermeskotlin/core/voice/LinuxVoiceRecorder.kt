package dev.hermeskotlin.core.voice

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PcmFrame(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

internal interface Microphone : AutoCloseable {
    fun start(onFrame: (PcmFrame) -> Unit, onFailure: (Throwable) -> Unit)
}

/** Owns one capture at a time; native callbacks only enqueue bounded, owned PCM frames. */
internal class LinuxVoiceRecorder(private val microphone: () -> Microphone) : VoiceRecorder {
    private val current = AtomicReference<Session?>()

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
        val session = Session()
        check(current.compareAndSet(null, session)) { "A recording is already in progress." }
        var device: Microphone? = null
        var failure: Throwable? = null
        try {
            runInterruptible(Dispatchers.IO) {
                microphone().also { device = it }.start(session::frame, session::fail)
            }
            val pcm = ByteArrayOutputStream()
            val detector = EndOfSpeech(activity)
            var rate = 0
            var channels = 1
            var samples = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = withTimeoutOrNull(2_000) { session.frames.receiveCatching() }
                    ?: error("The microphone stopped delivering audio.")
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    break
                }
                val frame = next.getOrThrow()
                check(frame.sampleRate in 8_000..192_000 && frame.channels in 1..2 &&
                    frame.bytes.isNotEmpty() && frame.bytes.size <= 8192 && frame.bytes.size % (frame.channels * 2) == 0) {
                    "The microphone returned an unsupported audio format."
                }
                if (rate == 0) { rate = frame.sampleRate; channels = frame.channels }
                check(rate == frame.sampleRate && channels == frame.channels) { "The microphone changed audio format." }
                check(pcm.size().toLong() + frame.bytes.size <= 32L * 1024 * 1024) { "The recording is too long." }
                pcm.write(frame.bytes)
                samples += frame.bytes.size / (channels * 2)
                val level = level(frame.bytes)
                onLevel(level)
                val heardBefore = detector.heardSpeech
                val done = detector.onFrame(level, samples * 1000 / rate)
                if (detector.heardSpeech && !heardBefore) onSpeech()
                if (done) break
            }
            session.complete()
            if (rate == 0) rate = 16_000
            return Recording(wav(pcm.toByteArray(), rate, channels), "audio/wav",
                detector.heardSpeech || session.finished && pcm.size() > rate * channels / 2)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            session.stop()
            var cleanupFailure: Throwable? = null
            try {
                withContext(NonCancellable + Dispatchers.IO) { device?.close() }
            } catch (cleanup: Throwable) { cleanupFailure = cleanup }
            try { onLevel(0f) } catch (cleanup: Throwable) {
                if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup)
            } finally { current.compareAndSet(session, null) }
            cleanupFailure?.let { if (failure == null) throw it else failure.addSuppressed(it) }
        }
    }

    override fun finish() { current.get()?.finish() }

    private fun level(bytes: ByteArray): Float {
        var sum = 0.0
        for (index in bytes.indices step 2) {
            val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toDouble()
            sum += sample * sample
        }
        // Match the shared voice detector's Android input level.
        return (sqrt(sum / (bytes.size / 2)) / 256.0 / 42.0).coerceAtMost(1.0).toFloat()
    }

    private class Session {
        private enum class End { Completed, Finished }
        private val terminal = AtomicReference<Any?>()
        val frames = Channel<PcmFrame>(32)
        val finished: Boolean get() = terminal.get() == End.Finished
        fun frame(frame: PcmFrame) {
            if (terminal.get() == null && frames.trySend(frame).isFailure) fail(IllegalStateException("The microphone couldn't keep up."))
        }
        fun fail(failure: Throwable) {
            if (terminal.compareAndSet(null, failure)) frames.close(failure)
        }
        fun finish() {
            if (terminal.compareAndSet(null, End.Finished)) frames.close()
        }
        fun complete() {
            terminal.compareAndSet(null, End.Completed)
            (terminal.get() as? Throwable)?.let { throw it }
        }
        fun stop() {
            terminal.compareAndSet(null, End.Completed)
            frames.cancel()
        }
    }
}
