package dev.hermeskotlin.ui.chat

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.decode.DecodeUtils
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.gif.isGif
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import okio.Buffer

@Composable
internal actual fun rememberGifPainter(bytes: ByteArray, maxEdge: Int): Painter? {
    val isGif = remember(bytes) {
        DecodeUtils.isGif(Buffer().write(bytes, offset = 0, byteCount = minOf(6, bytes.size)))
    }
    if (!isGif) return null

    return key(bytes, maxEdge) {
        val context = LocalContext.current
        val request = remember(context, bytes, maxEdge) {
            ImageRequest.Builder(context)
                .data(bytes)
                .size(maxEdge)
                .precision(Precision.INEXACT)
                // The preview and viewer need their own drawable and playback lifecycle.
                .memoryCachePolicy(CachePolicy.DISABLED)
                .decoderFactory(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
                .build()
        }
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        painter.takeIf { state is AsyncImagePainter.State.Success }
    }
}
