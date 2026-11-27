package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TooltipHoverTest {
    @Test
    fun tooltipStaysAboveTheButtonWhenThereIsNoRoomBelow() = runSkikoComposeUiTest(size = Size(360f, 240f), testTimeout = 10.seconds) {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                Box(Modifier.fillMaxSize().padding(bottom = 6.dp).testTag("window"), contentAlignment = Alignment.BottomCenter) {
                    Tooltip("Add photos or files") {
                        Box(Modifier.size(40.dp).testTag("button"))
                    }
                }
            }
        }

        val button = onNodeWithTag("button")
        val label = onNodeWithText("Add photos or files")
        val buttonBounds = button.fetchSemanticsNode().boundsInWindow
        mainClock.autoAdvance = false
        button.performMouseInput { moveTo(Offset(center.x, bottom - 2)) }
        mainClock.advanceTimeBy(600)
        label.assertIsDisplayed()
        val labelBounds = label.fetchSemanticsNode().boundsInWindow
        assertTrue(labelBounds.bottom < buttonBounds.top, "The tooltip must clear the hovered button")

        mainClock.advanceTimeBy(500)
        label.assertIsDisplayed()
        onNodeWithTag("window").performMouseInput { moveTo(topLeft) }
        mainClock.advanceTimeBy(64)
        label.assertDoesNotExist()
    }

    @Test
    fun aLabelTooTallForEitherSideStillLeavesTheButtonClear() = runSkikoComposeUiTest(size = Size(360f, 240f), testTimeout = 10.seconds) {
        val long = List(80) { "word" }.joinToString(" ")
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                Box(Modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
                    Tooltip(long) {
                        Box(Modifier.size(40.dp).testTag("button"))
                    }
                }
            }
        }

        val button = onNodeWithTag("button")
        val buttonBounds = button.fetchSemanticsNode().boundsInWindow
        mainClock.autoAdvance = false
        button.performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(600)
        val labelBounds = onNodeWithText(long).fetchSemanticsNode().boundsInWindow
        assertTrue(labelBounds.height > buttonBounds.top, "The label must be too tall for the room above")
        assertTrue(labelBounds.top >= buttonBounds.bottom || labelBounds.bottom <= buttonBounds.top, "The tooltip must not cover the hovered button")

        mainClock.advanceTimeBy(500)
        onNodeWithText(long).assertExists()
    }
}
