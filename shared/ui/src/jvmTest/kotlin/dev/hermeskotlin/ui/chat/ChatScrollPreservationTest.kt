package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies [resolveInitialScroll] and composition teardown/remount recovery through [TranscriptViewport]
 * (matching what happens when `AppLockGate` or transient `historyLoaded = false` unmounts `Messages` on wake, #424).
 */
@OptIn(ExperimentalTestApi::class)
class ChatScrollPreservationTest {

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

    @Test
    fun resolveInitialScrollUsesUnpinnedViewportWithinBounds() {
        assertEquals(0 to 0, resolveInitialScroll(0, null))
        val unpinned = TranscriptViewport(
            conversationKey = "chat-1",
            firstVisibleItemIndex = 12,
            firstVisibleItemScrollOffset = 48,
            pinned = false,
            readerNavigated = true,
            unreadSettled = false,
            positioned = true,
            unreadFrom = "m2",
            unreadPagesLoaded = 1,
        )
        assertEquals(12 to 48, resolveInitialScroll(60, unpinned))
        assertEquals(9 to 0, resolveInitialScroll(10, unpinned.copy(firstVisibleItemIndex = 50, firstVisibleItemScrollOffset = -5)))

        val pinned = unpinned.copy(pinned = true)
        assertEquals(resolveInitialScroll(60, null), resolveInitialScroll(60, pinned))
        assertEquals(59, resolveInitialScroll(60, null).first)
    }

    private fun ComposeUiTest.isDisplayed(index: Int): Boolean =
        try {
            onNode(hasText("Message $index:", substring = true)).assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }

    private fun ComposeUiTest.findVisibleMiddleMessage(range: IntRange): Int? {
        val visible = range.filter { isDisplayed(it) }
        return visible.getOrNull(visible.size / 2)
    }

    @Test
    fun compositionTeardownMidChatRestoresSavedViewportWithoutReadUpTo() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var viewport: TranscriptViewport? = null
        val state = ChatState(messages = longMessages(), historyLoaded = true)

        setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.size(411.dp, 700.dp)) {
                    if (mounted) {
                        ChatView(
                            title = "Chat",
                            state = state,
                            conversationKey = "test-chat",
                            savedViewport = viewport,
                            onSaveViewport = { viewport = it },
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
                            readUpTo = null,
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
        }
        waitForIdle()

        onNode(hasText("Message 60:", substring = true)).assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeDown() } }
        waitForIdle()

        val anchor = findVisibleMiddleMessage(10..50)
        assertNotNull(anchor, "expected a mid-chat message to be displayed after scrolling up")
        onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        assertFalse(isDisplayed(60))
        assertFalse(isDisplayed(1))

        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()

        val afterRestore = (1..60).filter { isDisplayed(it) }
        assertTrue(anchor in afterRestore, "expected anchor=$anchor after remount, got displayed=$afterRestore")
        onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        assertFalse(isDisplayed(60))
        assertFalse(isDisplayed(1))
    }

    @Test
    fun compositionTeardownPostUnreadRestoresSavedViewportWithReadUpTo() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var viewport: TranscriptViewport? = null
        val state = ChatState(messages = longMessages(), historyLoaded = true)

        setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.size(411.dp, 700.dp)) {
                    if (mounted) {
                        ChatView(
                            title = "Chat",
                            state = state,
                            conversationKey = "test-chat",
                            savedViewport = viewport,
                            onSaveViewport = { viewport = it },
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
                            readUpTo = 10.0,
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
        }
        waitForIdle()

        onNodeWithText("New messages").assertIsDisplayed()
        onNode(hasText("Message 2:", substring = true)).assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeUp() } }
        waitForIdle()

        val anchor = findVisibleMiddleMessage(10..50)
        assertNotNull(anchor, "expected a mid-chat message to be displayed after scrolling down from unread start")
        onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        assertFalse(isDisplayed(2))
        assertFalse(isDisplayed(60))

        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()

        val afterRestore = (1..60).filter { isDisplayed(it) }
        assertTrue(anchor in afterRestore, "expected anchor=$anchor after remount, got displayed=$afterRestore")
        onNode(hasText("Message $anchor:", substring = true)).assertIsDisplayed()
        assertFalse(isDisplayed(2))
        assertFalse(isDisplayed(60))
    }
}
