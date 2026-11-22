package dev.hermeskotlin.desktop

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WarmupTest {

    // The app swallows a scene that fails to draw, so a broken one would only quietly stop warming its page.
    @Test
    fun everyWarmedSceneDrawsOffScreen() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val failed = Warmup.SCENES.mapNotNull { scene ->
                executor.submit<Throwable?> { runCatching { Warmup.draw(scene, dispatcher) }.exceptionOrNull() }.get()
                    ?.let { "$scene: $it" }
            }
            assertEquals(emptyList(), failed)
        } finally {
            executor.shutdownNow()
        }
    }

    // Warmup.start() runs alongside the live app window, so it must only link classes on its background thread and
    // never compose off-screen scenes concurrently with the AWT EDT.
    @Test
    fun startLinksOnBackgroundThreadOffEdt() {
        val calls = AtomicInteger(0)
        var linkedOnEdt: Boolean? = null
        var threadName: String? = null
        var isDaemon: Boolean? = null

        val worker = Warmup.start(delayMs = 0) {
            calls.incrementAndGet()
            linkedOnEdt = SwingUtilities.isEventDispatchThread()
            threadName = Thread.currentThread().name
            isDaemon = Thread.currentThread().isDaemon
        }
        worker.join(10_000)

        assertFalse(worker.isAlive, "warmup thread should finish")
        assertEquals(1, calls.get())
        assertEquals(false, linkedOnEdt)
        assertEquals("warmup", threadName)
        assertEquals(true, isDaemon)
    }
}
