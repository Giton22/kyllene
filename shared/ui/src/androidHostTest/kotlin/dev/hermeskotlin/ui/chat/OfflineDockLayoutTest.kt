package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineDockLayoutTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    @Test
    fun jumpAndDockNoticesStayAboveTheBannerAndThePlanAndDraftStayBelowIt() {
        val online = mutableStateOf(false)
        val actions = PreviewChatActions("Draft in progress")
        val state = ChatState(
            historyLoaded = true,
            messages = (1..40).map { ChatMessage.Assistant("reply-$it", text = "Reply $it", timestamp = it.toDouble()) },
            todos = TodoList(listOf(TodoItem("1", "Check layout", TodoStatus.Completed)), revision = 1),
        )
        var retries = 0
        compose.setContent {
            HermesTheme(ColorScheme.Dark) {
                ChatView(
                    title = "Conversation",
                    state = state,
                    picker = ModelPickerState(),
                    connected = online.value,
                    connectionLabel = "No connection · connecting again…",
                    linkStatus = if (online.value) null else "Reconnecting…",
                    onRetryConnection = { retries++ },
                    attachments = emptyList(),
                    attachmentError = null,
                    voiceChat = VoiceChatState(),
                    dictation = DictationState(),
                    suggestions = emptyList(),
                    sprite = null,
                    placeholder = "Message",
                    notice = "Sample dock notice",
                    actions = actions,
                    readUpTo = 1.0,
                    onOpenSidebar = {},
                    onNewChat = null,
                    onOpenMenu = null,
                    onOpenModels = {},
                    onAttach = {},
                    onDictate = {},
                    onVoiceChat = {},
                    onOpenPets = {},
                    onViewImage = {},
                    onNotice = {},
                )
            }
        }

        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(durationMillis = 550) }
        val jump = compose.onNodeWithContentDescription("Jump to latest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithText("Sample dock notice").fetchSemanticsNode().boundsInRoot
        val banner = compose.onNodeWithText("Can’t reach Hermes", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val plan = compose.onNodeWithText("Last plan 1/1").fetchSemanticsNode().boundsInRoot
        val draft = compose.onNodeWithText("Draft in progress").fetchSemanticsNode().boundsInRoot
        assertTrue(jump.bottom <= banner.top)
        assertTrue(notice.bottom <= banner.top)
        assertTrue(banner.bottom <= plan.top, "The disconnected banner should sit above the plan")
        assertTrue(plan.bottom <= draft.top)
        compose.onNodeWithText("No connection · connecting again…").assertDoesNotExist()
        compose.onNodeWithText("Retry now").performClick()
        compose.runOnIdle { assertEquals(1, retries); online.value = true }
        compose.onNodeWithText("Retry now").assertDoesNotExist()
        assertEquals("Draft in progress", actions.composer.text.toString())
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
                base.evaluate()
            }
        }
    }
}
