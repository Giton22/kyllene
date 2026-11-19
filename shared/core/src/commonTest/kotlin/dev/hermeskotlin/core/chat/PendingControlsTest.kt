package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PendingControlsTest {

    private val url = FakeGateway.URL

    @Test
    fun clarifyRequestEventSurfacesAnswersViaClarifyRespondAndSettles() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":true,"execution_generation":4}"""
                    "clarify.respond" -> """{"status":"accepted"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope, profile = "github")
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        gw.socket.push(
            event(
                "clarify.request",
                "rt1",
                """{"prompt_id":"prm-1","execution_generation":4,"question":"What next?","choices":["Review","Open PR","Leave"]}""",
            ),
        )

        val shown = chat.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        val clarify = assertIs<InputRequest.Clarify>(shown)
        assertEquals("prm-1", clarify.id)
        assertEquals("What next?", clarify.questions.single().question)
        assertEquals(listOf("Review", "Open PR", "Leave"), clarify.questions.single().choices)

        assertTrue(chat.answer(clarify, InputAnswers.clarify(clarify, listOf(listOf("Review")))))
        assertTrue(chat.state.value.inputRequests.isEmpty())

        val respond = gw.socket.sent.value.first { it.isCall("clarify.respond") }
        val params = respond["params"]!!.jsonObject
        assertEquals("rt1", params["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4L, params["execution_generation"]?.jsonPrimitive?.longOrNull)
        assertEquals("prm-1", params["prompt_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Review", params["answer"]?.jsonPrimitive?.contentOrNull)
        assertEquals("github", params["profile"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun approvalRequestBackfillAndSettledEventWork() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{
                        "session_id":"rt1",
                        "running":true,
                        "execution_generation":5,
                        "pending_controls":[
                            {"kind":"approval","prompt_id":"apr-1","execution_generation":5,"command":"rm -rf tmp","description":"Delete tmp","choices":["once","deny"]},
                            {"kind":"clarify","prompt_id":"clr-2","execution_generation":5,"question":"Continue?","choices":["Yes","No"]}
                        ]
                    }"""
                    "approval.respond" -> """{"status":"accepted"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()

        val initial = chat.state.first { it.inputRequests.size == 2 }.inputRequests
        val approval = assertIs<InputRequest.Approval>(initial[0])
        assertEquals("apr-1", approval.id)
        assertEquals("rm -rf tmp", approval.command)

        // Settling clr-2 removes it without answering locally
        gw.socket.push(event("clarify.settled", "rt1", """{"prompt_id":"clr-2"}"""))
        chat.state.first { it.inputRequests.size == 1 }

        // Answering approval sends approval.respond
        assertTrue(chat.answer(approval, InputAnswers.approval(ApprovalChoice.Once)))
        val respond = gw.socket.sent.value.first { it.isCall("approval.respond") }
        val params = respond["params"]!!.jsonObject
        assertEquals("rt1", params["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(5L, params["execution_generation"]?.jsonPrimitive?.longOrNull)
        assertEquals("apr-1", params["prompt_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("once", params["choice"]?.jsonPrimitive?.contentOrNull)
    }
}
