package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.LocalAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplyMoreTest {
    @Test
    fun desktopReplyKeepsCopyMoreAndUsageOnOneRowAndRoutesTheMenuActions() = runComposeUiTest {
        val reading = mutableStateOf(false)
        val paused = mutableStateOf(false)
        var branched = false
        var regenerated = false
        var spoken: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(
                    LocalDesktopLayout provides true,
                    LocalAppSettings provides AppSettings(showUsage = true),
                ) {
                    Box(Modifier.size(420.dp, 480.dp).padding(20.dp)) {
                        AssistantReply(
                            message = ChatMessage.Assistant(
                                key = "reply", text = "Ready to review.", usage = TurnUsage(640, 321, 0, 1),
                            ),
                            onBranch = { branched = true },
                            onRegenerate = { regenerated = true },
                            last = true,
                            reading = reading.value,
                            readingPaused = paused.value,
                            onReadAloud = { spoken = it; reading.value = true },
                            onStopReading = { reading.value = false },
                            onPauseReading = { paused.value = true },
                            onResumeReading = { paused.value = false },
                        )
                    }
                }
            }
        }

        val copy = onNodeWithContentDescription("Copy").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = onNodeWithContentDescription("More").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val usage = onNodeWithText("640 ↓ · 321 ↑").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(usage.right <= copy.left && copy.right <= more.left && more.right >= 390f * density.density)
        assertTrue(kotlin.math.abs(copy.center.y - more.center.y) < 1f)
        onNodeWithContentDescription("Regenerate").assertDoesNotExist()
        onNodeWithContentDescription("Branch from here").assertDoesNotExist()

        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Branch from here").performClick()
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Regenerate").performClick()
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Read aloud").performClick()
        runOnIdle {
            assertTrue(branched && regenerated)
            assertEquals("Ready to review.", spoken)
        }
        onNodeWithContentDescription("Resume reading").assertDoesNotExist()
        onNodeWithContentDescription("Pause reading").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(reading.value && paused.value) }
        onNodeWithContentDescription("Pause reading").assertDoesNotExist()
        onNodeWithContentDescription("Resume reading").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(reading.value && !paused.value) }
        onNodeWithContentDescription("Stop reading").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(false, reading.value) }
    }

    @Test
    fun olderReplyDoesNotExposeItsUnrevealedActions() = runComposeUiTest {
        val latest = mutableStateOf(false)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    AssistantReply(
                        message = ChatMessage.Assistant(key = "reply", text = "An earlier reply."),
                        onBranch = {},
                        onRegenerate = {},
                        last = latest.value,
                    )
                }
            }
        }

        onNodeWithContentDescription("Copy").assertDoesNotExist()
        onNodeWithContentDescription("More").assertDoesNotExist()
        onNodeWithText("Copy").assertDoesNotExist()
        onNodeWithText("More").assertDoesNotExist()
        runOnIdle { latest.value = true }
        onNodeWithContentDescription("Copy").assertHasClickAction()
        onNodeWithContentDescription("More").assertHasClickAction()
    }
}
