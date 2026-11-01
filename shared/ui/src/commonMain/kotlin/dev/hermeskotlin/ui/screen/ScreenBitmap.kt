package dev.hermeskotlin.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.screen.RfbFrame

/**
 * The frame's pixels as a picture Compose can draw.
 *
 * The pixels are ARGB, top-left first. The copy is made here, because the frame's own array belongs to the
 * client: the next update paints over it.
 */
expect fun screenImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap?

/**
 * The picture for [frame], made only when the frame changes: a screen paints many times a second, and
 * making a picture of each one would cost more than drawing it.
 */
@Composable
fun rememberScreenImage(frame: RfbFrame?): ImageBitmap? {
    val version = frame?.version
    val width = frame?.width
    val height = frame?.height
    return remember(version, width, height) {
        frame?.let { screenImageBitmap(it.pixels, it.width, it.height) }
    }
}
