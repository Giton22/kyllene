package dev.hermeskotlin.ui.chat

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
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReplyReactionMenuTest {
    @Test
    fun storedReplyOffersReactionsAndShowsTheSelectedEmoji() = runComposeUiTest {
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
        onNodeWithText("Got it").performClick()
        runOnIdle { assertEquals(ReplyReaction.Acknowledge, selected.value) }
        onNodeWithText("👍").assertIsDisplayed()

        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Got it").assertIsSelected()
        onNodeWithText("Continue").assertIsNotSelected()
        onNodeWithText("Got it").performClick()
        runOnIdle { assertEquals(null, selected.value) }
        onNodeWithText("👍").assertDoesNotExist()
    }
}
