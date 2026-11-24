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

    /**
     * A `session.resume` reply as the session authority (hermes-agent 0.23.x) sends it while a turn waits: the
     * open clarify and approval prompts in `prompts`, `open_requests` empty, no `pending_*` keys.
     */
    private fun authorityResume(prompts: String) = """{
        "session_id":"rt1","stored_session_id":"stored-1","messages":[],"messages_omitted":true,"message_count":2,
        "running":true,"authority_epoch":1,"replay_epoch":"e1","last_sequence":9,"subscription_id":"sub-1",
        "revision":1,"execution_generation":1,"pending":[],"prompts":[$prompts],"info":{},
        "pending_connection":null,"todo_state":null,"open_requests":[]
    }"""

    private val clarifyPrompt =
        """{"kind":"clarify","prompt_id":"1fe66c4923","execution_generation":1,"question":"Which colour should the test use?",""" +
            """"choices":["Red (Recommended)","Green","Blue"],"multi_select":false,"timeout_s":3600}"""

    private val approvalPrompt =
        """{"kind":"approval","prompt_id":"3548d4a27d4c","execution_generation":1,"command":"rm -rf /tmp/probe-dir",""" +
            """"description":"delete in root path","choices":["once","deny","session","always"],"timeout_s":300}"""

    @Test
    fun aChatOpenedWhilePromptsWaitShowsThemFromTheResumeAndAnswersThem() = runTest(timeout = 2.seconds) {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> authorityResume("$clarifyPrompt,$approvalPrompt")
                    "clarify.respond", "approval.respond" -> """{"status":"resolved","prompt_id":"${call.param("prompt_id")}"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()

        val (clarify, approval) = chat.state.first { it.inputRequests.size == 2 }.inputRequests
        assertIs<InputRequest.Clarify>(clarify)
        assertEquals("1fe66c4923", clarify.id)
        assertEquals(InputRequestDelivery.PendingControl(1), clarify.delivery)
        val question = clarify.questions.single()
        assertEquals("Which colour should the test use?", question.question)
        assertEquals(listOf("Red (Recommended)", "Green", "Blue"), question.choices)
        assertEquals(false, question.multiSelect)
        assertIs<InputRequest.Approval>(approval)
        assertEquals("rm -rf /tmp/probe-dir", approval.command)
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny), approval.choices)

        assertTrue(chat.answer(clarify, InputAnswers.clarify(clarify, listOf(listOf("Green")))))
        val clarifyParams = gw.sent("clarify.respond").single()["params"]!!.jsonObject
        assertEquals("rt1", clarifyParams["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1L, clarifyParams["execution_generation"]?.jsonPrimitive?.longOrNull)
        assertEquals("1fe66c4923", clarifyParams["prompt_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Green", clarifyParams["answer"]?.jsonPrimitive?.contentOrNull)

        assertTrue(chat.answer(approval, InputAnswers.approval(ApprovalChoice.Deny)))
        val approvalParams = gw.sent("approval.respond").single()["params"]!!.jsonObject
        assertEquals("3548d4a27d4c", approvalParams["prompt_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1L, approvalParams["execution_generation"]?.jsonPrimitive?.longOrNull)
        assertEquals("deny", approvalParams["choice"]?.jsonPrimitive?.contentOrNull)
        assertTrue(chat.state.value.inputRequests.isEmpty())
    }

    @Test
    fun aQuestionKeepsItsCardAcrossAReconnectAndOneAskedWhileAwayAppears() = runTest(timeout = 5.seconds) {
        var prompts = ""
        val gw = FakeGateway(backgroundScope).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call -> if (call.method == "session.resume") authorityResume(prompts) else "{}" }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        // Heard live, then the link drops and the new socket's resume still lists it.
        prompts = clarifyPrompt
        gw.socket.push(event("clarify.request", "rt1", clarifyPrompt))
        chat.state.first { it.inputRequests.isNotEmpty() }
        val first = gw.socket
        first.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        // Asked while no socket was attached: only the next resume names it.
        prompts = "$clarifyPrompt,$approvalPrompt"
        chat.state.first { it.runtimeSessionId == "rt1" && gw.socket !== first }

        assertEquals(listOf("1fe66c4923", "3548d4a27d4c"), chat.state.value.inputRequests.map { it.id })
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
