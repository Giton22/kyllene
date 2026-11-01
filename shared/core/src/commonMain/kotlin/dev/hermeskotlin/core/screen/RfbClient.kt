package dev.hermeskotlin.core.screen

/**
 * An RFB (VNC) client for the gateway's Bot Screen.
 *
 * The gateway streams the raw RFB bytes of a bot's desktop over a WebSocket, so this is an ordinary RFB
 * client with one difference: the gateway decides what the viewer may type, not this one. It drops the
 * keyboard, pointer and clipboard messages of a viewer that does not hold the screen, so the client sends
 * what the user does and lets the gateway answer.
 *
 * The pixel format is fixed here (32 bits, true colour, little-endian), which makes a plain rectangle a
 * run of bytes: blue, green, red, unused. Tight's own pixels are RGB triples instead.
 */
class RfbClient(private val transport: RfbTransport) {

    private val reader = RfbReader(transport)
    private val hextile = HextileDecoder()
    private val tight = TightDecoder()

    /** The desktop's pixels, as the gateway paints them. */
    val frame = FrameBuffer()

    /**
     * Opens the session: the RFB greeting, the security handshake, and the server's description of the
     * desktop. The screen the gateway runs asks for no password (it sits behind the gateway's own
     * authentication), so anything else is a screen this client cannot open.
     */
    suspend fun connect(): RfbServerInfo {
        val greeting = reader.readExactly(GREETING_LENGTH).decodeToString()
        if (!greeting.startsWith("RFB ")) throw RfbException("The gateway's screen did not answer with RFB.")
        transport.write(CLIENT_GREETING.toByteArray())

        val count = reader.readByte()
        if (count == 0) {
            val reason = reader.readExactly(4).decodeToString()
            throw RfbException("The gateway's screen refused the connection${if (reason.isBlank()) "" else ": $reason"}.")
        }
        val types = reader.readExactly(count)
        if (types.none { (it.toInt() and 0xFF) == SECURITY_NONE }) {
            throw RfbException("The gateway's screen asks for a password this app can't answer.")
        }
        transport.write(byteArrayOf(SECURITY_NONE.toByte()))

        if (reader.readExactly(4).readInt() != 0) {
            throw RfbException("The gateway's screen refused the connection.")
        }

        // ClientInit: one flag, asking to share the desktop with the other viewers.
        transport.write(byteArrayOf(1))

        val size = reader.readExactly(4)
        frame.resize(size.readShort(0), size.readShort(2))
        reader.readExactly(PIXEL_FORMAT_LENGTH)
        val name = reader.readExactly(reader.readExactly(4).readInt()).decodeToString()

        setPixelFormat()
        setEncodings()
        requestFrame(incremental = false)
        return RfbServerInfo(frame.width, frame.height, name)
    }

    /** Asks for the next update. An incremental one asks only for what changed. */
    suspend fun requestFrame(incremental: Boolean = true) {
        transport.write(
            byteArrayOf(MSG_FRAMEBUFFER_UPDATE_REQUEST, if (incremental) 1 else 0) +
                byteArrayOf(0, 0) + // x
                byteArrayOf(0, 0) + // y
                frame.width.toShortBytes() + frame.height.toShortBytes(),
        )
    }

    /**
     * Reads the next update and paints it. Returns null when the stream ended. Messages the gateway sends
     * for other reasons (a palette change, the bell, text it cut) are read past.
     */
    suspend fun nextFrame(): RfbFrame? {
        while (true) {
            val type = reader.readByte()
            when (type) {
                MSG_FRAMEBUFFER_UPDATE -> {
                    reader.readExactly(1) // padding
                    val rectangles = reader.readExactly(2).readShort(0)
                    repeat(rectangles) { readRectangle() }
                    frame.painted()
                    return RfbFrame(frame.width, frame.height, frame.pixels, frame.version)
                }
                MSG_SET_COLOUR_MAP -> {
                    reader.readExactly(3)
                    val colours = reader.readExactly(2).readShort(0)
                    reader.readExactly(colours * 6)
                }
                MSG_BELL -> Unit
                MSG_SERVER_CUT_TEXT -> {
                    reader.readExactly(3)
                    reader.readExactly(reader.readExactly(4).readInt())
                }
                else -> throw RfbException("The gateway's screen sent a message this app doesn't read ($type).")
            }
        }
    }

