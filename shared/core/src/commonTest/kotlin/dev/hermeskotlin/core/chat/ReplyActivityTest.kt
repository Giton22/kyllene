package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyActivityTest {
    @Test
    fun silentAndToolOnlyActivityDoesNotMakeAnAlreadyReadBotChatUnread() {
        val rows = listOf(
            SessionMessage(role = "assistant", content = JsonPrimitive("Read already"), timestamp = 100.0),
            SessionMessage(role = "assistant", content = JsonPrimitive("[SILENT]"), timestamp = 120.0),
            SessionMessage(role = "assistant", reasoning = "Working", timestamp = 125.0),
            SessionMessage(role = "assistant", content = JsonPrimitive("Calling a tool"), timestamp = 130.0,
                toolCalls = buildJsonArray { add(buildJsonObject { put("name", "terminal") }) }),
            SessionMessage(role = "tool", content = JsonPrimitive("Result"), timestamp = 135.0),
        )
        assertFalse(rows.hasUnreadBotMessage(100.0))
    }

    @Test
    fun aVisibleReplyStaysUnreadAfterCompactionAndASilentTurn() {
        val rows = listOf(
            SessionMessage(role = "assistant", content = JsonPrimitive("Here's the result."), timestamp = 120.0,
                active = false, compacted = true),
            SessionMessage(role = "assistant", content = JsonPrimitive("Internal context"), timestamp = 125.0,
                active = false, compacted = true, displayKind = "hidden"),
            SessionMessage(role = "assistant", content = JsonPrimitive("[SILENT]"), timestamp = 130.0),
        )
        assertTrue(rows.hasUnreadBotMessage(100.0))
        assertFalse(rows.hasUnreadBotMessage(120.0))
    }

    @Test
    fun internalRowsThatOnlyResembleBotMessagesStayRead() {
        val wake = SessionMessage(
            role = "user",
            content = JsonPrimitive("Message from scheduler: Wake up"),
            displayKind = "internal_notification",
            timestamp = 120.0,
        )
        assertFalse(listOf(wake).hasUnreadBotMessage(100.0))
    }

    @Test
    fun realRepliesAndIncomingBotMessagesStillCount() {
        val reply = SessionMessage(role = "assistant", displayContent = JsonPrimitive("Done."), timestamp = 120.0)
        val incoming = SessionMessage(role = "user", content = JsonPrimitive("Message from 🤖 Rook (@rook): Check the result"),
            displayKind = "internal_notification", timestamp = 125.0)
        val tool = SessionMessage(role = "tool", content = JsonPrimitive("Result"), timestamp = 130.0)
        assertTrue(listOf(reply, tool).hasUnreadBotMessage(100.0))
        assertTrue(listOf(incoming, tool).hasUnreadBotMessage(120.0))
        assertFalse(listOf(reply, incoming, tool).hasUnreadBotMessage(125.0))
    }

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private val answered = ChatState().reduce(event("message.start")).reduce(event("message.complete", """{"text":"Already answered"}"""))

    @Test
    fun aBotChatSilenceDoesNotBringBackThePreviousReply() {
        // Bot Chat's message.complete is empty for a silence, so the turn's bubble goes and the old reply is last again.
        val running = answered.reduce(event("message.start"))
        val done = running.reduce(event("message.complete", """{"text":""}"""))
        assertEquals("Already answered", (done.messages.last() as ChatMessage.Assistant).text)
        assertNull(done.newFinishedReply(running))
    }

    @Test
    fun aReplyReadBackOnAReconnectIsNewAgainstTheTurnsStart() {
        // The turn began; the link dropped; the transcript read on the reconnect has its reply, finished, while the
        // chat still says running. Against the state just before the end it looks old; against the start it's new.
        val started = answered.reduce(event("message.start"))
        val readBack = started.copy(messages = answered.messages + ChatMessage.Assistant("row-7", text = "Done while away", outcome = TurnOutcome.Complete))
        val ended = readBack.copy(running = false)
        assertNull(ended.newFinishedReply(readBack))
        assertEquals("Done while away", ended.newFinishedReply(started, readBack)?.text)
    }

    @Test
    fun aSilenceAfterAHistoryReadDoesNotBringBackThePreviousReplyUnderItsRowKey() {
        // A heartbeat turn starts; its start reads the history, which keys the old live reply by its stored row.
        val started = answered.reduce(event("message.start"))
        val old = answered.messages.last() as ChatMessage.Assistant
        val reread = started.copy(messages = listOf(old.copy(key = "row-12")) + started.messages.drop(1))
        val done = reread.reduce(event("message.complete", """{"text":""}"""))
        assertEquals("row-12", done.messages.last().key)
        assertNull(done.newFinishedReply(started, reread))
    }

    @Test
    fun aReplyReadBackOnAReconnectWithTheSameWordsAsTheLastIsNew() {
        // A routine says "Nothing new." each run. This run's reply comes back from the stored transcript on a
        // reconnect, finished an hour after the last one, under its row's key, and was never streamed here.
        val last = ChatMessage.Assistant("live-1", text = "Nothing new.", outcome = TurnOutcome.Complete, timestamp = 1_000.0)
        val started = ChatState(messages = listOf(last), running = true)
        val readBack = started.copy(
            messages = listOf(last.copy(key = "row-11", timestamp = 1_001.0), last.copy(key = "row-12", timestamp = 4_600.0)),
        )
        assertEquals("row-12", readBack.copy(running = false).newFinishedReply(started, readBack)?.key)
        // A heartbeat that ends in silence after a read that re-keyed the old reply and dated it by the turn's
        // later tool step: still the old reply.
        val reread = started.copy(messages = listOf(last.copy(key = "row-11", timestamp = 4_600.0)))
        assertNull(reread.copy(running = false).newFinishedReply(started, reread))
    }

    @Test
    fun aTurnsOwnReplyIsNew() {
        val running = answered.reduce(event("message.start")).reduce(event("message.delta", """{"text":"Again"}"""))
        val done = running.reduce(event("message.complete", """{"text":"Again"}"""))
        assertEquals("Again", done.newFinishedReply(answered, running)?.text)
        // The same words as the reply before are still a new reply.
        val same = answered.reduce(event("message.start"))
        assertEquals("Already answered", same.reduce(event("message.complete", """{"text":"Already answered"}""")).newFinishedReply(answered, same)?.text)
    }
}
