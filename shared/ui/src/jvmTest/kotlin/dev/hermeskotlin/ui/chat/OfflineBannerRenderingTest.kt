package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.PureBlack
import kotlin.test.Test
import kotlin.test.assertEquals

/** The floating offline notice must hide the transcript beneath it in every theme. */
@OptIn(ExperimentalTestApi::class)
class OfflineBannerRenderingTest {
    @Test fun lightBannerIsOpaque() = bannerIsOpaque(ColorScheme.Light)
    @Test fun darkBannerIsOpaque() = bannerIsOpaque(ColorScheme.Dark)
    @Test fun pureBlackBannerIsOpaque() = bannerIsOpaque(PureBlack)

    private fun bannerIsOpaque(scheme: ColorScheme) = runComposeUiTest {
        setContent {
            HermesTheme(scheme) {
                Column {
                    for ((tag, backdrop) in listOf("black" to Color.Black, "white" to Color.White)) {
                        Box(Modifier.width(360.dp).background(backdrop).testTag(tag)) {
                            OfflineBanner("Test gateway", "Waiting for network…", onRetry = {})
                        }
                    }
                }
            }
        }
        val black = onNodeWithTag("black").captureToImage().toPixelMap()
        val white = onNodeWithTag("white").captureToImage().toPixelMap()
        // Sample the flat fill inside the rounded corners, clear of the icon, labels and retry button.
        val x = black.width / 2
        val y = 3
        assertEquals(black[x, y], white[x, y], "The transcript backdrop must not affect the banner fill")
    }
}
