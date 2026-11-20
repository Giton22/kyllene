package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test

/** The jump-to-end pill says "New reply" for reply text below the reader, not for the agent thinking or using tools. */
@OptIn(ExperimentalTestApi::class)
class NewReplyPillTest {
    private val history = (1..40).flatMap {
        listOf(ChatMessage.User("u$it", "Question $it"), ChatMessage.Assistant("a$it", text = "Reply $it"))
    } + ChatMessage.User("u41", "One more question")

    @Test
    fun thinkingAndToolsBelowShowOnlyTheArrowAndReplyTextSaysNewReply() = runComposeUiTest {
        var messages by mutableStateOf(history)
        setContent { Conversation(messages) }
        waitForIdle()
        onNode(hasScrollToIndexAction()).performMouseInput { moveTo(center); scroll(-10000f) }
        waitForIdle()
        onNodeWithText("Reply 40").assertDoesNotExist()

        runOnIdle { messages = history + ChatMessage.Assistant("live-1", reasoning = "Reading the files", streaming = true) }
        waitForIdle()
        onNodeWithText("New reply").assertDoesNotExist()
        runOnIdle {
            messages = history + ChatMessage.Assistant(
                "live-1", reasoning = "Reading the files and more", streaming = true,
                tools = listOf(ToolActivity("t1", "terminal", running = true)),
            )
        }
        waitForIdle()
        onNodeWithText("New reply").assertDoesNotExist()

        runOnIdle {
            messages = history + ChatMessage.Assistant(
                "live-1", text = "Here is what changed", reasoning = "Reading the files and more", streaming = true,
                tools = listOf(ToolActivity("t1", "terminal")),
            )
        }
        waitForIdle()
        onNodeWithText("New reply").assertIsDisplayed()
    }

    @Test
    fun anOlderPageLoadingAboveShowsOnlyTheArrowAndAFailedTurnSaysNewReply() = runComposeUiTest {
        // The end of one long turn, as a page that holds no prompt shows it.
        val page = (1..40).map { ChatMessage.Assistant("row-${it + 10}", text = "Step $it") }
        var messages by mutableStateOf<List<ChatMessage>>(page)
        setContent { Conversation(messages) }
        waitForIdle()
        onNode(hasScrollToIndexAction()).performMouseInput { moveTo(center); scroll(-600f) }
        waitForIdle()
        onNodeWithText("Step 40").assertDoesNotExist()

        val older = listOf(ChatMessage.User("row-1", "Go through the steps"), ChatMessage.Assistant("row-2", text = "Starting"))
        runOnIdle { messages = older + page }
        waitForIdle()
        onNodeWithText("New reply").assertDoesNotExist()

        runOnIdle { messages = older + page + ChatMessage.Assistant("live-1", reasoning = "Trying the next step", error = "Model unavailable") }
        waitForIdle()
        onNodeWithText("New reply").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Conversation(messages: List<ChatMessage>) {
        hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
            CompositionLocalProvider(LocalDesktopLayout provides true) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ChatView(
                        title = "Conversation", state = ChatState(storedSessionId = "chat", historyLoaded = true, messages = messages),
                        picker = ModelPickerState(), connected = true,
                        attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(),
                        dictation = DictationState(), suggestions = emptyList(), sprite = null,
                        placeholder = "Message", notice = null, actions = PreviewChatActions(),
                        readUpTo = null, onReadLatest = null,
                        onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                        onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                    )
                }
            }
        }
    }
}
