package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop's reactions, in the reply's own row of icons after Branch. The phone's are in ReplyReactionsPhoneTest. */
@OptIn(ExperimentalTestApi::class)
class ReplyReactionsTest {
    private val labels = listOf("Got it", "Continue", "Verify", "Explain")

    @Test
    fun eachReactionAndDesktopsOwnSetShowAsIcons() {
        for (reaction in ReplyReaction.entries) assertTrue(reactionIcon(reaction.emoji) != null, "$reaction has no icon")
        for (emoji in listOf("❤️", "👍", "👎", "😂", "‼️", "❓")) assertTrue(reactionIcon(emoji) != null, "$emoji has no icon")
        assertEquals(null, reactionIcon("🎉"))
    }

    @Test
    fun newestReplyShowsTheFourReactionsInItsRowAndTogglesOne() = runComposeUiTest {
        val selected = mutableStateOf<ReplyReaction?>(null)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    AssistantReply(
                        message = ChatMessage.Assistant(
                            key = "reply", text = "Ready to review.", reactionRowId = 42, userReaction = selected.value?.emoji,
                        ),
                        onBranch = {},
                        onRegenerate = {},
                        last = true,
                        onReact = { selected.value = it.takeUnless { selected.value == it } },
                    )
                }
            }
        }

        onNodeWithContentDescription("More").assertDoesNotExist()
        val copy = onNodeWithContentDescription("Copy").fetchSemanticsNode().boundsInRoot
        val branch = onNodeWithContentDescription("Branch from here").fetchSemanticsNode().boundsInRoot
        for (label in labels) {
            val bounds = onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction().assertIsNotSelected()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(copy.center.y - bounds.center.y) < 1f, "$label is in the reply's row")
            assertTrue(branch.right <= bounds.left, "$label follows the reply's icons")
        }

        onNodeWithContentDescription("Continue").performClick()
        runOnIdle { assertEquals(ReplyReaction.Continue, selected.value) }
        onNodeWithContentDescription("Continue").assertIsSelected()
        onNodeWithContentDescription("Verify").assertIsNotSelected()
        onNodeWithContentDescription("Continue").performClick()
        runOnIdle { assertEquals(null, selected.value) }
    }

    @Test
    fun anOlderReplysChosenReactionStaysShownWhenItsIconsFade() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    AssistantReply(
                        message = ChatMessage.Assistant(key = "reply", text = "Earlier.", reactionRowId = 7, userReaction = ReplyReaction.Explain.emoji),
                        onBranch = {},
                        onRegenerate = {},
                        last = false,
                        onReact = {},
                    )
                }
            }
        }
        onNodeWithContentDescription("Explain").assertIsDisplayed().assertIsSelected()
        // The faded ones take no taps and aren't read out.
        for (label in labels - "Explain") onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun aReactionFromAnotherClientsSetShowsBesideTheFour() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    AssistantReply(
                        message = ChatMessage.Assistant(key = "reply", text = "Ready.", reactionRowId = 3, userReaction = "❤️"),
                        onBranch = {},
                        onRegenerate = {},
                        last = true,
                        onReact = {},
                    )
                }
            }
        }
        onNodeWithContentDescription("❤️").assertIsDisplayed().assertIsSelected()
        for (label in labels) onNodeWithContentDescription(label).assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun offlineTheChosenReactionShowsWithoutTheOthers() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    AssistantReply(
                        message = ChatMessage.Assistant(key = "reply", text = "Ready.", reactionRowId = 3, userReaction = ReplyReaction.Verify.emoji),
                        onBranch = {},
                        onRegenerate = {},
                        last = true,
                        onReact = null,
                    )
                }
            }
        }
        onNodeWithContentDescription("Verify").assertIsDisplayed().assertIsSelected()
        for (label in labels - "Verify") onNodeWithContentDescription(label).assertDoesNotExist()
    }
}
