package dev.hermeskotlin.ui.screen

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun screenImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    // createBitmap copies the colours, so the picture is a snapshot and the frame's own array is left alone.
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
