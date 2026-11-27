package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppScreensTest {
    @Test
    fun systemTitleBarStillZoomsTheContent() = checkZoom(null, Triple(0, 0, 0))

    @Test
    fun customTitleBarControlsKeepTheirPixelInsets() =
        checkZoom(TitleBar(24.dp, 72.dp, 40.dp, Modifier), Triple(40, 100, 50))

    private fun checkZoom(titleBar: TitleBar?, expectedInsets: Triple<Int, Int, Int>) =
        runDesktopComposeUiTest(width = 500, height = 400) {
            var zoom by mutableStateOf(1f)
            var insets: Triple<Int, Int, Int>? = null
            var fontScale: Float? = null
            setContent {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(LocalDensity provides Density(1.25f, fontScale = 1.3f)) {
                        AppScreens(titleBar, zoom) {
                            val controls = LocalWindowControls.current
                            val density = LocalDensity.current
                            SideEffect {
                                insets = Triple(controls.start, controls.end, controls.height)
                                fontScale = density.fontScale
                            }
                            Box(Modifier.size(200.dp, 80.dp).testTag("content"))
                        }
                    }
                }
            }
            for ((scale, width) in listOf(1f to 250f, 1.1f to 275f, 0.9f to 225f, 1f to 250f)) {
                runOnIdle { zoom = scale }
                assertEquals(width, onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.width, "Content at zoom $scale")
                runOnIdle {
                    assertEquals(expectedInsets, insets, "Window control insets at zoom $scale")
                    assertEquals(1.3f, fontScale, "Font scale at zoom $scale")
                }
            }
        }
}
