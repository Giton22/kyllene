package dev.hermeskotlin.core.screen

import java.util.zip.Inflater

internal actual class ZlibInflater actual constructor() {

    private val inflater = Inflater()

    actual fun reset() {
        inflater.reset()
    }

    actual fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        inflater.setInput(data)
        val out = ByteArray(expectedSize)
        var total = 0
        while (total < expectedSize) {
            val read = inflater.inflate(out, total, expectedSize - total)
            if (read <= 0) {
                // The stream needs more input, or it is done: either way there is nothing more to take.
                if (inflater.needsInput() || inflater.finished() || inflater.needsDictionary()) break
            }
            total += read
        }
        return if (total == expectedSize) out else out.copyOf(total)
    }
}
