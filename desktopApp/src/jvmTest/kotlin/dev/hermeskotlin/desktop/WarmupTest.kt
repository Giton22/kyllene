package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.preview.PreviewScene
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
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

    // The pages readied first are drawn from the same list; one missing from it would never be readied at all.
    @Test
    fun thePagesReadiedFirstAreWarmedScenes() {
        assertEquals(emptyList(), Warmup.FIRST - Warmup.SCENES.toSet())
    }

    // Compose keeps unsynchronised global caches (e.g. NodeKindKt's modifier map), so warming scenes must run on the
    // AWT EDT alongside the app window rather than concurrently on the background warmup thread.
    @Test
    fun startDrawsScenesOnEdtAndLinksOffEdt() {
        val steps = mutableListOf<String>()
        var linkedOnEdt: Boolean? = null
        val drawnOnEdt = mutableListOf<Boolean>()

        val worker = Warmup.start(
            delayMs = 0,
            gapMs = 0,
            scenes = listOf(PreviewScene.EmptyPage, PreviewScene.LoadError),
            first = listOf(PreviewScene.EmptyPage),
            link = {
                linkedOnEdt = SwingUtilities.isEventDispatchThread()
                steps += "link"
            },
            drawScene = { scene ->
                Warmup.drawOnEdt(scene) { drawn, _ ->
                    drawnOnEdt += SwingUtilities.isEventDispatchThread()
                    steps += "draw:$drawn"
                }
            },
        )
        worker.join(10_000)

        assertFalse(worker.isAlive, "warmup thread should finish")
        assertEquals(false, linkedOnEdt)
        assertEquals(listOf(true, true), drawnOnEdt)
        assertEquals(listOf("draw:EmptyPage", "link", "draw:LoadError"), steps)
    }
}
