package dev.hermeskotlin.ui.chat

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DecodeUtils
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.gif.isGif
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import coil3.size.Precision
import okio.Buffer

@Composable
internal actual fun rememberGifPicture(bytes: ByteArray, maxEdge: Int): AttachmentPicture? {
    val isGif = remember(bytes) {
        DecodeUtils.isGif(Buffer().write(bytes, offset = 0, byteCount = minOf(6, bytes.size)))
    }
    if (!isGif) return null

    return key(bytes, maxEdge) {
        val context = LocalContext.current
        val request = remember(context, bytes, maxEdge) { gifRequest(context, bytes, maxEdge) }
        // Coil treats animated drawables as non-shareable. Each composed view owns playback,
        // so returning to a disposed preview starts it from the beginning.
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        when (state) {
            is AsyncImagePainter.State.Success -> AttachmentPicture(painter, decoding = false)
            is AsyncImagePainter.State.Error -> null
            else -> AttachmentPicture(null, decoding = true)
        }
    }
}

internal fun gifRequest(context: Context, bytes: ByteArray, maxEdge: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(bytes)
        .size(maxEdge)
        .precision(Precision.INEXACT)
        .apply {
            if (Build.VERSION.SDK_INT >= 28) {
                decoderFactory(AnimatedImageDecoder.Factory())
                // Coil loops forever by default; a GIF made to play once (no loop block) stops on its last frame.
                repeatCount(AnimatedImageDecoder.ENCODED_LOOP_COUNT)
            } else {
                // Android 8's decoder (Movie) can't read the loop count, and Coil turns the request down there,
                // so every GIF loops.
                decoderFactory(GifDecoder.Factory())
            }
        }
        .build()
