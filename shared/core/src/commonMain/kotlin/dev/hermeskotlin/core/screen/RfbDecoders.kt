package dev.hermeskotlin.core.screen

/**
 * The two encodings the gateway's screen sends that need more than a plain run of pixels: Hextile, which
 * paints a rectangle in 16×16 tiles, and Tight, which compresses a rectangle and may use a palette.
 *
 * Both read from the same [RfbReader] the client uses, and paint into the same [FrameBuffer].
 */
internal class HextileDecoder {

    private var background = 0
    private var foreground = 0

    suspend fun decode(
        reader: RfbReader,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        frame: FrameBuffer,
    ) {
        val tilesX = (rectWidth + TILE - 1) / TILE
        val tilesY = (rectHeight + TILE - 1) / TILE

        for (tileY in 0 until tilesY) {
            for (tileX in 0 until tilesX) {
                val subencoding = reader.readByte()
                if (subencoding > MAX_SUBENCODING) {
                    throw RfbException("The gateway's screen sent a tile this app can't read ($subencoding).")
                }
                val left = x + tileX * TILE
                val top = y + tileY * TILE
                val tileWidth = minOf(TILE, x + rectWidth - left)
                val tileHeight = minOf(TILE, y + rectHeight - top)

                if (subencoding and SUB_RAW != 0) {
                    val bytes = reader.readExactly(tileWidth * tileHeight * 4)
                    paintRaw(frame, left, top, tileWidth, tileHeight, bytes)
                    continue
                }

                if (subencoding and SUB_BACKGROUND != 0) background = reader.readColour()
                if (subencoding and SUB_FOREGROUND != 0) foreground = reader.readColour()

                frame.fill(left, top, tileWidth, tileHeight, background)

                if (subencoding and SUB_SUBRECTS != 0) {
                    val count = reader.readByte()
                    repeat(count) {
                        val colour = if (subencoding and SUB_COLOURED != 0) reader.readColour() else foreground
                        val position = reader.readByte()
                        val size = reader.readByte()
                        val subX = (position shr 4) and 0x0F
                        val subY = position and 0x0F
                        val subWidth = ((size shr 4) and 0x0F) + 1
                        val subHeight = (size and 0x0F) + 1
                        frame.fill(left + subX, top + subY, subWidth, subHeight, colour)
                    }
                }
            }
        }
    }

    private fun paintRaw(frame: FrameBuffer, x: Int, y: Int, rectWidth: Int, rectHeight: Int, bytes: ByteArray) {
        var source = 0
        for (row in 0 until rectHeight) {
            for (column in 0 until rectWidth) {
                frame.set(x + column, y + row, argb(bytes[source], bytes[source + 1], bytes[source + 2]))
                source += 4
            }
        }
    }

    private companion object {
        const val TILE = 16
        const val MAX_SUBENCODING = 0x1F
        const val SUB_RAW = 0x01
        const val SUB_BACKGROUND = 0x02
        const val SUB_FOREGROUND = 0x04
        const val SUB_SUBRECTS = 0x08
        const val SUB_COLOURED = 0x10
    }
}

/**
 * The gateway's screen's compressed encoding. A rectangle starts with a control byte: the low four bits
 * say which of the four zlib streams to start again, the high four say what the rectangle is (a fill, or
 * pixels in one of three filters). The pixels themselves are RGB triples, not the client's pixel format.
 */
internal class TightDecoder {

    private val streams = List(4) { ZlibInflater() }
    private var palette = IntArray(0)

    suspend fun decode(
        reader: RfbReader,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        frame: FrameBuffer,
    ) {
        val control = reader.readByte()
        val resets = control and 0x0F
        for (index in 0 until 4) {
            if (resets and (1 shl index) != 0) streams[index].reset()
        }

        when (val kind = (control shr 4) and 0x0F) {
            KIND_FILL -> {
                val colour = reader.readColourRgb()
                frame.fill(x, y, rectWidth, rectHeight, colour)
            }
            KIND_JPEG, KIND_PNG -> throw RfbException("The gateway's screen sent a picture this app can't draw.")
            else -> {
                val filter = if (kind and FILTER_PRESENT != 0) reader.readByte() else FILTER_COPY
                val stream = kind and STREAM_MASK
                when (filter) {
                    FILTER_COPY -> {
                        val data = readPixels(reader, stream, rectWidth * rectHeight * 3)
                        paintRgb(frame, x, y, rectWidth, rectHeight, data)
                    }
                    FILTER_PALETTE -> decodePalette(reader, stream, x, y, rectWidth, rectHeight, frame)
                    FILTER_GRADIENT -> {
                        val data = readPixels(reader, stream, rectWidth * rectHeight * 3)
                        paintGradient(frame, x, y, rectWidth, rectHeight, data)
                    }
                    else -> throw RfbException("The gateway's screen sent a filter this app can't read ($filter).")
                }
            }
        }
    }