    /** Presses or releases a key. [keysym] is an X11 key symbol, the way VNC names keys. */
    suspend fun keyEvent(keysym: Int, down: Boolean) {
        transport.write(byteArrayOf(MSG_KEY_EVENT, if (down) 1 else 0, 0, 0) + keysym.toIntBytes())
    }

    /** Moves the pointer and clicks. [mask] is the button bits: 1 left, 2 middle, 4 right. */
    suspend fun pointerEvent(x: Int, y: Int, mask: Int) {
        transport.write(byteArrayOf(MSG_POINTER_EVENT, mask.toByte()) + x.toShortBytes() + y.toShortBytes())
    }

    /** Sends text to the bot's clipboard. */
    suspend fun clientCutText(text: String) {
        val bytes = text.encodeToByteArray()
        transport.write(byteArrayOf(MSG_CLIENT_CUT_TEXT, 0, 0, 0) + bytes.size.toIntBytes() + bytes)
    }

    private suspend fun readRectangle() {
        val header = reader.readExactly(8)
        val x = header.readShort(0)
        val y = header.readShort(2)
        val rectWidth = header.readShort(4)
        val rectHeight = header.readShort(6)
        val encoding = reader.readExactly(4).readInt()

        when (encoding) {
            ENCODING_RAW -> {
                val bytes = reader.readExactly(rectWidth * rectHeight * 4)
                paintRaw(x, y, rectWidth, rectHeight, bytes)
            }
            ENCODING_COPY_RECT -> {
                val source = reader.readExactly(4)
                frame.copy(x, y, rectWidth, rectHeight, source.readShort(0), source.readShort(2))
            }
            ENCODING_HEXTILE -> hextile.decode(reader, x, y, rectWidth, rectHeight, frame)
            ENCODING_TIGHT -> tight.decode(reader, x, y, rectWidth, rectHeight, frame)
            ENCODING_DESKTOP_SIZE -> frame.resize(rectWidth, rectHeight)
            else -> throw RfbException("The gateway's screen sent an encoding this app doesn't read ($encoding).")
        }
    }

    private fun paintRaw(x: Int, y: Int, rectWidth: Int, rectHeight: Int, bytes: ByteArray) {
        var source = 0
        for (row in 0 until rectHeight) {
            for (column in 0 until rectWidth) {
                frame.set(x + column, y + row, argb(bytes[source], bytes[source + 1], bytes[source + 2]))
                source += 4
            }
        }
    }

    private suspend fun setPixelFormat() {
        val format = ByteArray(PIXEL_FORMAT_LENGTH)
        format[0] = 32 // bits per pixel
        format[1] = 24 // depth
        format[2] = 0 // little-endian
        format[3] = 1 // true colour
        format.writeShort(4, 255) // red max
        format.writeShort(6, 255) // green max
        format.writeShort(8, 255) // blue max
        format[10] = 16 // red shift
        format[11] = 8 // green shift
        format[12] = 0 // blue shift
        transport.write(byteArrayOf(MSG_SET_PIXEL_FORMAT, 0, 0, 0) + format)
    }

    private suspend fun setEncodings() {
        val encodings = listOf(
            ENCODING_RAW,
            ENCODING_COPY_RECT,
            ENCODING_HEXTILE,
            ENCODING_TIGHT,
            ENCODING_DESKTOP_SIZE,
        )
        val message = ByteArray(4 + encodings.size * 4)
        message[0] = MSG_SET_ENCODINGS
        message[2] = (encodings.size shr 8).toByte()
        message[3] = encodings.size.toByte()
        encodings.forEachIndexed { index, encoding ->
            val offset = 4 + index * 4
            message[offset] = (encoding shr 24).toByte()
            message[offset + 1] = (encoding shr 16).toByte()
            message[offset + 2] = (encoding shr 8).toByte()
            message[offset + 3] = encoding.toByte()
        }
        transport.write(message)
    }

