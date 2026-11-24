package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Picking the event stream up on the session authority's `/api/ws` (`gateway.ready.session_authority`). There the
 * replay epoch is per session (gateway/session_events.py `SessionEvents.epoch`): `session.resume` and
 * `session.create` name it as `replay_epoch` beside `last_sequence`, every event frame carries it as
 * `params.replay_epoch`, and `session.events.since` answers `snapshot_required` / `truncated` with no events unless
 * its `replay_epoch` param is that epoch. `gateway.ready`'s `replay_epoch` is the process's
 * (tui_gateway/event_replay.py `replay_epoch()`), a different value. The legacy gateway's side is in
 * [ChatSessionResumeTest].
 */
class AuthorityReplayEpochTest {

    private val url = FakeGateway.URL
    private val processEpoch = "proc-epoch-1"
    private var sessionEpoch = "sess-epoch-1"
    private val sid = "stored-1"

    private val transcript = listOf(
        """{"id":1,"role":"user","content":"p1"}""",
        """{"id":2,"role":"assistant","content":"a2"}""",
        """{"id":3,"role":"user","content":"p3"}""",
    )
    /** How many times the transcript was read. */
    private val reads = MutableStateFlow(0)

    /** The authority's ring for [sid]: every published frame's params, in seq order. */
    private val ring = mutableListOf<String>()
    private var sequence = 0L

    /** SessionEvents.publish: stamps the next seq and the session's epoch. */
    private fun publish(type: String, payload: String = "{}"): String {
        val seq = ++sequence
        val params = """{"type":"$type","session_id":"$sid","payload":$payload,"replay_epoch":"$sessionEpoch","seq":$seq,"execution_generation":1}"""
        ring += params
        return """{"jsonrpc":"2.0","method":"event","params":$params}"""
    }

    private fun delta(text: String) = publish("message.delta", """{"text":"$text"}""")

    /** The ring evicted the session: SessionEvents.watermark starts a new epoch counting from 1. */
    private fun startAnew(epoch: String) {
        sessionEpoch = epoch
        sequence = 0
        ring.clear()
    }

    /** gateway/session_controls.py events_since + session_events.py SessionEvents.since (v0.23.4). */
    private fun since(params: JsonObject, trustAnyEpoch: Boolean = false): String {
        val from = (params["last_sequence"] as? JsonPrimitive)?.longOrNull ?: (params["last_seen"] as? JsonPrimitive)?.longOrNull ?: 0
        val epoch = if (trustAnyEpoch) sessionEpoch else (params["replay_epoch"] as? JsonPrimitive)?.contentOrNull
        val missing = epoch != sessionEpoch || from > sequence
        val events = if (missing) emptyList() else ring.drop(from.toInt())
        return """{"events":[${events.joinToString(",")}],"latest_seq":$sequence,"last_sequence":$sequence,""" +
            """"epoch":"$sessionEpoch","replay_epoch":"$sessionEpoch","truncated":$missing,"snapshot_required":$missing,""" +
            """"count":${events.size},"open_requests":[]}"""
    }

    /**
     * session_controls.py resume (v0.23.4), trimmed to what the chat reads: no `inflight` and no `turn_started_at`,
     * so the reply streamed while away comes only from the replay. [withEpoch] false: a resume naming no session
     * epoch, as no authority build does, to check nothing is guessed in its place.
     */
    private fun resume(running: Boolean = true, withEpoch: Boolean = true) =
        """{"session_id":"$sid","stored_session_id":"$sid","messages":[],"messages_omitted":true,""" +
            """"running":$running,"authority_epoch":1,""" +
            (if (withEpoch) """"replay_epoch":"$sessionEpoch","last_sequence":$sequence,""" else "") +
            """"subscription_id":"sub-1","revision":1,"execution_generation":1,"pending":[],"prompts":[],"info":{},""" +
            """"pending_connection":null,"todo_state":null,"open_requests":[]}"""

    private lateinit var gateway: FakeGateway
    private var answer: (FakeTransport, String, JsonObject) -> String? = { _, _, _ -> "{}" }

