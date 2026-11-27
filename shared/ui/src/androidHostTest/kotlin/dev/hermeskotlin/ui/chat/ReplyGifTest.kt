package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.gif.GifDecoder
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyGifTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @Test
    fun replyGifAdvancesFramesInlineAndInTheViewer() {
        // A looping 80×48 GIF with a red first frame and a green second frame.
        val gif = Base64.decode("R0lGODlhUAAwAIEAAOAgIAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAPAAAACwAAAAAUAAwAAAIZgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmzhz6tzJs6fPn0CDCh1KtKjRo0iTKl3KtKnTp1CjSp1KtarVqwwDAgAh+QQBPAABACwAAAAAUAAwAIEgwCAAAAAAAAAAAAAIZgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmzhz6tzJs6fPn0CDCh1KtKjRo0iTKl3KtKnTp1CjSp1KtarVqwwDAgA7")
        showReply(gif)
        awaitSecondFrame("GIF preview")
        compose.onNodeWithContentDescription("GIF preview").performClick()
        awaitSecondFrame("GIF viewer")
    }

    @Test
    fun aGifRequestBuildsOnAndroid8() {
        // Coil turns down "the file's own loop count" below Android 9, and that threw while composing a GIF.
        // Coil's builder reads SDK_INT itself, so it's set here rather than running on an API 27 framework jar.
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 27)
        try {
            val request = gifRequest(RuntimeEnvironment.getApplication(), "GIF89a".encodeToByteArray(), maxEdge = 100)
            // Android 8's decoder, so the faked level took effect.
            assertTrue(request.decoderFactory is GifDecoder.Factory)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun gifPainterCanShowAnImageWithoutABitmapFallback() {
        // This header cannot make a BitmapFactory thumbnail. Stub a successful Coil decode
        // to exercise the view when the animation decoder can read more than the bitmap decoder.
        val header = "GIF89a".encodeToByteArray()
        val app = RuntimeEnvironment.getApplication()
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImage()
        val loader = ImageLoader.Builder(app)
            .components { add(Interceptor { SuccessResult(image, it.request) }) }
            .build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            showReply(header)
            compose.waitUntilExactlyOneExists(hasContentDescription("GIF preview"), 5_000)
            compose.onNodeWithContentDescription("GIF preview").performClick()
            compose.waitUntilExactlyOneExists(hasContentDescription("GIF viewer"), 5_000)
        } finally {
            SingletonImageLoader.reset()
            loader.shutdown()
        }
    }

    private fun showReply(gif: ByteArray) {
        compose.setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                var viewer by remember { mutableStateOf<ViewerImage?>(null) }
                CompositionLocalProvider(
                    LocalMediaLoader provides { gif },
                    LocalOpenImage provides { viewer = ViewerImage("GIF viewer", source = it.source) },
                ) {
                    ReplyMediaList(listOf(ReplyMedia("/fixture.gif", "GIF preview", isImage = true)))
                    viewer?.let { ImageViewer(it) { viewer = null } }
                }
            }
        }
    }

    @Test
    fun aGifWithoutALoopPlaysOnceAndStaysOnItsLastFrame() {
        // The same two frames (0.6 s each) without the NETSCAPE loop block: played once, as a browser does.
        val gif = Base64.decode("R0lGODlhUAAwAIEAAOAgIAAAAAAAAAAAACH5BAA8AAAALAAAAABQADAAAAhmAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMmypcuXMGPKnEmzps2bOHPq3Mmzp8+fQIMKHUq0qNGjSJMqXcq0qdOnUKNKnUq1qtWrDAMCACH5BAE8AAEALAAAAABQADAAgSDAIAAAAAAAAAAAAAhmAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMmypcuXMGPKnEmzps2bOHPq3Mmzp8+fQIMKHUq0qNGjSJMqXcq0qdOnUKNKnUq1qtWrDAMCADs=")
        showReply(gif)
        assertStaysOnLastFrame("GIF preview")
        compose.onNodeWithContentDescription("GIF preview").performClick()
        assertStaysOnLastFrame("GIF viewer")
    }

    private fun assertStaysOnLastFrame(description: String) {
        awaitSecondFrame(description)
        // A looping copy would be back on its red first frame within 0.6 s.
        val stop = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < stop) {
            assertTrue("$description went back to the first frame", isGreen(description))
            Thread.sleep(100)
        }
    }

    private fun awaitSecondFrame(description: String) {
        compose.waitUntilExactlyOneExists(hasContentDescription(description), 5_000)
        compose.waitUntil(5_000) { isGreen(description) }
    }

    private fun isGreen(description: String): Boolean {
        val pixels = compose.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        return center.green > .6f && center.red < .3f
    }
}
