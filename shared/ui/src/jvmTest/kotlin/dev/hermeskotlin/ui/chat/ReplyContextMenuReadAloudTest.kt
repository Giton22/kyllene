package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** A desktop's right-click on a reply offers Read aloud only when the reply has words to say, as its More menu does. */
@OptIn(ExperimentalTestApi::class)
class ReplyContextMenuReadAloudTest {

    private fun replyWithNothingToSay(text: String) = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.width(420.dp).testTag("reply")) {
                        AssistantReply(
                            message = ChatMessage.Assistant(key = "reply", text = text),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReadAloud = {},
                        )
                    }
                }
            }
        }

        // The More menu already leaves Read aloud out for a reply with nothing to say.
        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Regenerate").assertExists()
        onNodeWithText("Read aloud").assertDoesNotExist()
        onNodeWithText("Regenerate").performClick()
        waitForIdle()

        // The right-click menu should agree.
        onNodeWithTag("reply").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Branch from here").assertExists() // the right-click menu is open
        onNodeWithText("Read aloud").assertDoesNotExist()
    }

    @Test
    fun aReplyOfOnlyCodeOffersNoReadAloudOnRightClick() = replyWithNothingToSay("```\n$ ./gradlew check\n```")

    @Test
    fun aReplyOfOnlyEmojiOffersNoReadAloudOnRightClick() = replyWithNothingToSay("👍 🎉")

    @Test
    fun aReplyWithWordsOffersReadAloudOnRightClick() = runComposeUiTest {
        var read: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.width(420.dp).testTag("reply")) {
                        AssistantReply(
                            message = ChatMessage.Assistant(key = "reply", text = "The build passes."),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReadAloud = { read = it },
                        )
                    }
                }
            }
        }

        onNodeWithTag("reply").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Read aloud").performClick()
        waitForIdle()
        assertEquals("The build passes.", read)
    }
}
