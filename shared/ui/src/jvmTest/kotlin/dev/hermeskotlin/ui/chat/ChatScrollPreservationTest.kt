package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
 * The transcript taken down and put back (a spinner in its place a moment, or the screen rebuilt on waking the
 * phone) comes back where the reader was, through the [TranscriptViewport] the view model keeps (#424).
 */
@OptIn(ExperimentalTestApi::class)
class ChatScrollPreservationTest {

    private val messages = (1..60).map { i ->
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

    private fun viewport(firstKey: String?, pinned: Boolean = false) = TranscriptViewport(
        conversationKey = "chat-1",
        firstKey = firstKey,
        firstOffset = 48,
        pinned = pinned,
        readUpTo = null,
        savedCopy = false,
        readerNavigated = true,
        unreadSettled = false,
        positioned = true,
        unreadFrom = null,
        unreadPagesLoaded = 0,
    )

    @Test
    fun theListOpensWhereTheReaderWasByMessage() {
        assertEquals(ListPlace(12, 48, pinned = false), openingPlace(messages, viewport("m13")))
        // A page loaded above since keeps the same message on top.
        val older = listOf(ChatMessage.User("m0", "Message 0"))
        assertEquals(ListPlace(13, 48, pinned = false), openingPlace(older + messages, viewport("m13")))
    }

    @Test
    fun theListOpensAtTheEndWithNothingToComeBackTo() {
        val end = ListPlace(59, 1_000_000, pinned = true)
        assertEquals(end, openingPlace(messages, null))
        // Following the end, or the message gone (the chat started over from its newest page).
        assertEquals(end, openingPlace(messages, viewport("m13", pinned = true)))
        assertEquals(end, openingPlace(messages, viewport("gone")))
        assertEquals(ListPlace(0, 1_000_000, pinned = true), openingPlace(emptyList(), null))
    }

    @Composable
    private fun Chat(state: ChatState, readUpTo: Double?, saved: () -> TranscriptViewport?, onSave: (TranscriptViewport) -> Unit) {
        HermesTheme(ColorScheme.Light) {
            Box(Modifier.size(411.dp, 700.dp)) {
                ChatView(
                    title = "Chat",
                    state = state,
                    conversationKey = "test-chat",
                    savedViewport = saved,
                    onSaveViewport = onSave,
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

    private fun ComposeUiTest.isDisplayed(index: Int): Boolean =
        try {
            onNode(hasText("Message $index:", substring = true)).assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }

    private fun ComposeUiTest.shown() = (1..60).filter { isDisplayed(it) }

    private fun ComposeUiTest.middleOfScreen(): Int? = (10..50).filter { isDisplayed(it) }.let { it.getOrNull(it.size / 2) }

    @Test
    fun aListTakenDownMidChatComesBackThere() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var saved: TranscriptViewport? = null
        val state = ChatState(messages = messages, historyLoaded = true)
        setContent { if (mounted) Chat(state, readUpTo = null, saved = { saved }, onSave = { saved = it }) }

        onNode(hasText("Message 60:", substring = true)).assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeDown() } }
        waitForIdle()
        val anchor = assertNotNull(middleOfScreen(), "a mid-chat message should show after scrolling up")

        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()

        assertTrue(isDisplayed(anchor), "message $anchor should still show; shown: ${shown()}")
        assertFalse(isDisplayed(60))
        assertFalse(isDisplayed(1))
    }

    @Test
    fun aListTakenDownPastTheUnreadLineDoesntJumpBackToIt() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var saved: TranscriptViewport? = null
        val state = ChatState(messages = messages, historyLoaded = true)
        setContent { if (mounted) Chat(state, readUpTo = 10.0, saved = { saved }, onSave = { saved = it }) }

        onNodeWithText("New messages").assertIsDisplayed()
        onNode(hasText("Message 2:", substring = true)).assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeUp() } }
        waitForIdle()
        val anchor = assertNotNull(middleOfScreen(), "a mid-chat message should show after reading on")

        mounted = false
        waitForIdle()
        mounted = true
        waitForIdle()

        assertTrue(isDisplayed(anchor), "message $anchor should still show; shown: ${shown()}")
        assertFalse(isDisplayed(2))
        assertFalse(isDisplayed(60))
    }

    @Test
    fun aScreenRebuiltAfterAPageLoadedAboveComesBackToTheSameMessage() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var saved: TranscriptViewport? = null
        var state by mutableStateOf(ChatState(messages = messages.drop(20), historyLoaded = true))
        // Stands in for the screen's saved state, which a rebuilt screen gets back.
        setContent {
            val screen = rememberSaveableStateHolder()
            if (mounted) screen.SaveableStateProvider("chat") { Chat(state, readUpTo = null, saved = { saved }, onSave = { saved = it }) }
        }

        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeDown() } }
        waitForIdle()
        val anchor = assertNotNull(middleOfScreen(), "a mid-chat message should show after scrolling up")

        // Away, the transcript came back with 20 older rows above (a reload from the gateway).
        mounted = false
        waitForIdle()
        state = state.copy(messages = messages)
        mounted = true
        waitForIdle()

        assertTrue(isDisplayed(anchor), "message $anchor should still show; shown: ${shown()}")
    }

    @Test
    fun aListThatCantComeBackToItsMessageOpensAfresh() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var saved: TranscriptViewport? = null
        var state by mutableStateOf(ChatState(messages = messages, historyLoaded = true))
        setContent { if (mounted) Chat(state, readUpTo = 10.0, saved = { saved }, onSave = { saved = it }) }
        onNodeWithText("New messages").assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performTouchInput { repeat(2) { swipeUp() } }
        waitForIdle()
        assertNotNull(middleOfScreen(), "a mid-chat message should show after reading on")

        // Away, the chat started over from its newest rows, without the message the reader was on.
        mounted = false
        waitForIdle()
        state = state.copy(messages = messages.drop(40))
        mounted = true
        waitForIdle()

        // It opens at the "New messages" line, as a chat opened afresh does, not at its end.
        onNodeWithText("New messages").assertIsDisplayed()
        assertTrue(isDisplayed(41), "the first new message should show; shown: ${shown()}")
    }

    @Test
    fun whatIsKeptIsTheOpeningForTheCurrentSavedCopy() = runComposeUiTest {
        var mounted by mutableStateOf(true)
        var saved: TranscriptViewport? = null
        var state by mutableStateOf(ChatState(messages = messages, historyLoaded = true, savedCopyAt = 1L))
        setContent { if (mounted) Chat(state, readUpTo = 10.0, saved = { saved }, onSave = { saved = it }) }
        onNodeWithText("New messages").assertIsDisplayed()

        // The saved copy gives way to current history (here without the rows it had up to 30), which works the
        // opening out again: that one is kept, not the saved copy's.
        state = state.copy(messages = messages.drop(30), savedCopyAt = null)
        waitForIdle()
        mounted = false
        waitForIdle()

        val kept = assertNotNull(saved)
        assertFalse(kept.savedCopy)
        assertEquals(10.0, kept.readUpTo)
        assertEquals("m31", kept.unreadFrom)
        assertTrue(kept.positioned)
    }
}
