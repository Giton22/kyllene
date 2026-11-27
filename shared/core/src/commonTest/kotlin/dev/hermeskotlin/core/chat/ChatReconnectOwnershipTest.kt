package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Socket replacement while a chat has replay, a send, or a transcript read in progress. */
class ChatReconnectOwnershipTest {
    private val partial = """{"session_id":"new","running":true,"inflight":{"assistant":"Partial"}}"""
    private val transcript = """{"session_id":"stored","messages":[]}"""
    private var beforeRead: suspend () -> Unit = {}

    private fun fixture(scope: CoroutineScope, authority: Boolean = false) = FakeGateway(scope).apply {
        val backend = if (authority) ""","session_authority":true""" else ""
        ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch"$backend}}}""" }
        http = {
            beforeRead()
            json(transcript)
        }
    }

    private fun FakeGateway.chat(scope: CoroutineScope, stored: String? = "stored") =
        ChatSession(FakeGateway.URL, stored, "Chat", connection, SessionsApi(client), scope).also { it.start() }

    private fun blockNextRead(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeRead = {
            beforeRead = {}
            entered.complete(Unit)
            release.await()
        }
        return entered to release
    }

    private class GateDispatcher(private val scheduler: CoroutineDispatcher) : CoroutineDispatcher() {
        var pauseAt = 0
        private var held: Pair<CoroutineContext, Runnable>? = null
        val paused get() = held != null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (pauseAt > 0 && --pauseAt == 0) {
                check(held == null)
                held = context to block
            } else scheduler.dispatch(context, block)
        }

        fun release() {
            val (context, block) = checkNotNull(held)
            held = null
            scheduler.dispatch(context, block)
        }
    }

    @Test
    fun oldCatchUpDoesNotPoisonFreshCreate() = runTest(timeout = 8.seconds) {
        val gateway = fixture(backgroundScope)
        var creates = 0
        var sends = 0
        gateway.answer = { rpc ->
            when (rpc.method) {
                "session.create" -> """{"session_id":"runtime-${++creates}"}"""
                "prompt.submit" -> if (++sends == 1) rpc.error(5000, "Fixture rejects first send") else """{"status":"streaming"}"""
                "session.events.since" -> null
                else -> "{}"
            }
        }
        gateway.start(awaitConnected = true)
        val chat = gateway.chat(backgroundScope, null)
        assertFalse(chat.send("Rejected draft"))
        assertEquals(null, chat.state.value.storedSessionId)
        val old = gateway.socket
        old.push(event("session.info", "runtime-1", "{}", 1))
        old.push(event("session.info", "runtime-1", "{}", 3))
        old.sent.first { gateway.sent("session.events.since").isNotEmpty() }
        gateway.connection.stop()
        chat.state.first { it.attachment is Attachment.Detached }
        runCurrent()
        gateway.start(awaitConnected = true)
        assertTrue(chat.send("New prompt"))
        assertEquals(2, creates)
        gateway.socket.push(event("message.delta", "runtime-2", """{"text":"New reply"}""", 1))
        gateway.socket.push(event("message.complete", "runtime-2", """{"text":"New reply","status":"complete"}""", 2))
        runCurrent()
        val state = chat.state.value
        assertTrue(state.messages.any { it is ChatMessage.Assistant && it.text == "New reply" },
            "New runtime ${state.runtimeSessionId}, running=${state.running}, did not show its own events")
        assertFalse(state.running)
    }

    @Test
    fun queuedCatchUpDoesNotPoisonFreshCreate() = runTest(timeout = 8.seconds) {
        val gateway = fixture(backgroundScope)
        var creates = 0
        var sends = 0
        gateway.answer = { rpc ->
            when (rpc.method) {
                "session.create" -> """{"session_id":"runtime-${++creates}"}"""
                "prompt.submit" -> if (++sends == 1) rpc.error(5000, "Fixture rejects first send") else """{"status":"streaming"}"""
                "session.events.since" -> null
                else -> "{}"
            }
        }
        gateway.start(awaitConnected = true)
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val chatScope = CoroutineScope(backgroundScope.coroutineContext + gate)
        val chat = gateway.chat(chatScope, null)
        assertFalse(chat.send("Rejected draft"))
        assertEquals(null, chat.state.value.storedSessionId)
        val old = gateway.socket
        old.push(event("session.info", "runtime-1", "{}", 1))
        runCurrent()
        gate.pauseAt = 2
        old.push(event("session.info", "runtime-1", "{}", 3))
        runCurrent()
        assertTrue(gate.paused, "catch-up coroutine was not held")
        assertTrue(gateway.sent("session.events.since").isEmpty())
        gateway.connection.stop()
        chat.state.first { it.attachment is Attachment.Detached }
        gate.release()
        runCurrent()
        gateway.start(awaitConnected = true)
        assertTrue(chat.send("New prompt"))
        assertEquals(2, creates)
        gateway.socket.push(event("message.delta", "runtime-2", """{"text":"New reply"}""", 1))
        gateway.socket.push(event("message.complete", "runtime-2", """{"text":"New reply","status":"complete"}""", 2))
        runCurrent()
        val state = chat.state.value
        assertTrue(state.messages.any { it is ChatMessage.Assistant && it.text == "New reply" },
            "New runtime ${state.runtimeSessionId}, running=${state.running}, did not show its own events")
        assertFalse(state.running)
    }

    @Test
    fun manualRetryDoesNotInheritAbandonedGapScheduling() = runTest(timeout = 8.seconds) {
        val gateway = fixture(backgroundScope, authority = true)
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val chatScope = CoroutineScope(backgroundScope.coroutineContext + gate)
        var resumes = 0
        gateway.answer = { rpc ->
            if (rpc.method != "session.resume") "{}"
            else if (++resumes == 2) rpc.error(5000, "Fixture rejects first reconnect")
            else """{"session_id":"stored","stored_session_id":"stored","running":false,"replay_epoch":"same","last_sequence":0}"""
        }
        gateway.start(awaitConnected = true)
        val chat = gateway.chat(chatScope)
        chat.state.first { it.runtimeSessionId == "stored" && it.historyRead }
        runCurrent()
        gate.pauseAt = 2
        gateway.socket.push(event("session.replay_gap", "stored", """{"replay_epoch":"same","latest_seq":1}"""))
        runCurrent()
        assertTrue(gate.paused, "old gap recovery launch was not held")
        assertEquals(1, resumes)
        gateway.socket.serverClose(1006)
        chat.state.first { it.attachment is Attachment.Failed }
        assertEquals(2, resumes)
        gate.release()
        runCurrent()
        chat.retry()
        chat.state.first { it.runtimeSessionId == "stored" }
        assertEquals(3, resumes)
        gateway.socket.push(event("session.replay_gap", "stored", """{"replay_epoch":"same","latest_seq":2}"""))
        runCurrent()
        assertEquals(4, resumes, "the replacement socket must react to its own gap")
    }

    @Test
    fun staleSendCannotOverwriteReplacementAttachment() = runTest(timeout = 8.seconds) {
        val gateway = fixture(backgroundScope)
        var resumes = 0
        gateway.answer = { call ->
            when (call.method) {
                "session.resume" -> when (++resumes) {
                    1 -> """{"session_id":"old","running":false}"""
                    2 -> call.error(5000, "Fixture rejects first reconnect")
                    3 -> partial
                    else -> null
                }
                else -> "{}"
            }
        }
        gateway.start(awaitConnected = true)
        val chatScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val chat = gateway.chat(chatScope)
        chat.state.first { it.runtimeSessionId == "old" && it.historyRead }
        gateway.socket.serverClose(1006)
        chat.state.first { it.attachment is Attachment.Failed }
        val (entered, release) = blockNextRead()
        val send = async { chat.send("Prompt never sent") }
        entered.await()
        gateway.socket.serverClose(1006)
        chat.state.first { it.attachment is Attachment.Detached }
        gateway.connection.state.first { it is ConnectionState.Connected }
        release.complete(Unit)
        assertFalse(send.await())
        assertTrue(gateway.sent("prompt.submit").isEmpty())
        val state = chat.state.value
        assertEquals("The connection changed before the message could be sent.", state.error)
        assertTrue(state.attachment is Attachment.Attaching, "old send changed replacement attachment to ${state.attachment}")
    }

    private suspend fun replayGapDuringHistory(scope: kotlinx.coroutines.test.TestScope, authority: Boolean) = with(scope) {
        val gateway = fixture(backgroundScope, authority)
        val firstRuntime = if (authority) "stored" else "old"
        val nextRuntime = if (authority) "stored" else "new"
        var resumes = 0
        gateway.answer = { rpc ->
            if (rpc.method != "session.resume") "{}"
            else when (++resumes) {
                1 -> """{"session_id":"$firstRuntime","running":false,"replay_epoch":"first","last_sequence":0}"""
                2 -> """{"session_id":"$nextRuntime","running":true,"replay_epoch":"second","last_sequence":0}"""
                else -> """{"session_id":"$nextRuntime","running":false,"replay_epoch":"second","last_sequence":1}"""
            }
        }
        gateway.start(awaitConnected = true)
        val chat = gateway.chat(backgroundScope)
        chat.state.first { it.runtimeSessionId == firstRuntime && it.historyRead }
        val (entered, release) = blockNextRead()
        gateway.socket.serverClose(1006)
        entered.await()
        gateway.socket.push(event("session.replay_gap", nextRuntime, """{"replay_epoch":"second","latest_seq":1}"""))
        if (!authority) gateway.socket.push(event("message.delta", nextRuntime, """{"text":"Legacy still streams"}""", 1))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(if (authority) 3 else 2, resumes)
        if (authority) assertFalse(chat.state.value.running)
        else assertTrue(chat.state.value.messages.any { it is ChatMessage.Assistant && it.text == "Legacy still streams" })
    }

    @Test
    fun authorityGapBufferedDuringHistoryResubscribes() = runTest(timeout = 8.seconds) {
        replayGapDuringHistory(this, authority = true)
    }

    @Test
    fun legacyGapFrameBufferedDuringHistoryLeavesFollowingDeltasAlone() = runTest(timeout = 8.seconds) {
        replayGapDuringHistory(this, authority = false)
    }
}
