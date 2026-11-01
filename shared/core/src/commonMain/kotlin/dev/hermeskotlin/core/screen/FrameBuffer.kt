package dev.hermeskotlin.core.screen

/**
 * The pixels of the bot's desktop, in ARGB, top-left first. The decoders paint into it; the app draws it.
 */
class FrameBuffer(var width: Int = 0, var height: Int = 0) {

    var pixels: IntArray = IntArray(width * height)
        private set

    /** Number of updates painted into this buffer, so a caller can tell one frame from the next. */
    var version: Long = 0
        private set

    fun set(x: Int, y: Int, value: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = value
    }

    fun fill(x: Int, y: Int, rectWidth: Int, rectHeight: Int, value: Int) {
        for (row in 0 until rectHeight) {
            val target = (y + row) * width + x
            if (target < 0 || target + rectWidth > pixels.size) continue
            pixels.fill(value, target, target + rectWidth)
        }
    }

    /** Copies a rectangle; the source and the target may overlap. */
    fun copy(destinationX: Int, destinationY: Int, rectWidth: Int, rectHeight: Int, sourceX: Int, sourceY: Int) {
        for (row in 0 until rectHeight) {
            val source = (sourceY + row) * width + sourceX
            val target = (destinationY + row) * width + destinationX
            if (source < 0 || target < 0 || source + rectWidth > pixels.size || target + rectWidth > pixels.size) continue
            pixels.copyInto(pixels, target, source, source + rectWidth)
        }
    }

    /** A new size, as the gateway reports when the bot's desktop was resized. */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        width = newWidth
        height = newHeight
        pixels = IntArray(newWidth * newHeight)
    }

    fun painted() {
        version++
    }
}
