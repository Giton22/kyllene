package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a clarify answer sends on each kind of gateway. On the session authority a pending-controls clarify is
 * answered with `clarify.respond`, whose params are closed and all required (hermes-agent
 * `tui_gateway/contracts/canonical.py` `ClarifyRespondParams`: v0.22.1 :286, v0.22.3 :355, v0.22.7 :357,
 * v0.23.4 :434); `canonical_param_problems` refuses a missing or unknown key with 4001 after removing `profile`.
 * A classic `clarify` server request (every clarify before v0.22.1, a plugin choice on the authority) is answered
 * with a response frame shaped as `ClarifyResult`: `{answers}`, or `{}` to cancel.
 */
class ClarifyRespondContractTest {

    private val clarifyRespondKeys = setOf("session_id", "execution_generation", "prompt_id", "answer")

    /** The keys upstream's `canonical_param_problems` would name (missing and extra). */
    private fun problems(params: JsonObject): Set<String> {
        val keys = params.keys - "profile"
        return (clarifyRespondKeys - keys) + (keys - clarifyRespondKeys)
    }

    private class Opened(val gw: FakeGateway, val chat: ChatSession, val clarify: InputRequest.Clarify)

    private suspend fun TestScope.authority(payload: String): Opened {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""" }
            answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":true,"execution_generation":4}"""
                    "clarify.respond" -> """{"status":"resolved","prompt_id":"prm-1"}"""
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(FakeGateway.URL, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }
        gw.socket.push(event("clarify.request", "rt1", payload))
        val clarify = assertIs<InputRequest.Clarify>(chat.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single())
        assertEquals(InputRequestDelivery.PendingControl(4), clarify.delivery)
        return Opened(gw, chat, clarify)
    }

