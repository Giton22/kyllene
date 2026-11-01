package dev.hermeskotlin.core.screen

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RFB client against a server that plays the bytes a screen would: the greeting, the desktop's size
 * and then the updates.
 */
class RfbClientTest {

    private class Server(private val script: ByteArray) : RfbTransport {
        private var position = 0
        val sent = mutableListOf<ByteArray>()

        override suspend fun read(): ByteArray? {
            if (position >= script.size) return null
            val size = minOf(64 * 1024, script.size - position)
            val chunk = script.copyOfRange(position, position + size)
            position += size
            return chunk
        }

        override suspend fun write(bytes: ByteArray) {
            sent += bytes
        }

        override suspend fun close() = Unit
    }

    @Test
    fun theHandshakeAsksForTheScreen() = runTest {
        val server = Server(serverScript(1440, 900, "hermes:default", frameUpdate()))
        val client = RfbClient(server)

        val info = client.connect()

        assertEquals(1440, info.width)
        assertEquals(900, info.height)
        assertEquals("hermes:default", info.name)

        // The client asks for a pixel format it can read, then for the encodings it can.
        val pixelFormat = server.sent.first { it[0] == 0.toByte() }
        assertEquals(32, pixelFormat[4].toInt())
        assertEquals(1, pixelFormat[7].toInt()) // true colour

        val encodings = server.sent.first { it[0] == 2.toByte() }
        assertEquals(5, encodings.readShort(2))
        assertEquals(RfbClient.ENCODING_RAW, encodings.readInt(4))
        assertEquals(RfbClient.ENCODING_COPY_RECT, encodings.readInt(8))
        assertEquals(RfbClient.ENCODING_HEXTILE, encodings.readInt(12))
        assertEquals(RfbClient.ENCODING_TIGHT, encodings.readInt(16))
        assertEquals(RfbClient.ENCODING_DESKTOP_SIZE, encodings.readInt(20))

        // And then for the first frame.
        assertEquals(3, server.sent.first { it[0] == 3.toByte() }[0].toInt())
    }

