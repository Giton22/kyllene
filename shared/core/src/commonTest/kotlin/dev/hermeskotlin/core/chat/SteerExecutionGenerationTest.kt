package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `session.steer` on the session authority (gateway/session_busy_controls.py `correct`): it takes the running turn's
 * `execution_generation`, refuses `invalid_params` without one and `stale_generation` for any other turn's (#480).
 */
class SteerExecutionGenerationTest {

    private val url = FakeGateway.URL

    /** A gateway-hosted listener whose running turn is [running], as `correct` and `check_approval_generation` judge it. */
    private class Authority {
        var running = 1L
        var turnRunning = true
        var resumeGeneration: (Int) -> Long? = { running }
        var steerReply: (Long) -> String = { """{"status":"queued","text":"use tabs","execution_generation":$it,"authority_epoch":3}""" }
        var resumes = 0
    }

    private suspend fun TestScope.authorityChat(authority: Authority): Pair<ChatSession, FakeGateway> {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> {
                        val generation = authority.resumeGeneration(++authority.resumes)
                        """{"session_id":"rt1","running":${authority.turnRunning}""" +
                            generation?.let { ""","execution_generation":$it""" }.orEmpty() + "}"
                    }
                    "session.steer" -> {
                        val generation = (call.params["execution_generation"] as? JsonPrimitive)?.longOrNull
                        when {
                            call.params.keys != setOf("session_id", "text", "execution_generation") || generation == null ->
                                call.error(4001, "invalid_params", "invalid_params")
                            generation != authority.running || !authority.turnRunning ->
                                call.error(4001, "stale_generation", "stale_generation")
                            else -> authority.steerReply(generation)
                        }
                    }
                    "prompt.submit" -> """{"status":"queued"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Busy", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.running }
        return chat to gw
    }

    /** A turn event as the session authority sends it: the generation beside the payload, not in it. */
    private fun stamped(type: String, generation: Long, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt1","execution_generation":$generation,"payload":$payload}}"""

    private fun JsonObject.generation() = this["params"]?.jsonObject?.get("execution_generation")?.jsonPrimitive?.longOrNull

    @Test
    fun steerNamesTheGenerationTheTurnsEventsCarry() = runTest(timeout = 5.seconds) {
        val authority = Authority().apply { running = 3 }
        val (chat, gw) = authorityChat(authority)
        // The next turn starts: its events carry generation 4 beside the payload only.
        authority.running = 4
        gw.socket.push(stamped("message.start", 4))
        gw.socket.push(stamped("message.delta", 4, """{"text":"Working"}"""))
        chat.state.first { s -> (s.messages.lastOrNull() as? ChatMessage.Assistant)?.text == "Working" }
        // A late event of an older turn doesn't take it back.
        gw.socket.push(stamped("session.info", 3, """{"running":true}"""))

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        val steer = gw.sent("session.steer").single()
        assertEquals(4L, steer.generation())
        assertEquals(1, authority.resumes)
        assertTrue(gw.sent("prompt.submit").isEmpty())
        assertNull(chat.state.value.error)
        assertIs<ChatMessage.User>(chat.state.value.messages.single { (it as? ChatMessage.User)?.text == "use tabs" }).also { assertEquals(false, it.pending) }
    }

    @Test
    fun aStaleGenerationIsReadAgainAndTheSteerGoesOnce() = runTest(timeout = 5.seconds) {
        // Attached at generation 1; the turn now running is 2, and no event of it arrived yet.
        val authority = Authority().apply { resumeGeneration = { if (it == 1) 1L else 2L } }
        val (chat, gw) = authorityChat(authority)
        authority.running = 2

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        // The refusal names no current generation, so session.resume reads it; the steer goes once more with it.
        assertEquals(listOf(1L, 2L), gw.sent("session.steer").map { it.generation() })
        assertEquals(2, authority.resumes)
        assertTrue(gw.sent("prompt.submit").isEmpty())
        assertNull(chat.state.value.error)
    }

    @Test
    fun withoutAKnownGenerationTheTurnIsReadFirst() = runTest(timeout = 5.seconds) {
        val authority = Authority().apply { resumeGeneration = { if (it == 1) null else 1L } }
        val (chat, gw) = authorityChat(authority)

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        assertEquals(listOf(1L), gw.sent("session.steer").map { it.generation() })
        assertEquals(2, authority.resumes)
    }

    @Test
    fun aSteerForATurnThatEndedGoesAsTheNextPrompt() = runTest(timeout = 5.seconds) {
        val authority = Authority()
        val (chat, gw) = authorityChat(authority)
        // The turn ended on the gateway before its completion reached the phone.
        authority.turnRunning = false

        chat.steer("use tabs")

        // Refused before any agent saw it, and the turn read again isn't running: it goes as a prompt, once.
        assertEquals(1, gw.sent("session.steer").size)
        assertEquals(2, authority.resumes)
        val submit = gw.sent("prompt.submit").single()
        assertEquals("use tabs", submit["params"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun aPlainRejectionIsNotSentAgain() = runTest(timeout = 5.seconds) {
        val authority = Authority().apply {
            steerReply = { """{"status":"rejected","text":"use tabs","execution_generation":$it,"authority_epoch":3}""" }
        }
        val (chat, gw) = authorityChat(authority)

        assertEquals(SendOutcome.Unsettled, chat.steer("use tabs"))

        // The agent may have read it: no second copy goes, and the bubble stays to check.
        assertTrue(gw.sent("prompt.submit").isEmpty())
        assertEquals(1, gw.sent("session.steer").size)
        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.single { (it as? ChatMessage.User)?.text == "use tabs" })
        assertEquals(SendCheck.Unknown, bubble.check)
        assertEquals("Hermes may have read your steer, so it wasn't sent again. Check the reply before you send it again.", chat.state.value.error)
    }

    @Test
    fun aRejectionNoAgentSawGoesAsTheNextPrompt() = runTest(timeout = 5.seconds) {
        val authority = Authority().apply {
            steerReply = { """{"status":"rejected","text":"use tabs","execution_generation":$it,"authority_epoch":3,"delivered":false}""" }
        }
        val (chat, gw) = authorityChat(authority)

        chat.steer("use tabs")

        val submit = gw.sent("prompt.submit").single()
        assertEquals("use tabs", submit["params"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertNull(chat.state.value.error)
    }
}
