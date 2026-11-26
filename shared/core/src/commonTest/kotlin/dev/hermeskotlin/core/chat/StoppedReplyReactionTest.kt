package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A reply to our own turn that the user stopped can be reacted to at once, as after a reload. hermes-agent stores
 * the streamed part as an assistant row marked `display_metadata.interrupted` (v0.22.0 to v0.23.4
 * agent/turn_api_call.py), but the turn's receipt names that row only for a turn that completed
 * (agent/persisted_turn_receipt.py `status == "complete"`), so the transcript is read once the turn ends.
 *
 * Each case runs on a legacy gateway (`/api/ws` on the TUI gateway, a runtime id of its own) and on one whose
 * `/api/ws` runs on its session authority (the stored id, events stamped with the prompt's admission).
 */
class StoppedReplyReactionTest {

    private lateinit var gateway: FakeGateway

    /** The stored rows the transcript route answers with. */
    private var rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""

    /** How often the transcript was read. */
    private var historyReads = 0

    /** Held until completed, to let a read of the transcript land late. */
    private var historyGate: CompletableDeferred<Unit>? = null

    /** Which kind of gateway a chat runs on, and how it names the chat and stamps a turn's events. */
    private class Mode(val authority: Boolean) {
        val runtimeId = if (authority) "stored-1" else "rt1"

        fun event(type: String, payload: String = "{}", admission: String = "a1"): String = if (authority) {
            """{"jsonrpc":"2.0","method":"event","params":{"authority_epoch":"e1","execution_generation":2,""" +
                """"admission_id":"$admission","type":"$type","session_id":"stored-1","payload":$payload}}"""
        } else {
            dev.hermeskotlin.core.rpc.event(type, runtimeId, payload)
        }

        override fun toString() = if (authority) "session authority" else "legacy"
    }

    private val modes = listOf(Mode(authority = false), Mode(authority = true))

    private suspend fun chat(scope: CoroutineScope, mode: Mode): Pair<ChatSession, FakeTransport> {
        var admissions = 0
        gateway = FakeGateway(scope, reconnects = false).apply {
            if (mode.authority) ready = { AUTHORITY_READY }
            answer = { call ->
                when (call.method) {
                    "session.resume" ->
                        if (mode.authority) """{"session_id":"stored-1","running":false,"revision":4,"execution_generation":2}"""
                        else """{"session_id":"rt1","running":false}"""
                    "prompt.submit" ->
                        if (mode.authority) """{"admission_id":"a${++admissions}","status":"queued"}""" else """{"status":"streaming"}"""
                    "session.interrupt" -> """{"status":"interrupted"}"""
                    else -> "{}"
                }
            }
            http = { request ->
                if (request.url.encodedPath != "/api/sessions/stored-1/messages") error("unexpected ${request.url}")
                historyReads++
                historyGate?.await()
                json("""{"session_id":"stored-1","messages":[$rows]}""")
            }
            start(awaitConnected = true)
        }
        val client = (gateway.connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected).client
        client.ready.await()
        assertEquals(mode.authority, client.sessionAuthority)
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == mode.runtimeId && it.historyLoaded }
        return chat to gateway.socket
    }

    /** Sends a prompt and streams part of its reply. */
    private suspend fun streamTurn(chat: ChatSession, transport: FakeTransport, mode: Mode, text: String = "Working on it") {
        assertTrue(chat.send("next"))
        transport.awaitSent { it.isCall("prompt.submit") }
        transport.push(mode.event("message.start"))
        if (text.isNotEmpty()) {
            transport.push(mode.event("message.delta", """{"text":"$text"}"""))
            chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && it.streaming && it.text == text } }
        }
    }

    /** What v0.23.4 sends when our turn was stopped: its rows, but no final assistant row. */
    private fun stopped(text: String, rowIds: String = "3,4") =
        """{"text":"$text","status":"interrupted","persisted_turn":{"row_ids":[$rowIds],"user_row_id":3,"complete":false}}"""

    private fun ChatState.replies() = messages.filterIsInstance<ChatMessage.Assistant>()

    /** What ChatScreen's AssistantReply needs to offer the reactions. */
    private fun ChatMessage.Assistant.reactable() = !streaming && reactionRowId != null

    @Test
    fun aStoppedReplyCanBeReactedToWithoutAReload() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode)
            // What the gateway stored for the turn: the prompt and the part of the reply that streamed.
            rows += """,{"id":3,"role":"user","content":"next"},""" +
                """{"id":4,"role":"assistant","content":"Working on it","display_metadata":{"interrupted":true}}"""
            val reads = historyReads

            chat.interrupt()
            transport.awaitSent { it.isCall("session.interrupt") }
            transport.push(mode.event("message.complete", stopped("Working on it")))

            val shown = chat.state.first { s -> !s.running && s.replies().last().reactionRowId != null }
            val reply = shown.replies().last()
            assertEquals(4L, reply.reactionRowId, "$mode: $reply")
            assertTrue(reply.reactable(), "$mode: $reply")
            assertEquals("Working on it", reply.text, "$mode")
            // Still marked stopped once the stored rows replace the live copy.
            assertEquals(TurnOutcome.Interrupted, reply.outcome, "$mode")
            assertEquals(reads + 1, historyReads, "$mode: the transcript is read once")
            assertEquals(listOf("hello", "next"), shown.messages.filterIsInstance<ChatMessage.User>().map { it.text }, "$mode")
            chat.stop()
        }
    }

    @Test
    fun aCompletedReplyUsesItsReceiptAndReadsNothing() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode)
            val reads = historyReads

            transport.push(mode.event("message.complete",
                """{"text":"Working on it","status":"complete","persisted_turn":{"row_ids":[3,4],"user_row_id":3,"complete":true,"final_assistant_row_id":4}}"""))

            val reply = chat.state.first { !it.running }.replies().last()
            assertEquals(4L, reply.reactionRowId, "$mode")
            assertEquals(TurnOutcome.Complete, reply.outcome, "$mode")
            assertEquals(reads, historyReads, "$mode: a completed turn needs no read")
            chat.stop()
        }
    }

    @Test
    fun aFailedReplyOffersNoReactionLiveOrAfterAReload() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode)
            // A failed turn keeps the prompt and closes with a notice row, not the reply (agent/conversation_loop.py
            // `_close_durable_failed_turn`).
            rows += """,{"id":3,"role":"user","content":"next"},""" +
                """{"id":4,"role":"assistant","content":"This turn failed.","display_kind":"failed_turn"}"""

            transport.push(mode.event("message.complete",
                """{"text":"Working on it","status":"error","error":"Provider timed out","persisted_turn":{"row_ids":[3,4],"user_row_id":3,"complete":false}}"""))

            val live = chat.state.first { !it.running }.replies().last()
            assertEquals(TurnOutcome.Error, live.outcome, "$mode")
            assertFalse(live.reactable(), "$mode: $live")

            chat.retry()
            val reloaded = chat.state.first { s -> s.messages.any { it is ChatMessage.Event && it.event is TranscriptEvent.FailedTurn } }
            assertTrue(reloaded.replies().none { it.reactionRowId == 4L }, "$mode: ${reloaded.replies()}")
            chat.stop()
        }
    }

    @Test
    fun aReplyStoppedMidToolOffersNoReaction() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode, text = "Checking.")
            // A stop while a tool ran closes the turn with a hidden placeholder row that carries the stopped mark
            // (v0.23.4 agent/message_sanitization.py `close_interrupted_tool_sequence`).
            rows += """,{"id":3,"role":"user","content":"next"},""" +
                """{"id":4,"role":"assistant","content":"Checking.","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{}"}}]},""" +
                """{"id":5,"role":"tool","tool_call_id":"c1","content":"ok"},""" +
                """{"id":6,"role":"assistant","content":"","display_kind":"hidden","display_metadata":{"interrupted":true}}"""
            val reads = historyReads

            chat.interrupt()
            transport.push(mode.event("message.complete", stopped("", rowIds = "3,4,5,6")))

            chat.state.first { !it.running && historyReads > reads }
            val shown = chat.state.first { s -> s.messages.any { it.key == "row-4" } }
            assertTrue(shown.replies().none { it.reactable() && it.reactionRowId != 2L }, "$mode: ${shown.replies()}")
            // The stopped mark is on the hidden row, and the reply it closes still reads as stopped.
            assertEquals(TurnOutcome.Interrupted, shown.replies().last().outcome, "$mode: ${shown.replies()}")
            chat.stop()
        }
    }

    @Test
    fun onlyAStoppedTurnWithStoredRowsButNoNamedReplyIsRead() {
        fun read(payload: String) = stoppedUnnamed(HermesJson.decodeFromString<JsonObject>(payload))
        assertTrue(read(stopped("Working on it")))
        // Row 0 names nothing, as the reducer reads it.
        assertTrue(read("""{"status":"interrupted","persisted_turn":{"row_ids":[3,4],"final_assistant_row_id":0}}"""))
        assertFalse(read("""{"status":"interrupted","persisted_turn":{"row_ids":[3,4],"final_assistant_row_id":4}}"""))
        // No receipt, or one with no rows, doesn't say the reply was stored: a read could drop the live copy.
        assertFalse(read("""{"text":"Working on it","status":"interrupted"}"""))
        assertFalse(read("""{"status":"interrupted","persisted_turn":{"row_ids":[]}}"""))
        // Only the prompt's row (a stop before any text, or a save of the reply that failed): no reply to read.
        assertFalse(read("""{"status":"interrupted","persisted_turn":{"row_ids":[3],"user_row_id":3}}"""))
        assertFalse(read("""{"status":"interrupted","persisted_turn":{"row_ids":[3,5],"user_row_id":3,"user_row_ids":[3,5]}}"""))
        assertTrue(read("""{"status":"interrupted","persisted_turn":{"row_ids":[3,4],"user_row_id":3,"user_row_ids":[3]}}"""))
        assertFalse(read("""{"status":"complete","persisted_turn":{"row_ids":[3,4]}}"""))
        assertFalse(read("""{"status":"error","persisted_turn":{"row_ids":[3,4]}}"""))
    }

    @Test
    fun aReceiptNamingRowZeroStillReadsTheStoppedReply() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode)
            rows += """,{"id":3,"role":"user","content":"next"},""" +
                """{"id":4,"role":"assistant","content":"Working on it","display_metadata":{"interrupted":true}}"""

            chat.interrupt()
            transport.awaitSent { it.isCall("session.interrupt") }
            // Row 0 names nothing, as the reducer reads it too.
            transport.push(mode.event("message.complete",
                """{"text":"Working on it","status":"interrupted","persisted_turn":{"row_ids":[3,4],"user_row_id":3,"final_assistant_row_id":0}}"""))

            val reply = chat.state.first { s -> !s.running && s.replies().last().reactionRowId != null }.replies().last()
            assertEquals(4L, reply.reactionRowId, "$mode: $reply")
            chat.stop()
        }
    }

    @Test
    fun aTurnStoppedBeforeAnyTextOffersNoReaction() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode, text = "")
            rows += """,{"id":3,"role":"user","content":"next"}"""

            chat.interrupt()
            transport.awaitSent { it.isCall("session.interrupt") }
            // The waiting-for-model text is blanked before it goes out (tui_gateway/prompt_turn.py). Only the prompt
            // was stored, so nothing is read.
            transport.push(mode.event("message.complete", stopped("", rowIds = "3")))
            // A notice after it shows once the end of the turn was handled.
            transport.push(mode.event("notification.show", """{"text":"Done","level":"info","kind":"sticky","key":"after.stop"}"""))

            val shown = chat.state.first { s -> !s.running && s.notices.any { it.key == "after.stop" } }
            assertEquals(listOf(2L), shown.replies().filter { it.reactable() }.map { it.reactionRowId }, "$mode: ${shown.replies()}")
            chat.stop()
        }
    }

    @Test
    fun aStopAndSendStartsTheNewTurnUnharmedByTheRead() = runTest(timeout = 10.seconds) {
        for (mode in modes) {
            rows = """{"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}"""
            val (chat, transport) = chat(backgroundScope, mode)
            streamTurn(chat, transport, mode)
            rows += """,{"id":3,"role":"user","content":"next"},""" +
                """{"id":4,"role":"assistant","content":"Working on it","display_metadata":{"interrupted":true}},""" +
                """{"id":5,"role":"user","content":"do the docs instead"}"""
            val gate = CompletableDeferred<Unit>().also { historyGate = it }
            val reads = historyReads

            val sent = async { chat.stopAndSubmit("do the docs instead") }
            transport.awaitSent { it.isCall("session.interrupt") }
            transport.push(mode.event("message.complete", stopped("Working on it")))
            assertEquals(SendOutcome.Sent, sent.await().outcome, "$mode")
            // The new turn starts and streams while the stopped turn's read is still out.
            transport.push(mode.event("message.start", admission = "a2"))
            transport.push(mode.event("message.delta", """{"text":"Docs first"}""", admission = "a2"))
            chat.state.first { s -> s.replies().any { it.streaming && it.text == "Docs first" } }
            chat.state.first { historyReads > reads }
            gate.complete(Unit)
            historyGate = null

            val shown = chat.state.first { s -> s.replies().any { it.reactionRowId == 4L } }
            assertTrue(shown.running, "$mode")
            val (stopped, live) = shown.replies().takeLast(2)
            assertEquals(4L, stopped.reactionRowId, "$mode")
            assertEquals(TurnOutcome.Interrupted, stopped.outcome, "$mode")
            assertTrue(live.streaming, "$mode: $live")
            assertEquals("Docs first", live.text, "$mode")
            assertNull(live.reactionRowId, "$mode")
            // The new prompt shows once, above its streaming reply.
            assertEquals(listOf("hello", "next", "do the docs instead"),
                shown.messages.filterIsInstance<ChatMessage.User>().map { it.text }, "$mode")
            assertEquals(live, shown.messages.last(), "$mode")

            transport.push(mode.event("message.delta", """{"text":", then the tests."}""", admission = "a2"))
            val more = chat.state.first { s -> s.replies().last().text == "Docs first, then the tests." }
            assertTrue(more.replies().last().streaming, "$mode")
            chat.stop()
        }
    }

    private companion object {
        const val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""
    }
}
