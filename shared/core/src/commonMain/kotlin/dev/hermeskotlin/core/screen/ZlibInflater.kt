package dev.hermeskotlin.core.screen

/**
 * A zlib stream that survives across rectangles, the way the gateway's screen reuses its four Tight
 * streams. The window of the previous data is what makes the next rectangle decode, so a stream is only
 * reset when the server asks for it.
 */
internal expect class ZlibInflater() {

    /** Starts a new stream, dropping what the old one held. */
    fun reset()

    /** Feeds [data] and returns up to [expectedSize] bytes of output. */
    fun inflate(data: ByteArray, expectedSize: Int): ByteArray
}
