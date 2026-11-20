package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReplyMarkTest {
    private val prompt = ChatMessage.User("u1", "What changed?")

    @Test
    fun thinkingAndToolsAreNotANewReply() {
        val seen = listOf(prompt).replyMark()
        val working = listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Looking at the log", streaming = true))
        val withTool = listOf(
            prompt,
            ChatMessage.Assistant("live-1", reasoning = "Looking at the log", tools = listOf(ToolActivity("t1", "terminal", running = true)), streaming = true),
        )
        assertEquals(seen, working.replyMark())
        assertEquals(seen, withTool.replyMark())
    }

    @Test
    fun replyTextIsANewReply() {
        val seen = listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", streaming = true)).replyMark()
        val writing = listOf(prompt, ChatMessage.Assistant("live-1", text = "Two files", reasoning = "Thinking", streaming = true))
        assertNotEquals(seen, writing.replyMark())
    }

    @Test
    fun theStoredRowTakingOverTheLiveOneIsNotNews() {
        val live = listOf(prompt, ChatMessage.Assistant("live-1", text = "Two files", streaming = true))
        val stored = listOf(ChatMessage.User("stored-7", "What changed?"), ChatMessage.Assistant("stored-8", text = "Two files"))
        assertEquals(live.replyMark(), stored.replyMark())
    }

    @Test
    fun aReplySplitIntoSeveralStoredRowsIsNotNews() {
        val live = listOf(prompt, ChatMessage.Assistant("live-1", text = "Checking.Done.", streaming = true))
        val stored = listOf(
            prompt,
            ChatMessage.Assistant("s1", text = "Checking.", tools = listOf(ToolActivity("t1", "terminal"))),
            ChatMessage.Assistant("s2", text = "Done."),
        )
        assertEquals(live.replyMark(), stored.replyMark())
    }

    @Test
    fun eventsNoticesAndOlderPagesAreNotANewReply() {
        val reply = ChatMessage.Assistant("a1", text = "Done")
        val seen = listOf(prompt, reply).replyMark()
        assertEquals(seen, listOf(prompt, reply, ChatMessage.Notice("n1", "Context compressed")).replyMark())
        assertEquals(seen, listOf(prompt, reply, ChatMessage.Event("e1", TranscriptEvent.Label("Yesterday"))).replyMark())
        assertEquals(seen, listOf(ChatMessage.User("u0", "Earlier"), ChatMessage.Assistant("a0", text = "Older reply"), prompt, reply).replyMark())
    }

    @Test
    fun aTurnThatFailsOrWarnsIsNews() {
        val seen = listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", streaming = true)).replyMark()
        assertNotEquals(seen, listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", error = "Model unavailable")).replyMark())
        assertNotEquals(seen, listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", warning = "Not saved")).replyMark())
        assertNotEquals(seen, listOf(prompt, ChatMessage.Event("row-9", TranscriptEvent.FailedTurn("Model unavailable"))).replyMark())
    }

    @Test
    fun withoutAPromptAReplySplitIntoSeveralStoredRowsIsNotNews() {
        val live = listOf(ChatMessage.Assistant("live-1", text = "Checking.Done.", streaming = true))
        val stored = listOf(ChatMessage.Assistant("s1", text = "Checking."), ChatMessage.Notice("n1", "Subagent done", stored = true), ChatMessage.Assistant("s2", text = "Done."))
        assertEquals(live.replyMark(), stored.replyMark())
    }

    @Test
    fun anOlderPageLoadingAboveIsNotNewsButReplyTextAfterItIs() {
        // One long turn: the page loaded holds its end only, and the page before brings its prompt and start.
        val end = listOf(ChatMessage.Assistant("row-60", text = "Done."))
        val whole = listOf(prompt, ChatMessage.Assistant("row-2", text = "Checking.\n\nDone."))
        val away = ReplySeen(end.first().key, end.replyMark()).next(away = true, end.first().key, end.replyMark())
        val paged = away.next(away = true, whole.first().key, whole.replyMark())
        assertFalse(paged.new)
        val more = whole + ChatMessage.Assistant("live-1", text = "And", streaming = true)
        assertTrue(paged.next(away = true, more.first().key, more.replyMark()).new)
    }

    @Test
    fun newStaysNewUntilTheReaderIsBackAtTheEnd() {
        val seen = listOf(prompt, ChatMessage.Assistant("a1", text = "Done."))
        // The same prompt again, answered alike: the mark ends up where it was.
        val again = seen + ChatMessage.User("u2", prompt.text)
        var state = ReplySeen("u1", seen.replyMark()).next(away = true, "u1", seen.replyMark())
        assertFalse(state.new)
        state = state.next(away = true, "u1", again.replyMark())
        state = state.next(away = true, "u1", (again + ChatMessage.Assistant("a2", text = "Done.")).replyMark())
        assertEquals(seen.replyMark(), state.mark)
        assertTrue(state.new)
        // An older page meanwhile doesn't take it back.
        assertTrue(state.next(away = true, "u0", state.mark).new)
        assertFalse(state.next(away = false, "u0", state.mark).new)
    }

    @Test
    fun aNewPromptOrCommandOutputIsNews() {
        val seen = listOf(prompt, ChatMessage.Assistant("a1", text = "Done")).replyMark()
        assertNotEquals(seen, listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), ChatMessage.User("u2", "And now?")).replyMark())
        val command = ChatMessage.Command("c1", "/status", running = true)
        val ran = listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), command)
        assertNotEquals(ran.replyMark(), listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), command.copy(output = "ok", running = false)).replyMark())
    }
}
