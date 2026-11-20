package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
        val seen = listOf(reply).replyMark()
        assertEquals(seen, listOf(reply, ChatMessage.Notice("n1", "Context compressed")).replyMark())
        assertEquals(seen, listOf(ChatMessage.Assistant("a0", text = "Older reply"), reply).replyMark())
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
