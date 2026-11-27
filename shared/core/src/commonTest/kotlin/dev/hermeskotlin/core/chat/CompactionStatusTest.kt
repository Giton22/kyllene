package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bug #565: the auto-compaction status lifecycle (hermes-agent v0.23.4, agent/conversation_compression.py
 * COMPACTION_STATUS / COMPACTION_DONE_STATUS, sent by tui_gateway/agent_callbacks.py `_agent_status_update` as
 * `status.update`). The gateway never sends a clearing `status.update` (server.py `_status_update` drops an empty
 * body), so the client has to retire the "complete" edge itself once the turn moves on.
 */
class CompactionStatusTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun ChatState.apply(vararg events: GatewayEvent) = events.fold(this) { state, e -> state.reduce(e) }

    private val compacting = event("status.update", """{"kind":"compacting","text":"$COMPACTING"}""")
    private val compacted = event("status.update", """{"kind":"compacted","text":"$COMPACTED"}""")

    /** A turn that compacted mid-way: started, compacting shown, then the terminal edge. */
    private fun compactedMidTurn() = ChatState(messages = listOf(ChatMessage.User("u", "go on"))).apply(
        event("message.start"),
        event("tool.start", """{"tool_id":"t1","name":"terminal","context":"ls"}"""),
        event("tool.complete", """{"tool_id":"t1","name":"terminal","summary":"ok"}"""),
        compacting,
        compacted,
    )

    @Test
    fun theCompactingLineShowsWhileItRuns() {
        val state = ChatState().apply(event("message.start"), compacting)
        assertEquals(COMPACTING, state.status)
    }

    @Test
    fun theCompletedEdgeIsShownRightAfterCompaction() {
        // Briefly shown is fine: this is what the issue calls the completion status.
        assertEquals(COMPACTED, compactedMidTurn().status)
    }

    @Test
    fun theCompletedStatusClearsWhenTheModelThinksAgain() {
        val state = compactedMidTurn().apply(event("thinking.delta", """{"text":"(>∀<☆)☆ musing..."}"""))
        assertNull(state.status, "still showing '${state.status}' after the turn moved on to thinking")
    }

    @Test
    fun theCompletedStatusClearsWhenReasoningStreams() {
        val state = compactedMidTurn().apply(event("reasoning.delta", """{"text":"Next I will"}"""))
        assertNull(state.status, "still showing '${state.status}' after reasoning resumed")
    }

    @Test
    fun theCompletedStatusClearsWhenTheReplyStreams() {
        val state = compactedMidTurn().apply(event("message.delta", """{"text":"Here is"}"""))
        assertNull(state.status, "still showing '${state.status}' after the reply resumed streaming")
    }

    @Test
    fun theCompletedStatusClearsWhenTheNextToolStarts() {
        val state = compactedMidTurn().apply(event("tool.start", """{"tool_id":"t2","name":"read_file","context":"a.txt"}"""))
        assertNull(state.status, "still showing '${state.status}' beside the next tool")
    }

    @Test
    fun theCompletedStatusClearsWhenTheTurnEnds() {
        val state = compactedMidTurn().apply(
            event("message.delta", """{"text":"Done."}"""),
            event("message.complete", """{"text":"Done.","status":"complete"}"""),
        )
        assertNull(state.status)
    }

    @Test
    fun aCompactionAtTheTurnsEndClearsWithTheEnd() {
        val state = ChatState().apply(
            event("message.start"),
            event("message.delta", """{"text":"Done."}"""),
            compacting,
            compacted,
            event("message.complete", """{"text":"Done.","status":"complete"}"""),
        )
        assertNull(state.status)
    }

    @Test
    fun aCompactionBetweenTurnsDoesNotCarryIntoTheNextTurn() {
        // A compaction edge that lands with no turn running (a deferred /compress, or a late edge after the end).
        val idle = ChatState().apply(
            event("message.start"),
            event("message.complete", """{"text":"Done.","status":"complete"}"""),
            compacting,
            compacted,
        )
        val next = idle.apply(event("message.start"))
        assertNull(next.status, "the next turn opens on '${next.status}'")
    }

    @Test
    fun theLiveChatRetiresTheCompletedStatusOverTheSocket() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false).apply {
            answer = { call -> if (call.method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}" }
            http = { json("""{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"hello"}]}""") }
            start(awaitConnected = false)
        }
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        val socket = gateway.socket

        socket.push(event("message.start", sessionId = "rt1"))
        socket.push(event("status.update", sessionId = "rt1", payload = """{"kind":"compacting","text":"$COMPACTING"}"""))
        socket.push(event("status.update", sessionId = "rt1", payload = """{"kind":"compacted","text":"$COMPACTED"}"""))
        chat.state.first { it.status == COMPACTED }
        socket.push(event("message.delta", sessionId = "rt1", payload = """{"text":"Continuing"}"""))

        val streaming = chat.state.first { (it.messages.last() as? ChatMessage.Assistant)?.text == "Continuing" }
        assertEquals(true, streaming.running)
        assertNull(streaming.status, "the live chat still shows '${streaming.status}' while the reply streams")
    }

    @Test
    fun aProviderWaitAfterCompactionTakesTheStatusLine() {
        val wait = "⏳ still waiting on the provider (40s)"
        val state = compactedMidTurn().apply(event("thinking.delta", """{"text":"$wait"}"""))
        assertEquals(wait, state.status)
    }

    @Test
    fun theCompletedStatusClearsOnInterimTextAndAToolBeingWritten() {
        val interim = compactedMidTurn().apply(event("message.interim", """{"text":"Checking the logs."}"""))
        assertNull(interim.status)
        val generating = compactedMidTurn().apply(event("tool.generating", """{"name":"terminal"}"""))
        assertNull(generating.status)
    }

    @Test
    fun theCompactingLineStaysWhileTheModelThinks() {
        val state = ChatState().apply(event("message.start"), compacting, event("thinking.delta", """{"text":"(>∀<☆)☆ musing..."}"""))
        assertEquals(COMPACTING, state.status)
    }

    @Test
    fun aManualCompressOutsideATurnLeavesNoStatus() {
        // tui_gateway/methods_session.py _compress_live: pinned `compressing`, the agent's lifecycle, then `ready` to clear.
        val state = ChatState().apply(*manualCompress().toTypedArray())
        assertNull(state.status)
        val next = state.apply(event("message.start"), event("thinking.delta", """{"text":"(>∀<☆)☆ musing..."}"""))
        assertNull(next.status, "the next turn shows '${next.status}'")
        assertEquals("(>∀<☆)☆ musing...", next.thinkingFrame)
    }

    @Test
    fun aComputeHostCompactionBetweenTurnsIsRetiredByTheNextTurn() {
        // tools/compute_host_bridge.py sends its own `compacted` edge with no turn running.
        val idle = ChatState().apply(event("status.update", """{"kind":"compacted","text":"✓ Context compression complete"}"""))
        assertNull(idle.apply(event("message.start")).status)
    }

    @Test
    fun aProcessStatusBeforeTheTurnItWakesIsKept() {
        // session_auto_continue.py: "Resuming interrupted turn…" right before the turn's message.start.
        val resuming = "↻ Resuming interrupted turn…"
        val state = ChatState().apply(event("status.update", """{"kind":"process","text":"$resuming"}"""), event("message.start"))
        assertEquals(resuming, state.status)
    }

    @Test
    fun anOrdinaryStatusLineStaysWhileTheReplyStreams() {
        val state = ChatState().apply(
            event("message.start"),
            event("status.update", """{"kind":"status","text":"running ls"}"""),
            event("message.delta", """{"text":"Here"}"""),
        )
        assertEquals("running ls", state.status)
    }

    @Test
    fun theLiveChatShowsNoStatusAfterAManualCompress() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false).apply {
            answer = { call -> if (call.method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}" }
            http = { json("""{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"hello"}]}""") }
            start(awaitConnected = false)
        }
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        val socket = gateway.socket

        manualCompress().forEach { socket.push(event(it.type, sessionId = "rt1", payload = it.payload.toString())) }
        socket.push(event("message.start", sessionId = "rt1"))
        socket.push(event("thinking.delta", sessionId = "rt1", payload = """{"text":"(>∀<☆)☆ musing..."}"""))

        val thinking = chat.state.first { it.thinkingFrame == "(>∀<☆)☆ musing..." }
        assertEquals(true, thinking.running)
        assertNull(thinking.status, "the live chat shows '${thinking.status}' after /compress")
    }

    @Test
    fun aReconnectThatCannotPickTheTurnUpDropsTheCompletedStatus() = runTest {
        // No seqs, so the resume can't replay what the turn did while the link was down.
        val gateway = FakeGateway(backgroundScope, reconnects = true).apply {
            answer = { call ->
                if (call.method == "session.resume") """{"session_id":"${if (sockets.size > 1) "rt2" else "rt1"}","running":true}""" else "{}"
            }
            http = { json("""{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"hello"}]}""") }
            start()
        }
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        val first = gateway.socket
        first.push(event("status.update", sessionId = "rt1", payload = """{"kind":"compacted","text":"$COMPACTED"}"""))
        chat.state.first { it.status == COMPACTED }

        first.serverClose(1006)
        val back = chat.state.first { it.runtimeSessionId == "rt2" }
        assertEquals(true, back.running)
        assertNull(back.status, "the reconnected chat still shows '${back.status}'")
    }

    private fun manualCompress() = listOf(
        event("status.update", """{"kind":"compressing","text":"⠋ compressing 40 messages (~12k tok)…"}"""),
        compacting,
        compacted,
        event("status.update", """{"kind":"status","text":"ready"}"""),
    )

    private companion object {
        const val COMPACTING = "🗜️ Compacting context — summarizing earlier conversation so I can continue..."
        const val COMPACTED = "✓ Context compaction complete — continuing turn..."
    }
}