    private suspend fun decodePalette(
        reader: RfbReader,
        stream: Int,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        frame: FrameBuffer,
    ) {
        val colours = reader.readByte() + 1
        val entries = IntArray(colours)
        for (index in 0 until colours) {
            val colour = reader.readColourRgb()
            entries[index] = colour
        }
        palette = entries

        val bits = if (colours <= 2) 1 else 8
        val rowSize = (rectWidth * bits + 7) / 8
        val data = readPixels(reader, stream, rowSize * rectHeight)

        if (colours == 2) {
            paintMono(frame, x, y, rectWidth, rectHeight, data, rowSize)
        } else {
            paintIndexed(frame, x, y, rectWidth, rectHeight, data)
        }
    }

    private fun paintMono(
        frame: FrameBuffer,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        data: ByteArray,
        rowSize: Int,
    ) {
        val first = palette.getOrElse(0) { 0 }
        val second = palette.getOrElse(1) { 0 }
        for (row in 0 until rectHeight) {
            for (column in 0 until rectWidth) {
                val byte = data[row * rowSize + column / 8].toInt() and 0xFF
                val bit = (byte shr (7 - column % 8)) and 1
                frame.set(x + column, y + row, if (bit == 1) second else first)
            }
        }
    }

    private fun paintIndexed(
        frame: FrameBuffer,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        data: ByteArray,
    ) {
        var source = 0
        for (row in 0 until rectHeight) {
            for (column in 0 until rectWidth) {
                val index = data[source++].toInt() and 0xFF
                frame.set(x + column, y + row, palette.getOrElse(index) { 0 })
            }
        }
    }

    private fun paintRgb(
        frame: FrameBuffer,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        data: ByteArray,
    ) {
        var source = 0
        for (row in 0 until rectHeight) {
            for (column in 0 until rectWidth) {
                frame.set(x + column, y + row, rgb(data[source], data[source + 1], data[source + 2]))
                source += 3
            }
        }
    }

    /** Tight's gradient filter: each byte is a difference from the pixel predicted by its neighbours. */
    private fun paintGradient(
        frame: FrameBuffer,
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        data: ByteArray,
    ) {
        var source = 0
        val previous = IntArray(rectWidth)
        for (row in 0 until rectHeight) {
            val left = IntArray(3)
            val upperLeft = IntArray(3)
            for (column in 0 until rectWidth) {
                val upper = if (row == 0) {
                    intArrayOf(0, 0, 0)
                } else {
                    val above = previous[column]
                    intArrayOf((above shr 16) and 0xFF, (above shr 8) and 0xFF, above and 0xFF)
                }
                val predicted = IntArray(3)
                for (channel in 0 until 3) {
                    val guess = if (row == 0) {
                        left[channel]
                    } else {
                        left[channel] + upper[channel] - upperLeft[channel]
                    }.coerceIn(0, 255)
                    val value = (data[source++].toInt() and 0xFF) + guess
                    predicted[channel] = value.coerceIn(0, 255)
                }
                upperLeft[0] = upper[0]
                upperLeft[1] = upper[1]
                upperLeft[2] = upper[2]
                left[0] = predicted[0]
                left[1] = predicted[1]
                left[2] = predicted[2]
                val colour = 0xFF000000.toInt() or
                    (predicted[0] shl 16) or (predicted[1] shl 8) or predicted[2]
                previous[column] = colour
                frame.set(x + column, y + row, colour)
            }
        }
    }

    /**
     * The rectangle's pixels. Small ones travel as they are; bigger ones are zlib-compressed, with their
     * length written in the gateway's compact form.
     */
    private suspend fun readPixels(reader: RfbReader, stream: Int, expected: Int): ByteArray {
        if (expected == 0) return ByteArray(0)
        if (expected < UNCOMPRESSED_LIMIT) return reader.readExactly(expected)
        val length = reader.readCompactLength()
        val data = reader.readExactly(length)
        return streams[stream].inflate(data, expected)
    }

    private companion object {
        const val KIND_FILL = 0x08
        const val KIND_JPEG = 0x09
        const val KIND_PNG = 0x0A
        const val FILTER_PRESENT = 0x04
        const val STREAM_MASK = 0x03
        const val FILTER_COPY = 0
        const val FILTER_PALETTE = 1
        const val FILTER_GRADIENT = 2

        /** Below this many bytes the gateway sends the pixels as they are. */
        const val UNCOMPRESSED_LIMIT = 12
    }
}