    /** Answers on the authority and returns what `clarify.respond` carried, checked against the contract. */
    private suspend fun TestScope.respondOnAuthority(payload: String, answerOf: (InputRequest.Clarify) -> JsonObject): JsonObject {
        val setup = authority(payload)
        assertTrue(setup.chat.answer(setup.clarify, answerOf(setup.clarify)))
        assertTrue(setup.chat.state.value.inputRequests.isEmpty())
        val params = setup.gw.sent("clarify.respond").single()["params"]!!.jsonObject
        assertEquals(emptySet(), problems(params), "upstream would answer 4001 invalid_params for $params")
        assertEquals("rt1", params["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("4", params["execution_generation"]?.jsonPrimitive?.contentOrNull)
        assertEquals("prm-1", params["prompt_id"]?.jsonPrimitive?.contentOrNull)
        return params
    }

    private fun answerOf(params: JsonObject) = params["answer"]?.jsonPrimitive?.contentOrNull

    /** As `register_clarify` publishes it on v0.22.x: one question, no `timeout_s`. */
    private val singleV022 = """{"prompt_id":"prm-1","execution_generation":4,"question":"What next?","choices":["Review","Leave"]}"""

    /** As v0.23.4 publishes it. */
    private val singleV023 =
        """{"prompt_id":"prm-1","execution_generation":4,"question":"What next?","choices":["Review","Leave"],"multi_select":false,"timeout_s":3600}"""

    /** No upstream tag puts `questions` in a pending prompt; these stand in for one that might. */
    private val batchOfOne = """{"prompt_id":"prm-1","execution_generation":4,"questions":[{"qid":"q1","question":"Name?"}]}"""
    private val batchOfTwo = """{"prompt_id":"prm-1","execution_generation":4,"questions":[""" +
        """{"qid":"q1","question":"Name?"},{"qid":"q2","question":"Colour?","choices":["Red","Blue"]}]}"""

    @Test
    fun singleAnswerOnAuthority() = runTest(timeout = 2.seconds) {
        for (payload in listOf(singleV022, singleV023)) {
            val params = respondOnAuthority(payload) { InputAnswers.clarify(it, listOf(listOf("Review"))) }
            assertEquals("Review", answerOf(params))
        }
    }

    @Test
    fun singleSkipOnAuthority() = runTest(timeout = 2.seconds) {
        for (payload in listOf(singleV022, singleV023)) {
            val params = respondOnAuthority(payload) { InputAnswers.clarify(it, listOf(emptyList())) }
            assertEquals("", answerOf(params))
        }
    }

    @Test
    fun pendingControlBatchOfOneSendsAnswer() = runTest(timeout = 2.seconds) {
        val params = respondOnAuthority(batchOfOne) { InputAnswers.clarify(it, listOf(listOf("Ada"))) }
        assertEquals("Ada", answerOf(params))
    }

    @Test
    fun pendingControlCancelSendsEmptyAnswer() = runTest(timeout = 2.seconds) {
        for (payload in listOf(singleV023, batchOfOne, batchOfTwo)) {
            val params = respondOnAuthority(payload) { InputAnswers.clarifyCancel }
            assertEquals("", answerOf(params))
        }
    }

    @Test
    fun pendingControlBatchOfTwoIsRefusedLocally() = runTest(timeout = 2.seconds) {
        val setup = authority(batchOfTwo)
        assertFalse(setup.chat.answer(setup.clarify, InputAnswers.clarify(setup.clarify, listOf(listOf("Ada"), emptyList()))))
        assertTrue(setup.gw.sent("clarify.respond").isEmpty())
        assertNotNull(setup.chat.state.value.error)
        assertEquals(listOf(setup.clarify), setup.chat.state.value.inputRequests)
    }

    @Test
    fun pendingControlBatchOfTwoWithOneAnswerIsRefusedLocally() = runTest(timeout = 2.seconds) {
        // A notification reply or the watch answers only the first question: one entry, still two questions.
        val setup = authority(batchOfTwo)
        val firstOnly = InputAnswers.clarify(setup.clarify, listOf(listOf("Ada")))
        assertNotNull(InputRequest.pendingControlRefusal(setup.clarify, firstOnly))
        assertFalse(setup.chat.answer(setup.clarify, firstOnly))
        assertTrue(setup.gw.sent("clarify.respond").isEmpty())
        assertEquals(listOf(setup.clarify), setup.chat.state.value.inputRequests)
    }

    /** A classic `clarify` server request for [runtimeId], as `ClarifyRequestParams` has it (one `q0`). */
    private fun clarifyRequest(runtimeId: String) =
        """{"jsonrpc":"2.0","id":"srq-1","method":"clarify","params":{"session_id":"$runtimeId",""" +
            """"questions":[{"qid":"q0","question":"Which one?","choices":["Alpha","Beta"],"multi_select":false}]}}"""

    private suspend fun TestScope.serverRequest(ready: String): Opened {
        val gw = FakeGateway(backgroundScope, reconnects = false).apply {
            this.ready = { ready }
            answer = { call -> if (call.method == "session.resume") """{"session_id":"rt1","running":true}""" else "{}" }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(FakeGateway.URL, "stored-1", "DM", gw.connection, SessionsApi(gw.client), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }
        gw.socket.push(clarifyRequest("rt1"))
        val clarify = assertIs<InputRequest.Clarify>(chat.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single())
        assertEquals(InputRequestDelivery.ServerRequest, clarify.delivery)
        return Opened(gw, chat, clarify)
    }

    /** The response frame sent for `srq-1`, as its `result`. */
    private suspend fun FakeGateway.responseResult(): JsonObject =
        socket.awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" && "result" in it }["result"]!!.jsonObject

    @Test
    fun legacyBatchAnswerAndCancelUnchanged() = runTest(timeout = 2.seconds) {
        // v0.22.0: no session authority, every clarify is the classic request.
        val answered = serverRequest(FakeGateway.QUIET_READY)
        assertTrue(answered.chat.answer(answered.clarify, InputAnswers.clarify(answered.clarify, listOf(listOf("Beta")))))
        assertEquals("""{"answers":{"q0":"Beta"}}""", answered.gw.responseResult().toString())
        assertTrue(answered.gw.sent("clarify.respond").isEmpty())

        val skipped = serverRequest(FakeGateway.QUIET_READY)
        assertTrue(skipped.chat.answer(skipped.clarify, InputAnswers.clarifyCancel))
        assertEquals("{}", skipped.gw.responseResult().toString())
        assertTrue(skipped.gw.sent("clarify.respond").isEmpty())
    }

    @Test
    fun authorityServerRequestClarifyUsesRespond() = runTest(timeout = 2.seconds) {
        // v0.22.7+: a plugin command's choice goes out as the classic request even on the session authority.
        val ready = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""
        val opened = serverRequest(ready)
        assertTrue(opened.chat.answer(opened.clarify, InputAnswers.clarify(opened.clarify, listOf(listOf("Alpha")))))
        assertEquals("""{"answers":{"q0":"Alpha"}}""", opened.gw.responseResult().toString())
        assertTrue(opened.gw.sent("clarify.respond").isEmpty())
    }
}
