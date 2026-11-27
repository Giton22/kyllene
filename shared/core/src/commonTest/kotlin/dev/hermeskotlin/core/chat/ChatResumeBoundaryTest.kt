package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChatResumeBoundaryTest {
    private val end = """{"text":"Final answer","status":"complete"}"""
    private var transcript = """{"session_id":"stored","messages":[]}"""
    private var beforeRead: suspend () -> Unit = {}

    private fun fixture(scope: CoroutineScope, authority: Boolean = false) = FakeGateway(scope).apply {
        val backend = if (authority) ""","session_authority":true""" else ""
        ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch"$backend}}}""" }
        http = { beforeRead(); json(transcript) }
    }

    private fun FakeGateway.chat(scope: CoroutineScope, stored: String? = "stored") =
        ChatSession(FakeGateway.URL, stored, "Chat", connection, SessionsApi(client), scope).also { it.start() }

    private fun blockNextRead(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeRead = { beforeRead = {}; entered.complete(Unit); release.await() }
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

    private suspend fun completionAtResponseBoundary(
        scope: TestScope,
        beforeReply: Boolean,
        pauseResume: Boolean = false,
        newerTurn: Boolean = false,
        settleDelayed: Boolean = false,
        acknowledgedKey: String = "stored",
        watermark: Long? = null,
        committedReply: Boolean = false,
        overflow: Boolean = false,
        ownOverflow: Boolean = false,
        epochChanged: Boolean = false,
    ) = with(scope) {
        val gateway = fixture(backgroundScope, authority = watermark != null)
        val gate = GateDispatcher(StandardTestDispatcher(testScheduler))
        val chatScope = CoroutineScope(backgroundScope.coroutineContext + gate)
        var resumes = 0
        var statusReads = 0
        gateway.answer = { call ->
            when (call.method) {
                "session.resume" -> when (++resumes) {
                    1 -> """{"session_id":"old","running":false}"""
                    3 -> """{"session_id":"new","running":false,"replay_epoch":"fresh","last_sequence":1}"""
                    else -> {
                        val metadata = if (watermark == null) "" else ""","last_sequence":$watermark,"replay_epoch":"fresh""""
                        val started = if (committedReply) ""","turn_started_at":100""" else ""
                        val snapshot = """{"session_id":"new","session_key":"$acknowledgedKey","running":true,"inflight":{"assistant":"Partial"}$metadata$started}"""
                        val payload = if (committedReply) end.dropLast(1) + ""","persisted_turn":{"final_assistant_row_id":2}}""" else end
                        val completion = event("message.complete", "new", payload, 1).let {
                            val eventEpoch = if (epochChanged) "new-epoch" else "fresh"
                            if (watermark == null) it else it.dropLast(2) + ""","replay_epoch":"$eventEpoch"}}"""
                        }
                        if (pauseResume) gate.pauseAt = 1
                        val frames = buildList {
                            if (!beforeReply) add(call.reply(snapshot))
                            add(completion)
                            if (overflow) repeat(64) { add(event("message.delta", "other", """{"text":"Other chat"}""")) }
                            if (ownOverflow) repeat(64) { add(event("message.delta", "new", """{"text":"More"}""")) }
                            if (beforeReply) add(call.reply(snapshot))
                        }
                        call.socket.push(frames.joinToString("\n"))
                        null
                    }
                }
                "session.active_list" -> {
                    statusReads++
                    val status = if (newerTurn || (settleDelayed && statusReads == 1)) "working" else "idle"
                    """{"sessions":[{"id":"new","session_key":"$acknowledgedKey","status":"$status"}]}"""
                }
                else -> "{}"
            }
        }
        gateway.start(awaitConnected = true)
        val chat = gateway.chat(chatScope)
        chat.state.first { it.runtimeSessionId == "old" && it.historyRead }
        val (entered, release) = blockNextRead()
        transcript = """{"session_id":"stored","messages":[{"id":1,"role":"user","content":"Prompt"},{"id":2,"role":"assistant","timestamp":110,"content":"Final answer"}]}"""
        gateway.socket.serverClose(1006)
        if (pauseResume) {
            testScheduler.advanceTimeBy(2_000)
            assertTrue(gate.paused, "resume continuation was not held")
            assertFalse(entered.isCompleted, "resume already entered history")
            gate.release()
        }
        entered.await()
        runCurrent()
        release.complete(Unit)
        chat.state.first { it.runtimeSessionId == "new" }
        runCurrent()
        val state = chat.state.value
        if (settleDelayed) assertEquals(2, statusReads)
        if (watermark != null || committedReply) assertEquals(0, statusReads)
        if (epochChanged || ownOverflow) assertEquals(3, resumes)
        if (overflow) assertEquals(2, resumes, "Another chat's frames forced a second resume")
        val replies = state.messages.filterIsInstance<ChatMessage.Assistant>().map { it.text }
        if (newerTurn || watermark == 1L) {
            assertTrue(state.running)
            assertTrue("Partial" in replies, "The current turn's streamed text was replaced: $replies")
        } else {
            assertFalse(state.running, "Completion lost at resume response boundary; replies=$replies")
            assertEquals(listOf("Final answer"), replies)
        }
    }

    @Test
    fun completionBeforeResumeReplyIsNotLost() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true)
    }

    @Test
    fun completionImmediatelyAfterReplyBeforeResumeContinuationIsNotLost() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = false, pauseResume = true)
    }

    @Test
    fun earlyCompletionCannotEndANewerLegacyTurn() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, newerTurn = true)
    }
    @Test
    fun earlyLegacyCompletionSurvivesTheGatewaySettlingAndFollowingATip() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, settleDelayed = true, acknowledgedKey = "tip")
    }

    @Test
    fun earlyAuthorityCompletionBeyondTheSnapshotWatermarkIsApplied() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, watermark = 0)
    }

    @Test
    fun earlyAuthorityCompletionAlreadyInTheSnapshotDoesNotEndANewerTurn() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, watermark = 1)
    }

    @Test
    fun aCommittedLegacyReplyConfirmsTheEndWithoutPolling() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, committedReply = true)
    }

    @Test
    fun unrelatedAuthorityTrafficCannotSilentlyEvictTheCompletion() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, watermark = 0, overflow = true)
    }

    @Test
    fun anEarlyAuthorityEpochChangeRecoversFromAFreshSnapshot() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, watermark = 0, epochChanged = true)
    }

    @Test
    fun earlyAuthorityFramesOfThisChatBeyondTheLimitRecoverFromAFreshSnapshot() = runTest(timeout = 8.seconds) {
        completionAtResponseBoundary(this, beforeReply = true, watermark = 0, ownOverflow = true)
    }

    /** Picked up from the last seq on the legacy gateway: the catch-up places the early completion, with no status polls. */
    @Test
    fun aResumableLegacyReconnectKeepsTheEarlyCompletionWithoutPolling() = runTest(timeout = 8.seconds) {
        resumableLegacyReconnect(this, ringAnswers = false)
    }

    /** The ring replays the same completion: applied once, the early copy dropped as already seen. */
    @Test
    fun aReplayedEarlyLegacyCompletionIsAppliedOnce() = runTest(timeout = 8.seconds) {
        resumableLegacyReconnect(this, ringAnswers = true)
    }

    private suspend fun resumableLegacyReconnect(scope: TestScope, ringAnswers: Boolean) = with(scope) {
        val gateway = fixture(backgroundScope)
        var resumes = 0
        var statusReads = 0
        gateway.answer = { call ->
            when (call.method) {
                "session.resume" -> if (++resumes == 1) """{"session_id":"rt","running":true,"inflight":{"assistant":"Partial"}}""" else {
                    val snapshot = """{"session_id":"rt","running":true,"inflight":{"assistant":"Partial more"}}"""
                    call.socket.push(event("message.complete", "rt", end, 2) + "\n" + call.reply(snapshot))
                    null
                }
                // When the ring can't answer, only the frame taken at the boundary can end the turn.
                "session.events.since" -> if (!ringAnswers) "error:4040" else
                    """{"latest_seq":2,"truncated":false,"events":[{"session_id":"rt","seq":2,"type":"message.complete","payload":$end}]}"""
                "session.active_list" -> {
                    statusReads++
                    """{"sessions":[{"id":"rt","session_key":"stored","status":"idle"}]}"""
                }
                else -> "{}"
            }
        }
        gateway.start(awaitConnected = true)
        val chat = gateway.chat(backgroundScope)
        chat.state.first { it.runtimeSessionId == "rt" && it.historyRead }
        gateway.socket.push(event("message.delta", "rt", """{"text":" more"}""", 1))
        runCurrent()
        transcript = """{"session_id":"stored","messages":[{"id":1,"role":"user","content":"Prompt"},{"id":2,"role":"assistant","timestamp":110,"content":"Final answer"}]}"""
        var reads = 0
        beforeRead = { reads++ }
        gateway.socket.serverClose(1006)
        chat.state.first { resumes == 2 && !it.running }
        runCurrent()
        assertEquals(0, statusReads)
        // The reconnect reads once either way; only a ring miss reads the transcript again.
        assertEquals(if (ringAnswers) 1 else 2, reads)
        assertEquals(listOf("Final answer"), chat.state.value.messages.filterIsInstance<ChatMessage.Assistant>().map { it.text })
    }
}
