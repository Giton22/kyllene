package dev.hermeskotlin.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** An answer that didn't go out leaves its request open: the panel's buttons take the next try, however often it fails. */
@OptIn(ExperimentalTestApi::class)
class InputRequestPanelRetryTest {

    private val request = InputRequest.Approval("srq-1", "rm -rf build", "Deletes the build folder", "terminal", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))

    @Test
    fun everyFailedAnswerLeavesTheButtonsUsable() = runComposeUiTest {
        var answers = 0
        setContent {
            HermesTheme(ColorScheme.Light) {
                // As a failed RPC: nothing the panel is shown changes, only the answer's own result says so.
                InputRequestPanel(listOf(request), connected = true, onAnswer = { _, _, onDone -> answers++; onDone(false) })
            }
        }
        mainClock.advanceTimeBy(1_000) // past the delay before a new approval takes taps
        waitForIdle()

        repeat(3) { attempt ->
            onNodeWithText("Deny").performClick()
            waitForIdle()
            assertEquals(attempt + 1, answers, "the try after ${attempt} failed answers didn't go out")
        }
    }

    @Test
    fun anAnswerOnItsWayIsNotSentTwice() = runComposeUiTest {
        var answers = 0
        var done: ((Boolean) -> Unit)? = null
        setContent {
            HermesTheme(ColorScheme.Light) {
                InputRequestPanel(listOf(request), connected = true, onAnswer = { _, _, onDone -> answers++; done = onDone })
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onNodeWithText("Deny").performClick()
        waitForIdle()
        onNodeWithText("Deny").performClick()
        waitForIdle()
        assertEquals(1, answers)

        // It failed after all: the next tap is a new try.
        runOnUiThread { done?.invoke(false) }
        waitForIdle()
        onNodeWithText("Deny").performClick()
        waitForIdle()
        assertEquals(2, answers)
    }
}
