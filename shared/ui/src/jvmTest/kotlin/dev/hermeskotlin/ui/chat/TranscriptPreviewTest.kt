package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TranscriptPreviewTest {
    @Test
    fun shortReasoningExpandsInTheChat() = runComposeUiTest {
        setContent { TranscriptPreviewFixture(true, listOf(ChatMessage.Assistant("reply", reasoning = "Short reasoning output."))) }
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Short reasoning output.").assertIsDisplayed()
        onNodeWithContentDescription("Close").assertDoesNotExist()
    }

    @Test
    fun longReasoningOpensInTheDesktopRail() = runComposeUiTest {
        setContent { TranscriptPreviewFixture(true, listOf(ChatMessage.Assistant("reply", reasoning = longOutput))) }
        onNodeWithText("Reasoning").performClick()
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        onNodeWithText("Transcript preview fixture").assertIsDisplayed()
    }

    @Test
    fun longRoutineStaysCollapsedUntilTheDesktopPreviewOpens() = runComposeUiTest {
        val routine = ChatMessage.Event("routine", TranscriptEvent.Routine(null, longOutput, result = true))
        setContent { TranscriptPreviewFixture(true, listOf(routine)) }
        onNodeWithText("A routine ran").performClick()
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        onNodeWithText("A routine ran").assertIsDisplayed()
    }

    @Test
    fun manySystemMessagesOpenInTheDesktopRail() = runComposeUiTest {
        val events = List(18) { TranscriptEvent.Process("Process ${it + 1}", "", failed = false) }
        val system = ChatMessage.Event("system", TranscriptEvent.SystemMessages(events, "process-1"))
        setContent { TranscriptPreviewFixture(true, listOf(system)) }
        onNodeWithText("System messages", substring = true).performClick()
        onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        onNodeWithText("System messages", substring = true).assertIsDisplayed()
    }

    @Test
    fun theCutoffUsesMeasuredBodyHeightAndIncludesTheBoundary() {
        for ((bodyHeight, expectedPreview) in listOf(168.dp to false, 169.dp to true)) runComposeUiTest {
            var previewOpened = false
            setContent {
                HermesTheme(ColorScheme.Dark) {
                    CompositionLocalProvider(
                        LocalOpenTranscriptPreview provides { previewOpened = true },
                        LocalTranscriptInlineBudget provides transcriptInlineBudget(500.dp),
                    ) {
                        Box(Modifier.size(360.dp, 500.dp)) {
                            AdaptiveTranscriptDisclosure(icon = {}, label = "Measured details") {
                                Box(Modifier.size(50.dp, bodyHeight))
                            }
                        }
                    }
                }
            }
            onNodeWithText("Measured details").performClick()
            runOnIdle { kotlin.test.assertEquals(expectedPreview, previewOpened) }
        }
    }

    private val longOutput = (1..20).joinToString("\n\n") { "Detail $it. The report includes a comparison and a validation result." }
}

@Composable
private fun TranscriptPreviewFixture(desktop: Boolean, messages: List<ChatMessage>) {
    HermesTheme(ColorScheme.Dark) {
        CompositionLocalProvider(LocalDesktopLayout provides desktop) {
            Box(Modifier.size(if (desktop) 850.dp else 360.dp, 600.dp)) {
                ChatView(
                    title = "Transcript preview fixture",
                    state = ChatState(storedSessionId = "chat", historyLoaded = true, messages = messages),
                    picker = ModelPickerState(), connected = true, attachments = emptyList(), attachmentError = null,
                    voiceChat = VoiceChatState(), dictation = DictationState(), suggestions = emptyList(), sprite = null,
                    placeholder = "Message", notice = null, actions = PreviewChatActions(), readUpTo = null, onReadLatest = null,
                    onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                    onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                )
            }
        }
    }
}
