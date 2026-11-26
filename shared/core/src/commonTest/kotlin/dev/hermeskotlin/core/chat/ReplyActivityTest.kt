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
    fun aTurnsOwnReplyIsNew() {
        val running = answered.reduce(event("message.start")).reduce(event("message.delta", """{"text":"Again"}"""))
        val done = running.reduce(event("message.complete", """{"text":"Again"}"""))
        assertEquals("Again", done.newFinishedReply(running)?.text)
        // The same words as the reply before are still a new reply.
        val same = answered.reduce(event("message.start"))
        assertEquals("Already answered", same.reduce(event("message.complete", """{"text":"Already answered"}""")).newFinishedReply(same)?.text)
    }
}