    @Test
    fun aRawRectangleIsPainted() = runTest {
        val pixels = intArrayOf(
            0x10, 0x20, 0x30, 0x00, // blue, green, red
            0x40, 0x50, 0x60, 0x00,
            0x70, 0x80, 0x90, 0x00,
            0xA0, 0xB0, 0xC0, 0x00,
        ).map { it.toByte() }.toByteArray()
        val server = Server(serverScript(2, 2, "screen", frameUpdate(rawRect(0, 0, 2, 2, pixels))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(2, frame?.width)
        assertEquals(0xFF302010.toInt(), frame?.pixels?.get(0))
        assertEquals(0xFF605040.toInt(), frame?.pixels?.get(1))
        assertEquals(0xFF908070.toInt(), frame?.pixels?.get(2))
        assertEquals(0xFFC0B0A0.toInt(), frame?.pixels?.get(3))
    }

    @Test
    fun aCopiedRectangleIsPainted() = runTest {
        val pixels = intArrayOf(
            0x11, 0x22, 0x33, 0x00,
            0x44, 0x55, 0x66, 0x00,
            0x77, 0x88, 0x99, 0x00,
            0xAA, 0xBB, 0xCC, 0x00,
        ).map { it.toByte() }.toByteArray()
        val copy = bytes(shortBytes(0), shortBytes(0)) // copy from the top-left corner
        val server = Server(
            serverScript(
                2, 2, "screen",
                frameUpdate(rawRect(0, 0, 2, 2, pixels), copyRect(0, 1, 1, 1, copy)),
            ),
        )
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(0xFF332211.toInt(), frame?.pixels?.get(0))
        assertEquals(0xFF332211.toInt(), frame?.pixels?.get(2)) // the copied pixel
    }

    @Test
    fun aHextileTileIsPainted() = runTest {
        // Background, foreground and one subrectangle of the foreground.
        val tile = bytes(
            byteArrayOf((0x02 or 0x04 or 0x08).toByte()),
            byteArrayOf(0x01, 0x02, 0x03, 0x00), // background: blue, green, red
            byteArrayOf(0x04, 0x05, 0x06, 0x00), // foreground
            byteArrayOf(1), // one subrectangle
            byteArrayOf(0x00), // at 0,0
            byteArrayOf(0x00), // one by one
        )
        val server = Server(serverScript(16, 16, "screen", frameUpdate(hextileRect(0, 0, 16, 16, tile))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(0xFF060504.toInt(), frame?.pixels?.get(0)) // the subrectangle
        assertEquals(0xFF030201.toInt(), frame?.pixels?.get(1)) // the background around it
        assertEquals(0xFF030201.toInt(), frame?.pixels?.get(16)) // the rest of the tile
    }

    @Test
    fun aTightRectangleIsPainted() = runTest {
        // Copy filter, stream 0: the pixels travel as they are.
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val rect = bytes(
            byteArrayOf(0x40), // filter present, stream 0
            byteArrayOf(0x00), // copy filter
            data,
        )
        val server = Server(serverScript(2, 1, "screen", frameUpdate(tightRect(0, 0, 2, 1, rect))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(0xFF112233.toInt(), frame?.pixels?.get(0))
        assertEquals(0xFF445566.toInt(), frame?.pixels?.get(1))
    }

    @Test
    fun aTightPaletteIsPainted() = runTest {
        // Two colours, one bit per pixel, compressed: the long way round the zlib path.
        val rowSize = 16 // 128 pixels, one bit each
        val data = ByteArray(rowSize)
        data[0] = 0b10101010.toByte()
        data[1] = 0b01010101.toByte()
        val compressed = deflateStored(data)
        val rect = bytes(
            byteArrayOf(0x41), // reset stream 0, filter present, stream 0
            byteArrayOf(0x01), // palette filter
            byteArrayOf(1), // two colours
            byteArrayOf(0x0A, 0x0B, 0x0C), // colour one
            byteArrayOf(0x0D, 0x0E, 0x0F), // colour two
            byteArrayOf(compressed.size.toByte()),
            compressed,
        )
        val server = Server(serverScript(128, 1, "screen", frameUpdate(tightRect(0, 0, 128, 1, rect))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        // The bits run from the top: 10101010, so the second colour, the first, the second.
        assertEquals(0xFF0D0E0F.toInt(), frame?.pixels?.get(0))
        assertEquals(0xFF0A0B0C.toInt(), frame?.pixels?.get(1))
        assertEquals(0xFF0D0E0F.toInt(), frame?.pixels?.get(2))
    }

    @Test
    fun aTightGradientIsPainted() = runTest {
        // Two pixels, each a difference from the pixel before it.
        val data = byteArrayOf(10, 20, 30, 5, 5, 5)
        val rect = bytes(
            byteArrayOf(0x42), // filter present, stream 2
            byteArrayOf(0x02), // gradient filter
            data,
        )
        val server = Server(serverScript(1, 2, "screen", frameUpdate(tightRect(0, 0, 1, 2, rect))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(0xFF0A141E.toInt(), frame?.pixels?.get(0))
        assertEquals(0xFF0F1923.toInt(), frame?.pixels?.get(1))
    }

    @Test
    fun aResizedDesktopIsTaken() = runTest {
        val server = Server(serverScript(800, 600, "screen", frameUpdate(desktopSizeRect(1024, 768))))
        val client = RfbClient(server)
        client.connect()

        val frame = client.nextFrame()

        assertEquals(1024, frame?.width)
        assertEquals(768, frame?.height)
    }

    @Test
    fun aKeyIsSent() = runTest {
        val server = Server(serverScript(2, 2, "screen", frameUpdate()))
        val client = RfbClient(server)
        client.connect()

        client.keyEvent(0x61, down = true)

        val message = server.sent.first { it[0] == 4.toByte() }
        assertEquals(8, message.size)
        assertEquals(1, message[1].toInt())
        assertEquals(0x61, message.readInt(4))
    }

    @Test
    fun aPointerIsSent() = runTest {
        val server = Server(serverScript(2, 2, "screen", frameUpdate()))
        val client = RfbClient(server)
        client.connect()

        client.pointerEvent(120, 340, mask = 1)

        val message = server.sent.first { it[0] == 5.toByte() }
        assertEquals(6, message.size)
        assertEquals(1, message[1].toInt())
        assertEquals(120, message.readShort(2))
        assertEquals(340, message.readShort(4))
    }

    @Test
    fun textIsSentToTheClipboard() = runTest {
        val server = Server(serverScript(2, 2, "screen", frameUpdate()))
        val client = RfbClient(server)
        client.connect()

        client.clientCutText("hi")

        val message = server.sent.first { it[0] == 6.toByte() }
        assertEquals(2, message.readInt(4))
        assertContentEquals("hi".encodeToByteArray(), message.copyOfRange(8, 10))
    }

    @Test
    fun aScreenThatAsksForAPasswordIsRefused() = runTest {
        val script = bytes(
            text("RFB 003.008\n"),
            byteArrayOf(1, 2), // one security type: VNC authentication
        )
        val server = Server(script)

        val failure = runCatching { RfbClient(server).connect() }.exceptionOrNull()

        assertTrue(failure is RfbException)
        assertTrue(failure!!.message!!.contains("password"))
    }

    // --- the bytes a screen sends ---------------------------------------------------------

    private fun serverScript(width: Int, height: Int, name: String, updates: ByteArray): ByteArray = bytes(
        text("RFB 003.008\n"),
        byteArrayOf(1, 1), // one security type: none
        byteArrayOf(0, 0, 0, 0), // the security result: fine
        shortBytes(width),
        shortBytes(height),
        pixelFormat(),
        intBytes(name.length),
        text(name),
        updates,
    )

    private fun pixelFormat(): ByteArray = bytes(
        byteArrayOf(32, 24, 0, 1), // bits per pixel, depth, little-endian, true colour
        shortBytes(255), shortBytes(255), shortBytes(255),
        byteArrayOf(16, 8, 0, 0, 0, 0),
    )

    private fun frameUpdate(vararg rectangles: ByteArray): ByteArray = bytes(
        byteArrayOf(0, 0), // a framebuffer update, no padding
        shortBytes(rectangles.size),
        *rectangles,
    )

    private fun rect(x: Int, y: Int, width: Int, height: Int, encoding: Int): ByteArray = bytes(
        shortBytes(x), shortBytes(y), shortBytes(width), shortBytes(height), intBytes(encoding),
    )

    private fun rawRect(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray): ByteArray =
        bytes(rect(x, y, width, height, RfbClient.ENCODING_RAW), pixels)

    private fun copyRect(x: Int, y: Int, width: Int, height: Int, source: ByteArray): ByteArray =
        bytes(rect(x, y, width, height, RfbClient.ENCODING_COPY_RECT), source)

    private fun hextileRect(x: Int, y: Int, width: Int, height: Int, tiles: ByteArray): ByteArray =
        bytes(rect(x, y, width, height, RfbClient.ENCODING_HEXTILE), tiles)

    private fun tightRect(x: Int, y: Int, width: Int, height: Int, data: ByteArray): ByteArray =
        bytes(rect(x, y, width, height, RfbClient.ENCODING_TIGHT), data)

    private fun desktopSizeRect(width: Int, height: Int): ByteArray =
        rect(0, 0, width, height, RfbClient.ENCODING_DESKTOP_SIZE)

    /** A zlib stream that stores its data as it is: the shortest way to test the compressed path. */
    private fun deflateStored(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        fun add(bytes: ByteArray) {
            bytes.forEach { out.add(it) }
        }
        add(byteArrayOf(0x78, 0x01)) // the zlib header
        var offset = 0
        while (offset < data.size) {
            val size = minOf(65535, data.size - offset)
            val last = if (offset + size >= data.size) 1 else 0
            val inverted = size.inv() and 0xFFFF
            add(
                byteArrayOf(
                    last.toByte(),
                    (size and 0xFF).toByte(),
                    (size shr 8).toByte(),
                    (inverted and 0xFF).toByte(),
                    (inverted shr 8).toByte(),
                ),
            )
            add(data.copyOfRange(offset, offset + size))
            offset += size
        }
        var a = 1
        var b = 0
        data.forEach { byte ->
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        val checksum = (b shl 16) or a
        add(
            byteArrayOf(
                (checksum shr 24).toByte(),
                (checksum shr 16).toByte(),
                (checksum shr 8).toByte(),
                checksum.toByte(),
            ),
        )
        return out.toByteArray()
    }

    private fun bytes(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun text(value: String): ByteArray = value.encodeToByteArray()

    private fun shortBytes(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun ByteArray.readShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}
