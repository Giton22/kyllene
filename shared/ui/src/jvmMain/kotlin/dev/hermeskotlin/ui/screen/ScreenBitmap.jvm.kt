package dev.hermeskotlin.ui.screen

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual fun screenImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    // Skia reads raw pixels as BGRA, so the frame's ARGB is put in that order.
    val bytes = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        val argb = pixels[i]
        bytes[i * 4] = (argb and 0xFF).toByte()
        bytes[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((argb shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()
    }
    val bitmap = Bitmap()
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    if (!bitmap.installPixels(info, bytes, width * 4)) {
        bitmap.close()
        return null
    }
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
