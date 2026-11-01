package dev.hermeskotlin.core.screen

/**
 * A byte pipe to the gateway's display stream: the RFB bytes of a bot's desktop, both ways. The client
 * reads whole messages from it, so the transport only has to keep the order and not lose bytes.
 */
interface RfbTransport {

    /** The next bytes from the stream, or null once it ended. */
    suspend fun read(): ByteArray?

    /** Sends bytes to the stream. */
    suspend fun write(bytes: ByteArray)

    /** Closes the stream. */
    suspend fun close()
}

/** The stream ended, or the gateway's screen sent something this client cannot read. */
class RfbException(message: String) : Exception(message)

/** What the gateway's screen told us about itself when the connection opened. */
data class RfbServerInfo(val width: Int, val height: Int, val name: String)

/**
 * One painted frame of the bot's desktop. [pixels] are ARGB, top-left first, and belong to the client:
 * the next update changes them, so a caller that keeps a frame draws it before reading the next one.
 */
class RfbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Counts the frames the client has painted, so a caller can tell one update from the next. */
    val version: Long,
)
