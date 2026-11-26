package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplyReactionMenuTest {
    @Test
    fun storedReplyOffersReactionsUnderMoreAndShowsTheChosenOneAsAnIcon() = runComposeUiTest {
        val selected = mutableStateOf<ReplyReaction?>(null)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                AssistantReply(
                    message = ChatMessage.Assistant(
                        key = "reply", text = "Ready to review.", reactionRowId = 42,
                        userReaction = selected.value?.emoji,
                    ),
                    onBranch = null,
                    onRegenerate = null,
                    last = true,
                    onReact = { selected.value = it.takeUnless { selected.value == it } },
                )
            }
        }

        onNodeWithContentDescription("More").performClick()
        for (label in listOf("Got it", "Continue", "Verify", "Explain")) {
            onNodeWithText(label).assertIsDisplayed()
        }
        // Line icons, not the emoji the gateway stores.
        onNodeWithText("👍", substring = true).assertDoesNotExist()
        onNodeWithText("Got it").performClick()
        runOnIdle { assertEquals(ReplyReaction.Acknowledge, selected.value) }
        // Beside Copy, read out by its name, still without the emoji.
        onNodeWithContentDescription("Got it").assertIsDisplayed()
        onNodeWithText("👍", substring = true).assertDoesNotExist()

        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Got it").assertIsSelected()
        onNodeWithText("Continue").assertIsNotSelected()
        onNodeWithText("Got it").performClick()
        runOnIdle { assertEquals(null, selected.value) }
        onNodeWithContentDescription("Got it").assertDoesNotExist()
    }

    @Test
    fun aStoppedReplyWithAStoredRowOffersTheReactions() {
        for (desktop in listOf(false, true)) runComposeUiTest {
            var chosen: ReplyReaction? = null
            setContent {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(LocalDesktopLayout provides desktop) {
                        AssistantReply(
                            message = ChatMessage.Assistant(
                                key = "row-4", text = "Working on it", outcome = TurnOutcome.Interrupted, reactionRowId = 4,
                            ),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReact = { chosen = it },
                        )
                    }
                }
            }

            onNodeWithText("Stopped").assertIsDisplayed()
            onNodeWithContentDescription("More").performClick()
            for (label in listOf("Got it", "Continue", "Verify", "Explain")) onNodeWithText(label).assertIsDisplayed()
            onNodeWithText("Continue").performClick()
            runOnIdle { assertEquals(ReplyReaction.Continue, chosen, "desktop=$desktop") }
        }
    }

    @Test
    fun eachReactionAndDesktopsOwnSetShowAsIcons() {
        for (reaction in ReplyReaction.entries) assertEquals(reaction.icon, reactionIcon(reaction.emoji))
        for (emoji in listOf("❤️", "👍", "👎", "😂", "‼️", "❓")) assertTrue(reactionIcon(emoji) != null, "$emoji has no icon")
        assertEquals(null, reactionIcon("🎉"))
    }

    @Test
    fun aReactionFromAnotherClientsSetShowsBesideCopy() = runComposeUiTest {
        val emoji = mutableStateOf("❤️")
        setContent {
            HermesTheme(ColorScheme.Light) {
                AssistantReply(
                    message = ChatMessage.Assistant(key = "reply", text = "Done.", reactionRowId = 7, userReaction = emoji.value),
                    onBranch = null,
                    onRegenerate = null,
                    last = true,
                    onReact = {},
                )
            }
        }

        // Its icon, read out by its emoji as there's no name for it here.
        onNodeWithContentDescription("❤️").assertIsDisplayed()
        onNodeWithText("❤️").assertDoesNotExist()
        // One with no icon to match shows as its emoji.
        runOnIdle { emoji.value = "🎉" }
        onNodeWithContentDescription("🎉").assertIsDisplayed()
        onNodeWithContentDescription("❤️").assertDoesNotExist()
        onNodeWithContentDescription("More").performClick()
        for (label in listOf("Got it", "Continue", "Verify", "Explain")) onNodeWithText(label).assertIsNotSelected()
    }

    @Test
    fun noReactionsAreOfferedWithoutAStoredRowOrWhileTheyCantBeSent() = runComposeUiTest {
        val rowId = mutableStateOf<Long?>(null)
        val canReact = mutableStateOf(true)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                AssistantReply(
                    message = ChatMessage.Assistant(key = "reply", text = "Done.", reactionRowId = rowId.value, userReaction = "🔍"),
                    onBranch = {},
                    onRegenerate = null,
                    last = true,
                    onReact = if (canReact.value) ({}) else null,
                )
            }
        }

        // Not saved yet: no row to react on. The menu keeps the reply's other commands.
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Branch from here").assertIsDisplayed()
        for (label in listOf("Got it", "Continue", "Verify", "Explain")) onNodeWithText(label).assertDoesNotExist()
        onNodeWithText("Branch from here").performClick()

        // Offline: none offered, but the chosen one still shows beside Copy, as chosen.
        runOnIdle { rowId.value = 9; canReact.value = false }
        onNodeWithContentDescription("Verify").assertIsDisplayed().assertIsSelected()
        onNodeWithContentDescription("More").performClick()
        for (label in listOf("Got it", "Continue", "Verify", "Explain")) onNodeWithText(label).assertDoesNotExist()
    }
}
