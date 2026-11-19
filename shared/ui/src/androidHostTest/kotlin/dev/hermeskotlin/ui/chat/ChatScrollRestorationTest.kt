package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Waking the phone can rebuild the screen while the chat stays open, as turning it does. The transcript stays where
 * the reader scrolled rather than following the end again or jumping back to the "New messages" line (#424). The
 * view model, which outlives the rebuild, keeps where that was; [saved] stands in for it here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScrollRestorationTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private fun longMessages(): List<ChatMessage> = (1..60).map { i ->
        if (i % 2 == 1) {
            ChatMessage.User("m$i", "Message $i: prompt line to fill vertical space cleanly", timestamp = i * 10.0)
        } else {
            ChatMessage.Assistant(
                "m$i",
                "Message $i: reply line.\n\nSecond paragraph of message $i to ensure multi-screen height.",
                timestamp = i * 10.0,
            )
        }
    }

    private var saved: TranscriptViewport? = null

    private fun showRestorableChat(restoration: StateRestorationTester, readUpTo: Double?) {
        val state = ChatState(messages = longMessages(), historyLoaded = true)
        restoration.setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.fillMaxSize()) {
                    ChatView(
                        title = "Chat",
                        state = state,
                        conversationKey = "test-chat",
                        savedViewport = { saved },
                        onSaveViewport = { saved = it },
                        picker = ModelPickerState(),
                        connected = true,
                        attachments = emptyList(),
                        attachmentError = null,
                        voiceChat = VoiceChatState(),
                        dictation = DictationState(),
                        suggestions = emptyList(),
                        sprite = null,
                        placeholder = "Message",
                        notice = null,
                        actions = PreviewChatActions(),
                        readUpTo = readUpTo,
                        onOpenSidebar = {},
                        onNewChat = {},
                        onOpenMenu = {},
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
        }
        composeRule.waitForIdle()
    }

    private fun isDisplayed(index: Int): Boolean =
        try {
            composeRule.onNode(hasText("Message $index:", substring = true)).assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }

    private fun findVisibleMiddleMessage(range: IntRange): Int? {
        val visible = range.filter(::isDisplayed)
        return visible.getOrNull(visible.size / 2)
    }

    @Test
    fun midChatPositionSurvivesStateRestorationWithoutReadUpTo() {
        val restoration = StateRestorationTester(composeRule)
        showRestorableChat(restoration, readUpTo = null)

        composeRule.onNode(hasText("Message 60:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeDown() } }
        composeRule.waitForIdle()

        val anchor = findVisibleMiddleMessage(10..50)
        assertNotNull(anchor, "expected a mid-chat message to be displayed after scrolling up")
        composeRule.onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("Message 60:", substring = true)).assertIsNotDisplayed()
        composeRule.onNode(hasText("Message 1:", substring = true)).assertIsNotDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        val afterRestore = (1..60).filter(::isDisplayed)
        assertTrue(anchor in afterRestore, "expected anchor=$anchor after restore, got displayed=$afterRestore")
        composeRule.onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("Message 60:", substring = true)).assertIsNotDisplayed()
        composeRule.onNode(hasText("Message 1:", substring = true)).assertIsNotDisplayed()
    }

    @Test
    fun postUnreadPositionSurvivesStateRestorationWithReadUpTo() {
        val restoration = StateRestorationTester(composeRule)
        showRestorableChat(restoration, readUpTo = 10.0)

        composeRule.onNodeWithText("New messages").assertIsDisplayed()
        composeRule.onNode(hasText("Message 2:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeUp() } }
        composeRule.waitForIdle()

        val anchor = findVisibleMiddleMessage(10..50)
        assertNotNull(anchor, "expected a mid-chat message to be displayed after scrolling past unread start")
        composeRule.onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("Message 2:", substring = true)).assertIsNotDisplayed()
        composeRule.onNode(hasText("Message 1:", substring = true)).assertIsNotDisplayed()
        composeRule.onNode(hasText("Message 60:", substring = true)).assertIsNotDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        val afterRestore = (1..60).filter(::isDisplayed)
        assertTrue(anchor in afterRestore, "expected anchor=$anchor after restore, got displayed=$afterRestore")
        composeRule.onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("Message 2:", substring = true)).assertIsNotDisplayed()
        composeRule.onNode(hasText("Message 60:", substring = true)).assertIsNotDisplayed()
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