    private suspend fun connect(scope: CoroutineScope): Pair<GatewayConnection, List<FakeTransport>> {
        gateway = FakeGateway(scope).apply {
            answer = { call -> this@AuthorityReplayEpochTest.answer(call.socket, call.method, call.params) }
            // tui_gateway/ws.py: the process's replay_epoch, and session_authority on /api/ws.
            ready = {
                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":""" +
                    """{"replay_epoch":"$processEpoch","session_authority":true}}}"""
            }
            http = { request ->
                require(request.url.encodedPath == "/api/sessions/$sid/messages") { "unexpected ${request.url}" }
                reads.update { it + 1 }
                json("""{"session_id":"$sid","messages":[${transcript.joinToString(",")}]}""")
            }
            start(awaitConnected = false)
        }
        return gateway.connection to gateway.sockets
    }

    private fun ChatState.reply() = messages.last() as? ChatMessage.Assistant

    private fun FakeTransport.sinceCalls() = sent.value.filter { it.isCall("session.events.since") }

    private fun JsonObject.params() = this["params"]!!.jsonObject

    /** stored-1 opened on the authority, streaming "Hel" (seqs 1-3); then its link drops. */
    private suspend fun droppedMidTurn(scope: CoroutineScope): Pair<ChatSession, List<FakeTransport>> {
        val (connection, transports) = connect(scope)
        val chat = ChatSession(url, sid, "Chat", connection, SessionsApi(gateway.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == sid && it.historyLoaded }
        val first = transports.last()
        first.push(publish("message.start"))
        first.push(delta("He"))
        first.push(delta("l"))
        chat.state.first { it.reply()?.text == "Hel" }
        first.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        return chat to transports
    }

    @Test
    fun aDropMidTurnOnTheSessionAuthorityReplaysTheGapInsteadOfReloading() = dropAndReconnect(trustAnyEpoch = false)

    /**
     * The same, with the gateway answering the gap whatever epoch is asked with: isolates the chat's own check of
     * the reply's `epoch` (EventReplay.parse), which must be the session's, not `gateway.ready`'s.
     */
    @Test
    fun aGapTheAuthorityAnswersIsTakenWithItsPerSessionEpoch() = dropAndReconnect(trustAnyEpoch = true)

    private fun dropAndReconnect(trustAnyEpoch: Boolean) = runTest {
        answer = { transport, method, params ->
            when (method) {
                "session.resume" -> resume()
                "session.events.since" -> since(params, trustAnyEpoch).also { transport.push(delta("ld")) }
                else -> "{}"
            }
        }
        val (chat, transports) = droppedMidTurn(backgroundScope)
        // Published while no socket of this phone was attached: only the ring has them.
        publish("message.delta", """{"text":"lo"}""")
        publish("message.delta", """{"text":" wor"}""")
        val readsBefore = reads.value

        val done = chat.state.first { it.reply()?.text?.endsWith("ld") == true }

        val ask = transports.last().sinceCalls().first()
        val problems = buildList {
            if (!trustAnyEpoch && ask.param("replay_epoch") != sessionEpoch) {
                add("session.events.since sent replay_epoch=${ask.param("replay_epoch")} (want $sessionEpoch), params ${ask["params"]}")
            }
            if ("last_sequence" in ask.params()) add("session.events.since sent last_sequence, which gateways up to v0.22.2 refuse")
            if (ask.param("last_seen") != "3") add("session.events.since asked from ${ask.param("last_seen")}, want 3")
            if (reads.value != readsBefore) add("the transcript was read again (${reads.value - readsBefore} read(s)) although the ring held seqs 4-5")
            if (done.reply()?.text != "Hello world") add("reply shows '${done.reply()?.text}', want 'Hello world'")
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun aSessionThatStartedCountingAnewWhileAwayReloadsInsteadOfReplaying() = runTest {
        answer = { _, method, params ->
            when (method) {
                "session.resume" -> resume()
                "session.events.since" -> since(params)
                else -> "{}"
            }
        }
        val (chat, transports) = droppedMidTurn(backgroundScope)
        val readsBefore = reads.value
        startAnew("sess-epoch-2")
        publish("message.start")

        chat.state.first { it.runtimeSessionId == sid }
        // Seqs of the new epoch aren't the ones the chat counted: it reads the transcript, it doesn't ask the ring.
        reads.first { it > readsBefore }
        assertTrue(transports.last().sinceCalls().isEmpty(), "asked the ring of epoch 2 for seqs counted in epoch 1")
        assertTrue(chat.state.value.running)
    }

    @Test
    fun aResumeWithoutASessionEpochIsNotAnsweredWithTheProcesss() = runTest {
        answer = { _, method, params ->
            when (method) {
                "session.resume" -> resume(withEpoch = false)
                "session.events.since" -> since(params)
                else -> "{}"
            }
        }
        val (chat, transports) = droppedMidTurn(backgroundScope)
        val readsBefore = reads.value

        chat.state.first { it.runtimeSessionId == sid }
        reads.first { it > readsBefore }
        // No epoch to name: the gateway.ready one would only be refused, so the chat reloads as before replay.
        assertFalse(transports.last().sinceCalls().any { it.param("replay_epoch") == processEpoch })
    }

    @Test
    fun aGapInTheLiveStreamIsFilledWithTheSessionsEpoch() = runTest {
        answer = { _, method, params ->
            when (method) {
                "session.resume" -> resume()
                "session.events.since" -> since(params)
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, sid, "Chat", connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == sid && it.historyLoaded }
        val socket = transports.last()
        socket.push(delta("a"))
        chat.state.first { it.reply()?.text == "a" }
        // Seqs 2-3 never reach this socket.
        publish("message.delta", """{"text":"b"}""")
        publish("message.delta", """{"text":"c"}""")
        socket.push(delta("d"))

        assertEquals("abcd", chat.state.first { it.reply()?.text == "abcd" }.reply()?.text)
        val ask = socket.sinceCalls().single()
        assertEquals(sessionEpoch, ask.param("replay_epoch"))
        assertEquals("1", ask.param("last_seen"))
    }

    @Test
    fun theFirstEventsAfterOpeningCountFromTheResumesLastSequence() = runTest {
        // Seqs 1-2 were published before this phone opened the chat; resume names 2 as its last_sequence.
        publish("message.start")
        publish("message.delta", """{"text":"x"}""")
        answer = { _, method, params ->
            when (method) {
                "session.resume" -> resume()
                "session.events.since" -> since(params)
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, sid, "Chat", connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == sid && it.historyLoaded }
        val socket = transports.last()
        // Seq 3 is lost on the way: seq 4 follows a gap, which only the resume's cursor shows.
        publish("message.delta", """{"text":"y"}""")
        socket.push(delta("z"))

        socket.awaitSent { it.isCall("session.events.since") }
        val ask = socket.sinceCalls().single()
        assertEquals("2", ask.param("last_seen"))
        assertEquals(sessionEpoch, ask.param("replay_epoch"))
    }

    @Test
    fun anEventInANewSessionEpochIsAppliedNotDroppedAsSeenBefore() = runTest {
        answer = { _, method, params ->
            when (method) {
                "session.resume" -> resume(running = false)
                "session.events.since" -> since(params)
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, sid, "Chat", connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == sid && it.historyLoaded }
        val socket = transports.last()
        socket.push(publish("message.start"))
        socket.push(delta("old"))
        socket.push(publish("message.complete", """{"text":"old","status":"complete"}"""))
        chat.state.first { !it.running && it.reply()?.text == "old" }
        // Evicted from the shared ring: the next turn's seqs count from 1 again, in a new epoch.
        startAnew("sess-epoch-2")
        socket.push(publish("message.start"))
        socket.push(delta("new"))

        assertEquals("new", chat.state.first { it.reply()?.text == "new" }.reply()?.text)
        assertTrue(socket.sinceCalls().isEmpty())
    }

    @Test
    fun aChatCreatedOnTheAuthorityCatchesUpWithTheEpochItWasCreatedIn() = runTest {
        answer = { transport, method, params ->
            when (method) {
                // session_controls.py create ends in resume: the same snapshot keys.
                "session.create" -> resume(running = false)
                "prompt.submit" -> """{"status":"streaming"}"""
                "session.resume" -> resume()
                "session.events.since" -> since(params).also { transport.push(delta("!")) }
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(gateway.client), backgroundScope)
        chat.start()
        assertTrue(chat.send("hi"))
        val first = transports.last()
        first.push(publish("message.start"))
        first.push(delta("Hel"))
        chat.state.first { it.reply()?.text == "Hel" }
        first.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        publish("message.delta", """{"text":"lo"}""")

        assertEquals("Hello!", chat.state.first { it.reply()?.text == "Hello!" }.reply()?.text)
        val ask = transports.last().sinceCalls().first()
        assertEquals(sessionEpoch, ask.param("replay_epoch"))
        assertEquals("2", ask.param("last_seen"))
    }
}
