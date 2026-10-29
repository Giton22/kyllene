package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SessionUsage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.wearlink.ReplyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchTurnTest {

    private val earlier = listOf(
        ChatMessage.User("u0", "earlier"),
        ChatMessage.Assistant("a0", "an earlier reply"),
    )

    @Test
    fun beforeTheTurnStartsItIsSendingWithNoReplyFromEarlier() {
        val state = ChatState(storedSessionId = "s1", messages = earlier + ChatMessage.User("u1", "hi", pending = true))
        val progress = progressOf(state)
        assertEquals(ReplyPhase.Sending, progress.phase)
        assertEquals("", progress.text)
        assertEquals("s1", progress.chatId)
    }

    @Test
    fun theRunningToolIsTheActivityAndTheReplySoFarTheText() {
        val reply = ChatMessage.Assistant(
            "a1",
            "Looking",
            streaming = true,
            tools = listOf(ToolActivity("t1", "read_file"), ToolActivity("t2", "terminal", running = true)),
        )
        val state = ChatState(running = true, title = "Disk", messages = earlier + ChatMessage.User("u1", "hi") + reply)
        val progress = progressOf(state)
        assertEquals(ReplyPhase.Working, progress.phase)
        assertEquals("Looking", progress.text)
        assertEquals("Running a command", progress.activity)
        assertEquals("Disk", progress.title)
    }

    @Test
    fun withoutARunningToolTheGatewaysStatusLineIsTheActivity() {
        val state = ChatState(running = true, status = "thinking…", messages = earlier + ChatMessage.User("u1", "hi"))
        assertEquals("thinking…", progressOf(state).activity)
        assertNull(progressOf(state.copy(status = " ")).activity)
    }

    @Test
    fun aQuestionTheAgentWaitsOnSendsTheWatchToThePhone() {
        val approval = InputRequest.Approval("r1", "rm -rf build", "Delete the build folder", "terminal", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        val state = ChatState(running = true, inputRequests = listOf(approval), messages = earlier + ChatMessage.User("u1", "clean"))
        val progress = progressOf(state)
        assertEquals(ReplyPhase.WaitingOnPhone, progress.phase)
        assertEquals("Waiting for you on your phone", progress.activity)
    }

    @Test
    fun theReplyIsTheLastOnePastThePrompt() {
        val state = ChatState(messages = earlier + ChatMessage.User("u1", "hi") + ChatMessage.Assistant("a1", "one") + ChatMessage.Assistant("a2", "two"))
        assertEquals("two", replyAfter(state)?.text)
        assertNull(replyAfter(ChatState(messages = earlier + ChatMessage.User("u1", "hi"))))
    }

    @Test
    fun theContextIsTheGatewaysFigureElseWorkedOutFromTheTokens() {
        assertEquals(36, contextPercentOf(ChatState(usage = SessionUsage(contextUsed = 10, contextMax = 100, contextPercent = 36))))
        assertEquals(25, contextPercentOf(ChatState(usage = SessionUsage(contextUsed = 50_000, contextMax = 200_000))))
        assertNull(contextPercentOf(ChatState(usage = SessionUsage(contextUsed = 50_000, contextMax = 0))))
        assertNull(contextPercentOf(ChatState()))
    }

    @Test
    fun theModelGoesToTheWatchOnlyOnceTheGatewayNamedIt() {
        val progress = progressOf(ChatState(model = "claude-sonnet-4-5", usage = SessionUsage(contextPercent = 12)))
        assertEquals(displayModelName("claude-sonnet-4-5"), progress.model)
        assertEquals(12, progress.contextPercent)
        assertNull(modelOf(ChatState(model = " ")))
    }

    @Test
    fun aHistoryReloadedFromAnotherRowStillFindsTheReplyToTheLastPrompt() {
        // The turn's end reloads the newest rows: the earlier ones are gone, so positions moved.
        val state = ChatState(messages = listOf(ChatMessage.User("s1", "hi"), ChatMessage.Assistant("s2", "hello")))
        assertEquals("hello", replyAfter(state)?.text)
    }
}
