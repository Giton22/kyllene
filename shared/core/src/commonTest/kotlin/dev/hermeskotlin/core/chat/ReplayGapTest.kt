package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The session authority (hermes-agent 0.22.1, 0.22.3+, `gateway.ready` `session_authority: true`) drops a viewer
 * whose fanout backlog overflowed (`tui_gateway/transport.py` FanoutTransport.write) and sends it one
 * `session.replay_gap` (`gateway/session_events.py` SessionEvents.publish `overflow`). The socket stays open, but
 * `_retire_overflowed` (`gateway/session_authority.py`) ended its subscription: nothing more of the session reaches
 * it until it calls `session.resume` again, which re-attaches it with a fresh snapshot (as Desktop's
 * `handleReplayGapEvent` and the TUI's `case 'session.replay_gap'` do). The legacy sidecar sends no such frame.
 */
class ReplayGapTest {

    private val url = FakeGateway.URL

    /** As the authority's ws.py sends it: the process `replay_epoch` and `session_authority` (no heartbeat, for the test clock). */
    private val authorityReady =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"proc-1","session_authority":true}}}"""

    /** The legacy sidecar's: no `session_authority`. */
    private val sidecarReady =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"proc-1"}}}"""

    /** The frame `SessionEvents.publish`'s `overflow` builds, field for field (no `seq`, no `replay_epoch` beside the payload). */
    private fun replayGap(sessionId: String, epoch: String, latestSeq: Long) =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"session.replay_gap","session_id":"$sessionId",""" +
            """"payload":{"replay_epoch":"$epoch","latest_seq":$latestSeq}}}"""

    /** A `session.resume` reply as `session_controls.py` builds it on the authority: no `inflight`, the session's own epoch. */
    private fun authorityResume(lastSequence: Long, running: Boolean) = """{
        "session_id":"stored-1","stored_session_id":"stored-1","messages":[],"messages_omitted":true,"message_count":2,
        "running":$running,"authority_epoch":1,"replay_epoch":"sess-e1","last_sequence":$lastSequence,"subscription_id":"sub-1",
        "revision":1,"execution_generation":1,"pending":[],"prompts":[],"info":{},
        "pending_connection":null,"todo_state":null,"open_requests":[]
    }"""

    /**
     * A gateway that, like the authority, only delivers a session's events to a subscribed socket: subscribed by
     * `session.resume`, unsubscribed when it sends the gap. [publish] stands for `SessionEvents.publish`.
     */
    private class Authority(val gw: FakeGateway) {
        var subscribed = false
        var resumes = 0
        var seq = 3L
        var running = true
        var history = """{"session_id":"stored-1","messages":[]}"""

        /** Runs once as the transcript is read: frames published then reach a chat that is still attaching. */
        var onHistory: (() -> Unit)? = null

        fun publish(type: String, payload: String) {
            seq++
            if (subscribed) gw.socket.push(event(type, "stored-1", payload, seq))
        }

        /** The overflow: the subscription ends and the gap goes out on the healthy socket. */
        fun overflow(frame: String) {
            subscribed = false
            gw.socket.push(frame)
        }
    }

    private suspend fun FakeGateway.authority(ready: String = authorityReady): Authority {
        val authority = Authority(this)
        this.ready = { ready }
        answer = { call ->
            when (call.method) {
                "session.resume" -> {
                    authority.resumes++
                    authority.subscribed = true
                    authorityResume(authority.seq, running = authority.running)
                }
                else -> "{}"
            }
        }
        http = {
            authority.onHistory?.let { hook ->
                authority.onHistory = null
                hook()
                // The read takes a while, so the chat takes those frames in before it's attached again.
                delay(200)
            }
            json(authority.history)
        }
        start(awaitConnected = true)
        return authority
    }

    private fun FakeGateway.chat(scope: kotlinx.coroutines.CoroutineScope) =
        ChatSession(url, "stored-1", "DM", connection, SessionsApi(client), scope)

    private fun FakeGateway.resumes() = socket.sent.value.count { it.isResume() }

    @Test
    fun aReplayGapOnTheAuthorityResumesTheSession() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }
        assertEquals(1, authority.resumes)

        authority.publish("message.delta", """{"text":"lo"}""")
        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))

        // Nothing else happens on the socket: the only way back onto the stream is a new session.resume.
        val resumed = withTimeoutOrNull(30.seconds) { gw.socket.sent.first { list -> list.count { it.isResume() } >= 2 } }
        assertNotNull(
            resumed,
            "no session.resume after session.replay_gap: the authority ended this socket's subscription, so the chat gets no more events",
        )
        // A snapshot, as Desktop takes one: the hole is longer than session.events.since keeps.
        assertTrue(gw.socket.sent.value.none { (it["method"] as? JsonPrimitive)?.content == "session.events.since" })
    }

    @Test
    fun aTurnThatEndsAfterAReplayGapStopsRunning() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))
        // The turn goes on and ends in the hole: this socket isn't subscribed, so its frames go nowhere.
        authority.publish("message.delta", """{"text":" world"}""")
        authority.publish("message.complete", """{"text":"Hello world","status":"complete"}""")
        authority.running = false
        authority.history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"Hi","timestamp":1},
            {"id":2,"role":"assistant","content":"Hello world","timestamp":2}]}"""

        val stopped = withTimeoutOrNull(30.seconds) { chat.state.first { !it.running } }
        assertTrue(
            stopped != null,
            "the chat still shows the turn running after session.replay_gap; session.resume calls: ${authority.resumes}",
        )
        // The reply that finished in the hole shows from the transcript the resume reloaded.
        chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && it.text == "Hello world" } }
    }

    @Test
    fun aTurnStillRunningAfterAReplayGapStreamsOnAndCompletes() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))
        authority.publish("message.delta", """{"text":"lost in the hole"}""")
        assertNotNull(
            withTimeoutOrNull(30.seconds) { gw.socket.sent.first { list -> list.count { it.isResume() } >= 2 } },
            "no session.resume after session.replay_gap",
        )
        chat.state.first { it.attachment is Attachment.Attached && it.running }

        // Subscribed again: the rest of the turn arrives.
        authority.publish("message.delta", """{"text":"After the gap."}""")
        chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && "After the gap." in it.text } }
        authority.publish("message.complete", """{"text":"After the gap.","status":"complete"}""")
        val stopped = withTimeoutOrNull(30.seconds) { chat.state.first { !it.running } }
        assertNotNull(stopped, "message.complete after the re-subscribe didn't end the turn")
    }

    @Test
    fun aTurnThatEndsWhileTheGapResumeReadsTheTranscriptStopsRunning() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        // Subscribed again by the resume, the socket gets the turn's end while the chat still reads the transcript.
        authority.onHistory = {
            authority.publish("message.complete", """{"text":"Hello world","status":"complete"}""")
            authority.running = false
        }
        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))

        val stopped = withTimeoutOrNull(30.seconds) { chat.state.first { it.attachment is Attachment.Attached && !it.running } }
        assertNotNull(stopped, "the message.complete that came while the chat attached again was dropped; it still shows Running")
    }

    @Test
    fun aGapWhileTheGapResumeReadsTheTranscriptResumesAgain() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        // The new subscription overflows too before the chat is attached again.
        authority.onHistory = { authority.overflow(replayGap("stored-1", "sess-e1", authority.seq)) }
        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))

        assertNotNull(
            withTimeoutOrNull(30.seconds) { gw.socket.sent.first { list -> list.count { it.isResume() } >= 3 } },
            "the second session.replay_gap, which came while the chat attached again, took no resume",
        )
        chat.state.first { it.attachment is Attachment.Attached }
        authority.publish("message.delta", """{"text":"Back again."}""")
        chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && "Back again." in it.text } }
    }

    @Test
    fun gapsBeforeTheResumeStartsTakeOneResume()= runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))
        gw.socket.push(replayGap("stored-1", "sess-e1", authority.seq))
        assertNotNull(
            withTimeoutOrNull(30.seconds) { gw.socket.sent.first { list -> list.count { it.isResume() } >= 2 } },
            "no session.resume after session.replay_gap",
        )
        chat.state.first { it.attachment is Attachment.Attached }
        assertEquals(2, gw.resumes(), "the first attach and one resume for the gaps")
    }

    @Test
    fun aSendAfterAFailedGapResumeAttachesAgain() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val answer = gw.answer
        gw.answer = { call -> if (call.method == "session.resume" && authority.resumes == 1) { authority.resumes++; "error:5000" } else answer(call) }
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        authority.overflow(replayGap("stored-1", "sess-e1", authority.seq))
        chat.state.first { it.attachment is Attachment.Failed }
        authority.running = false
        chat.send("hi")
        // Not sent on a socket that no longer follows the chat: it resumes first, so the reply reaches it.
        val sent = gw.socket.sent.value.map { (it["method"] as? JsonPrimitive)?.content }
        assertEquals(3, sent.count { it == "session.resume" }, "sent: $sent")
        assertTrue(sent.lastIndexOf("session.resume") < sent.indexOf("prompt.submit"), "sent: $sent")
    }

    @Test
    fun aGapForAnotherSessionDoesNotResume() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val authority = gw.authority()
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }

        gw.socket.push(replayGap("other-1", "sess-e9", 40))
        authority.publish("message.delta", """{"text":"Still here."}""")
        chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && "Still here." in it.text } }
        assertEquals(1, gw.resumes())
    }

    @Test
    fun aReplayGapOnTheLegacySidecarIsIgnored() = runTest(timeout = 5.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false)
        val sidecar = gw.authority(ready = sidecarReady)
        val chat = gw.chat(backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.running }
        val before = chat.state.value

        // The sidecar closes the socket on overflow and never sends this; a stray one changes nothing.
        gw.socket.push(replayGap("stored-1", "sess-e1", sidecar.seq))
        sidecar.publish("message.delta", """{"text":"Still streaming."}""")
        chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && "Still streaming." in it.text } }
        assertEquals(1, gw.resumes())
        assertEquals(before.attachment, chat.state.value.attachment)
        assertTrue(chat.state.value.running)
    }

    private fun JsonObject.isResume() = (this["method"] as? JsonPrimitive)?.content == "session.resume"
}
