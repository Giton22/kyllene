package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class InterruptExecutionGenerationTest {

    private val url = FakeGateway.URL

    @Test
    fun interruptAndStopAndSubmitSendExecutionGenerationFromResumeAndEvents() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":true,"execution_generation":7}"""
                    "session.interrupt" -> {
                        val gen = call.params["execution_generation"]?.jsonPrimitive?.longOrNull
                        if (gen == null) "error:4001:invalid_params" else """{"status":"interrupted"}"""
                    }
                    "prompt.submit" -> """{"status":"streaming","execution_generation":8}"""
                    "process.stop" -> if (call.param("session_id") == "rt1") """{"killed":0}""" else "error:4001:invalid_params"
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Busy", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.running }

        // 1) stopAndSubmit uses execution_generation=7 from session.resume
        val stopAndSend = launch {
            val outcome = chat.stopAndSubmit("actually do this")
            assertEquals(SendOutcome.Sent, outcome.outcome)
        }
        val firstInterrupt = gw.socket.awaitSent { it.isCall("session.interrupt") }
        assertEquals(7L, firstInterrupt["params"]?.jsonObject?.get("execution_generation")?.jsonPrimitive?.longOrNull)
        gw.socket.push(event("message.complete", "rt1", """{"status":"interrupted"}"""))
        stopAndSend.join()

        // 2) Turn event updates execution_generation to 9; /stop sends 9
        gw.socket.push(event("message.complete", "rt1", """{"status":"completed"}"""))
        chat.state.first { !it.running }
        gw.socket.push(event("message.start", "rt1", """{"execution_generation":9}"""))
        chat.state.first { it.running }
        chat.stopEverything()

        val interrupts = gw.socket.sent.value.filter { it.isCall("session.interrupt") }
        assertEquals(2, interrupts.size)
        assertEquals(9L, interrupts.last()["params"]?.jsonObject?.get("execution_generation")?.jsonPrimitive?.longOrNull)
        assertNull(chat.state.value.error)
        val stopCard = chat.state.value.messages.filterIsInstance<ChatMessage.Command>().last()
        assertEquals("Stopped the reply.\nNo background processes were running.", stopCard.output)
    }
}
