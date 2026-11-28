package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.chat.TurnOutcome
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
    fun savedCommentaryIsNewsOnceAcrossTheLiveToSavedTransition() {
        val waiting = listOf(prompt).replyMark()
        val live = listOf(prompt, ChatMessage.Assistant("live-1", text = "Checking.\n\nMore detail.", streaming = true)).replyMark()
        val saved = listOf(
            prompt,
            ChatMessage.Assistant("row-2", commentary = listOf("Checking.\r\n\r\nMore detail.")),
        ).replyMark()
        val seen = ReplySeen(prompt.key, waiting)

        assertTrue(seen.next(away = true, prompt.key, saved).new)
        assertFalse(ReplySeen(prompt.key, live).next(away = true, prompt.key, saved).new)
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
        assertNotEquals(seen, listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", outcome = TurnOutcome.Error)).replyMark())
        assertNotEquals(seen, listOf(prompt, ChatMessage.Event("row-9", TranscriptEvent.FailedTurn(""))).replyMark())
    }

    @Test
    fun aFailedTurnsStoredRowTakingOverIsNotNews() {
        val live = listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", outcome = TurnOutcome.Error, error = "Model unavailable"))
        val stored = listOf(prompt, ChatMessage.Event("row-9", TranscriptEvent.FailedTurn("The model could not be reached")))
        assertEquals(live.replyMark(), stored.replyMark())
    }

    @Test
    fun blankSpaceInAReplyIsNotCounted() {
        // The stream runs a reply's steps together; the stored rows are joined with blank lines.
        val live = listOf(ChatMessage.Assistant("live-1", text = "Checking.Done.\n", streaming = true))
        val stored = listOf(ChatMessage.Assistant("row-4", text = "Checking.\n\nDone."))
        assertEquals(live.replyMark(), stored.replyMark())
        val thinking = listOf(prompt, ChatMessage.Assistant("live-1", reasoning = "Thinking", streaming = true))
        assertEquals(thinking.replyMark(), listOf(prompt, ChatMessage.Assistant("live-1", text = "\n", reasoning = "Thinking", streaming = true)).replyMark())
    }

    @Test
    fun anOlderPageLoadingAboveIsNotNewsButReplyTextAfterItIs() {
        // One long turn: the page loaded holds its end only, and the page before brings its prompt and start.
        val end = listOf(ChatMessage.Assistant("row-60", text = "Done."))
        val whole = listOf(ChatMessage.User("row-1", "What changed?"), ChatMessage.Assistant("row-2", text = "Checking.\n\nDone."))
        val away = ReplySeen(end.first().key, end.replyMark()).next(away = true, end.first().key, end.replyMark())
        val paged = away.next(away = true, whole.first().key, whole.replyMark())
        assertFalse(paged.new)
        val more = whole + ChatMessage.Assistant("live-1", text = "And", streaming = true)
        assertTrue(paged.next(away = true, more.first().key, more.replyMark()).new)
    }

    @Test
    fun aReplyArrivingWithAnOlderPageIsNewsWhenThePartAlreadyLoadedChanged() {
        // The part loaded holds no prompt, so the page above, which brings it, changes the mark.
        val loaded = listOf(ChatMessage.Assistant("row-11", text = "Checking."))
        val away = ReplySeen("row-11", loaded.replyMark()).next(away = true, "row-11", loaded.replyMark())
        val older = listOf(ChatMessage.User("row-10", "What changed?"))
        // The page above only: the part already loaded is as it was.
        val paged = older + loaded
        assertNotEquals(away.mark, paged.replyMark())
        assertFalse(away.next(away = true, "row-10", paged.replyMark(), paged.replyArrivedWithPage(loaded, away.mark)).new)
        // The page above and more of the reply in the same update.
        val replied = paged + ChatMessage.Assistant("live-1", text = "Two files changed.", streaming = true)
        assertTrue(away.next(away = true, "row-10", replied.replyMark(), replied.replyArrivedWithPage(loaded, away.mark)).new)
    }

    @Test
    fun aReplyArrivingWithAnOlderPageThatFoldsTheOldestBubbleIsNews() {
        // History can fold the first loaded bubble into an older row, so its key is gone once the page is in.
        val loaded = listOf(ChatMessage.Assistant("row-60", text = "Done."))
        val away = ReplySeen("row-60", loaded.replyMark()).next(away = true, "row-60", loaded.replyMark())
        val whole = listOf(ChatMessage.User("row-1", "What changed?"), ChatMessage.Assistant("row-2", text = "Checking.\n\nDone."))
        assertFalse(away.next(away = true, "row-1", whole.replyMark(), whole.replyArrivedWithPage(loaded, away.mark)).new)
        val replied = whole + ChatMessage.Assistant("live-1", text = "And", streaming = true)
        assertTrue(away.next(away = true, "row-1", replied.replyMark(), replied.replyArrivedWithPage(loaded, away.mark)).new)
    }

    @Test
    fun aReplyThatComesAsTheChatsOwnPromptIsStoredIsNews() {
        // A chat begun here keeps its local prompt until the transcript is read again, e.g. on reconnecting.
        val live = listOf(ChatMessage.User("local-0", "What changed?"), ChatMessage.Assistant("live-1", reasoning = "Thinking", streaming = true))
        val reread = listOf(ChatMessage.User("row-1", "What changed?"), ChatMessage.Assistant("row-2", text = "Two files"))
        val away = ReplySeen("local-0", live.replyMark()).next(away = true, "local-0", live.replyMark())
        assertTrue(away.next(away = true, "row-1", reread.replyMark()).new)
    }

    @Test
    fun newStaysNewUntilTheReaderIsBackAtTheEnd() {
        val seen = listOf(prompt, ChatMessage.Assistant("a1", text = "Done."))
        // The same prompt again, answered alike: the mark ends up where it was.
        val again = seen + ChatMessage.User("u2", prompt.text)
        var state = ReplySeen("row-7", seen.replyMark()).next(away = true, "row-7", seen.replyMark())
        assertFalse(state.new)
        state = state.next(away = true, "row-7", again.replyMark())
        state = state.next(away = true, "row-7", (again + ChatMessage.Assistant("a2", text = "Done.")).replyMark())
        assertEquals(seen.replyMark(), state.mark)
        assertTrue(state.new)
        // An older page meanwhile doesn't take it back.
        assertTrue(state.next(away = true, "row-1", state.mark).new)
        assertFalse(state.next(away = false, "row-1", state.mark).new)
    }

    @Test
    fun aNewPromptOrCommandOutputIsNews() {
        val seen = listOf(prompt, ChatMessage.Assistant("a1", text = "Done")).replyMark()
        assertNotEquals(seen, listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), ChatMessage.User("u2", "And now?")).replyMark())
        val command = ChatMessage.Command("c1", "/status", running = true)
        val ran = listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), command)
        assertNotEquals(ran.replyMark(), listOf(prompt, ChatMessage.Assistant("a1", text = "Done"), command.copy(output = "ok", running = false)).replyMark())
    }

    @Test
    fun dismissingASideCardWhileAwayIsNotANewReply() {
        val reply = ChatMessage.Assistant("a1", text = "Done")
        val card = ChatMessage.Command("side-1", "/btw which file?", output = "notes.txt", running = false, taskId = "btw_1")
        val withCard = listOf(prompt, reply, card)
        val away = ReplySeen(prompt.key, withCard.replyMark()).next(away = true, prompt.key, withCard.replyMark())

        val dismissed = listOf(prompt, reply)
        assertTrue(dismissed.onlyDismissedSideCardsFrom(withCard))
        val afterDismiss = away.next(away = true, prompt.key, dismissed.replyMark(), onlyDismissedCards = dismissed.onlyDismissedSideCardsFrom(withCard))
        assertFalse(afterDismiss.new)

        // A real reply afterwards still counts as news, as does one arriving in the same update as a dismissal.
        val replied = dismissed + ChatMessage.Assistant("a2", text = "Next answer")
        assertFalse(replied.onlyDismissedSideCardsFrom(dismissed))
        assertTrue(afterDismiss.next(away = true, prompt.key, replied.replyMark(), onlyDismissedCards = replied.onlyDismissedSideCardsFrom(dismissed)).new)
        assertFalse(replied.onlyDismissedSideCardsFrom(withCard))
        assertTrue(away.next(away = true, prompt.key, replied.replyMark(), onlyDismissedCards = replied.onlyDismissedSideCardsFrom(withCard)).new)
    }
}