    companion object {
        private const val GREETING_LENGTH = 12
        private const val PIXEL_FORMAT_LENGTH = 16
        private const val CLIENT_GREETING = "RFB 003.008\n"
        private const val SECURITY_NONE = 1

        private const val MSG_SET_PIXEL_FORMAT = 0.toByte()
        private const val MSG_SET_ENCODINGS = 2.toByte()
        private const val MSG_FRAMEBUFFER_UPDATE_REQUEST = 3.toByte()
        private const val MSG_KEY_EVENT = 4.toByte()
        private const val MSG_POINTER_EVENT = 5.toByte()
        private const val MSG_CLIENT_CUT_TEXT = 6.toByte()

        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_SET_COLOUR_MAP = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_HEXTILE = 5
        const val ENCODING_TIGHT = 7
        const val ENCODING_DESKTOP_SIZE = 223
    }
}

/** Reads the RFB stream a message at a time. */
internal class RfbReader(private val transport: RfbTransport) {

    private var buffer = ByteArray(0)
    private var position = 0

    suspend fun readByte(): Int = readExactly(1)[0].toInt() and 0xFF

    suspend fun readExactly(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        while (available() < count) {
            val chunk = transport.read() ?: throw RfbException("The gateway's screen closed the stream.")
            if (chunk.isEmpty()) continue
            append(chunk)
        }
        val out = buffer.copyOfRange(position, position + count)
        position += count
        compact()
        return out
    }

    /** A colour in the client's pixel format: blue, green, red, unused. */
    suspend fun readColour(): Int {
        val bytes = readExactly(4)
        return argb(bytes[0], bytes[1], bytes[2])
    }

    /** A colour in Tight's own form: red, green, blue. */
    suspend fun readColourRgb(): Int {
        val bytes = readExactly(3)
        return rgb(bytes[0], bytes[1], bytes[2])
    }

    /** The gateway's compact length: one to three bytes, seven bits each, the high bit meaning "more". */
    suspend fun readCompactLength(): Int {
        val first = readByte()
        var length = first and 0x7F
        if (first and 0x80 == 0) return length
        val second = readByte()
        length = length or ((second and 0x7F) shl 7)
        if (second and 0x80 == 0) return length
        return length or (readByte() shl 14)
    }

    private fun available(): Int = buffer.size - position

    private fun append(chunk: ByteArray) {
        val rest = if (position > 0) buffer.copyOfRange(position, buffer.size) else buffer
        buffer = rest + chunk
        position = 0
    }

    private fun compact() {
        if (position == buffer.size) {
            buffer = ByteArray(0)
            position = 0
        } else if (position > READ_AHEAD) {
            buffer = buffer.copyOfRange(position, buffer.size)
            position = 0
        }
    }

    private companion object {
        /** Bytes kept after what was read, so a message that follows in the same chunk costs nothing. */
        const val READ_AHEAD = 64 * 1024
    }
}

/** A colour from the gateway's screen in the client's pixel format, where the bytes are blue, green, red. */
internal fun argb(blue: Byte, green: Byte, red: Byte): Int =
    0xFF000000.toInt() or
        ((red.toInt() and 0xFF) shl 16) or
        ((green.toInt() and 0xFF) shl 8) or
        (blue.toInt() and 0xFF)

/** A colour from the gateway's screen in Tight's own form, where the bytes are red, green, blue. */
internal fun rgb(red: Byte, green: Byte, blue: Byte): Int =
    0xFF000000.toInt() or
        ((red.toInt() and 0xFF) shl 16) or
        ((green.toInt() and 0xFF) shl 8) or
        (blue.toInt() and 0xFF)

internal fun ByteArray.readShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.writeShort(offset: Int, value: Int) {
    this[offset] = (value shr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.readInt(offset: Int = 0): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun Int.toIntBytes(): ByteArray = byteArrayOf(
    (this shr 24).toByte(),
    (this shr 16).toByte(),
    (this shr 8).toByte(),
    this.toByte(),
)

internal fun Int.toShortBytes(): ByteArray = byteArrayOf((this shr 8).toByte(), this.toByte())
