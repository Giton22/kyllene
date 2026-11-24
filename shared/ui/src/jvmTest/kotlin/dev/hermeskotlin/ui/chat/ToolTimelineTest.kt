package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.DesktopBack
import dev.hermeskotlin.ui.LocalDesktopBack
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolTimelineTest {
    private val tools = listOf(
        ToolActivity("read", "read_file", startedAt = 50.0, detail = "Read the backup log", output = "All archives found"),
        ToolActivity(
            "check", "terminal", startedAt = 50.2, detail = "Check backup snapshots",
            input = "backupctl snapshots --latest", output = "Snapshot listing timed out", failed = true, durationSeconds = 2.0,
        ),
    )

    private fun opensDetails(name: String) = hasText(name) and SemanticsMatcher("opens tool details") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Tool details"
    }

    private fun expandsCall(name: String) = hasText(name) and SemanticsMatcher("expands an individual call") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show details"
    }

    @Test
    fun tappingACallOpensItWithFullInputAndOutput() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Tools(tools, "backup-reply")
                }
            }
        }

        onNodeWithText("Check backup snapshots").assertDoesNotExist()
        onNodeWithText("Ran 2 tools").performClick()
        onNodeWithText("+0.2s").assertExists()
        onNodeWithText("Read the backup log").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completion unconfirmed"))
        onNodeWithText("Check backup snapshots").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed"))
        onNodeWithText("2.0s").assertExists()
        onNodeWithText("View tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).performClick()
        onNodeWithText("Tool details").assertExists()
        onNodeWithText("Error").assertExists()
        onNodeWithText("backupctl snapshots --latest").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
        onNode(expandsCall("Read the backup log")).performClick()
        onNodeWithText("All archives found").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
        onNodeWithText("View full output").assertDoesNotExist()

        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).assertExists()
    }

    @Test
    fun wideDesktopKeepsTheReplyVisibleBesideExpandedTools() = runDesktopComposeUiTest(width = 1200, height = 750) {
        val back = DesktopBack()
        val state = ChatState(historyLoaded = true, messages = listOf(
            ChatMessage.Assistant("backup-reply", text = "The backup can be retried.", tools = tools),
        ))
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalDesktopBack provides back) {
                    ChatView(
                        title = "Backups", state = state, picker = ModelPickerState(), connected = true,
                        attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(),
                        dictation = DictationState(), suggestions = emptyList(), sprite = null,
                        placeholder = "Message", notice = null, actions = PreviewChatActions(),
                        onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                        onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                    )
                }
            }
        }

        onNodeWithText("Ran 2 tools").performClick()
        onNode(opensDetails("Check backup snapshots")).performClick()
        val reply = onNodeWithText("The backup can be retried.").assertIsDisplayed().getUnclippedBoundsInRoot()
        val details = onNodeWithText("Tool details").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(details.left > reply.right, "Tool details should be to the right of the reply")
        onNodeWithText("backupctl snapshots --latest").assertIsDisplayed()
        onNode(expandsCall("Read the backup log")).performClick()
        onNodeWithText("All archives found").assertIsDisplayed()
        onNodeWithText("Snapshot listing timed out").assertIsDisplayed()
        runOnIdle { assertTrue(back.back()) }
        onNodeWithText("Tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).assertIsDisplayed()
    }
}
