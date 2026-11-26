package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
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

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun copyIncludesSavedProgressAndWorksWithoutAnAnswer() = runComposeUiTest {
        var copied: String? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                copied = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
            }
        }
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    Column {
                        AssistantReply(ChatMessage.Assistant("a1", text = "Done.", commentary = listOf("Checking.")), null, null, true)
                        AssistantReply(ChatMessage.Assistant("a2", commentary = listOf("Checking again.")), null, null, true)
                    }
                }
            }
        }
        onAllNodes(hasContentDescription("Copy"))[0].performClick()
        runOnIdle { assertEquals("Checking.\n\nDone.", copied) }
        onAllNodes(hasContentDescription("Copy") or hasContentDescription("Copied"))[1].performClick()
        runOnIdle { assertEquals("Checking again.", copied) }
        onNodeWithContentDescription("More").assertDoesNotExist()
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
        // Faded out, not gone: the row keeps its height, so the reply doesn't grow when the pointer passes.
        val hiddenHeight = onRoot().fetchSemanticsNode().boundsInRoot.height
        runOnIdle { latest.value = true }
        assertEquals(hiddenHeight, onRoot().fetchSemanticsNode().boundsInRoot.height)
        onNodeWithContentDescription("Copy").assertHasClickAction()
        onNodeWithContentDescription("More").assertHasClickAction()
    }

    @Test
    fun anOlderReplyKeepsItsChosenReactionShownAndItsActionsInReachOfTheKeyboard() = runComposeUiTest {
        val focus = FocusRequester()
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Column {
                        Box(Modifier.size(10.dp).focusRequester(focus).focusable())
                        AssistantReply(
                            message = ChatMessage.Assistant(key = "reply", text = "An earlier reply.", reactionRowId = 5, userReaction = "👍"),
                            onBranch = {},
                            onRegenerate = {},
                            last = false,
                            onReact = {},
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription("Got it").assertIsDisplayed()
        onNodeWithContentDescription("More").assertDoesNotExist()
        // Faded out, it takes no taps: a touchscreen doesn't hover to show it first.
        onNodeWithContentDescription("More", useUnmergedTree = true).performClick()
        onNodeWithText("Branch from here").assertDoesNotExist()
        // Tabbing on from the element above reaches the reply's row, which then shows.
        runOnIdle { focus.requestFocus() }
        repeat(6) {
            if (onAllNodes(hasContentDescription("Copy") and isFocused()).fetchSemanticsNodes().isEmpty()) {
                onRoot().performKeyInput { pressKey(Key.Tab) }
            }
        }
        onNodeWithContentDescription("Copy").assertIsFocused()
        onNodeWithContentDescription("More").assertHasClickAction()
    }

    @Test
    fun theMenuOpensAwayFromTheNearerEdgeOfTheWindow() = runComposeUiTest {
        val atTop = mutableStateOf(true)
        setContent {
            HermesTheme(ColorScheme.Light) {
                Column(Modifier.fillMaxSize()) {
                    if (!atTop.value) Spacer(Modifier.weight(1f))
                    AssistantReply(
                        message = ChatMessage.Assistant(key = "reply", text = "Done.", reactionRowId = 3),
                        onBranch = {},
                        onRegenerate = {},
                        last = true,
                        onReact = {},
                    )
                }
            }
        }

        // Near the top it opens downwards: pushed up past the top, its first rows would sit under the status bar.
        onNodeWithContentDescription("More").performClick()
        var more = onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
        assertTrue(onNodeWithText("Got it").fetchSemanticsNode().boundsInRoot.top >= more.bottom)
        onNodeWithText("Got it").performClick()

        // Low on the screen, as replies usually are, it opens upwards, over the reply.
        runOnIdle { atTop.value = false }
        onNodeWithContentDescription("More").performClick()
        more = onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
        assertTrue(onNodeWithText("Regenerate").fetchSemanticsNode().boundsInRoot.bottom <= more.top)
    }
}
