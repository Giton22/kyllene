package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ProjectCwdExplicitTest {

    private val url = FakeGateway.URL

    @Test
    fun newChatInProjectSendsCwdExplicitTrue() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.create" -> """{"session_id":"rt-proj","stored_session_id":"stored-proj","info":{"cwd":"/Users/me/Projects/demo"}}"""
                    "prompt.submit" -> """{"status":"streaming"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-proj","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(
            url,
            initialStoredId = null,
            initialTitle = "New chat",
            connection = gw.connection,
            sessions = SessionsApi(gw.client),
            scope = backgroundScope,
            profile = "orchestrator",
            cwd = "/Users/me/Projects/demo",
        )
        chat.start()

        assertEquals(SendOutcome.Sent, chat.submit("Run pwd"))

        val create = gw.socket.sent.value.first { it.isCall("session.create") }
        val params = create["params"]!!.jsonObject
        assertEquals("/Users/me/Projects/demo", params["cwd"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, params["cwd_explicit"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("orchestrator", params["profile"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun newChatWithoutProjectDoesNotSendCwdExplicit() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.create" -> """{"session_id":"rt-home","stored_session_id":"stored-home"}"""
                    "prompt.submit" -> """{"status":"streaming"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-home","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(
            url,
            initialStoredId = null,
            initialTitle = "New chat",
            connection = gw.connection,
            sessions = SessionsApi(gw.client),
            scope = backgroundScope,
            profile = "orchestrator",
            cwd = null,
        )
        chat.start()

        assertEquals(SendOutcome.Sent, chat.submit("Hello"))

        val create = gw.socket.sent.value.first { it.isCall("session.create") }
        val params = create["params"]!!.jsonObject
        assertNull(params["cwd"])
        assertNull(params["cwd_explicit"])
    }
}
