package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
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

        awaitSecondFrame("GIF preview")
        compose.onNodeWithContentDescription("GIF preview").performClick()
        awaitSecondFrame("GIF viewer")
    }

    private fun awaitSecondFrame(description: String) {
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithContentDescription(description).captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.green > .6f && center.red < .3f
        }
    }
}
