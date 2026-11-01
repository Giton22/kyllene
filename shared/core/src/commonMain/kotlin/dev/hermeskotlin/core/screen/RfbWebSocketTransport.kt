package dev.hermeskotlin.core.screen

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send

/**
 * The display stream over a WebSocket: the gateway splices the RFB bytes of the bot's desktop into the
 * socket as binary frames, so a frame here is a piece of the protocol.
 */
class WebSocketRfbTransport(private val session: DefaultClientWebSocketSession) : RfbTransport {

    override suspend fun read(): ByteArray? {
        val frame = session.incoming.receive()
        if (frame !is Frame.Binary) return null
        return frame.readBytes()
    }

    override suspend fun write(bytes: ByteArray) = session.send(Frame.Binary(false, bytes))

    override suspend fun close() {
        session.close()
    }
}

/**
 * Opens the display stream. [url] is the gateway's own route with the ticket the gateway minted, so the
 * ticket travels in the query and nowhere else.
 */
suspend fun HttpClient.openDisplayStream(url: String): RfbTransport {
    val session = webSocketSession(url)
    return WebSocketRfbTransport(session)
}
