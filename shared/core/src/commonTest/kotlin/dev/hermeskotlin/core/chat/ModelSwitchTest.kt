package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Switching a live chat's model. The session authority (`gateway.ready` `session_authority`) refuses
 * `config.set model` with `use_session_mutation_model` (hermes-agent gateway/session_busy_controls.py), so there a
 * pick is a fenced `session.mutate` `model` (gateway/session_mutations.py, session_mutation_model.py); older
 * gateways keep `config.set`.
 */
class ModelSwitchTest {

    private suspend fun chat(
        scope: CoroutineScope,
        authority: Boolean,
        mutate: (FakeGateway.Call) -> String? = { it.switched() },
        configSet: (FakeGateway.Call) -> String? = { """{"key":"${it.param("key")}","value":"ok"}""" },
        refusesConfigSetModel: Boolean = authority,
    ): Pair<ChatSession, FakeGateway> {
        val gateway = FakeGateway(scope, reconnects = false).apply {
            ready = {
                """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":$authority}}}"""
            }
            answer = { call ->
                when (call.method) {
                    "session.resume" ->
                        """{"session_id":"rt1","running":false,"revision":4,"execution_generation":2,"info":{"model":"m1","provider":"p1"}}"""
                    // What the authority does with config.set model (busy_config -> AuthorityRefusal, wire 4001).
                    "config.set" -> if (refusesConfigSetModel && call.param("key") == "model") {
                        call.error(4001, "use_session_mutation_model", reason = "use_session_mutation_model")
                    } else {
                        configSet(call)
                    }
                    "session.mutate" -> if (authority) mutate(call) else "error:-32601"
                    else -> "{}"
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
        }
        gateway.start(awaitConnected = true)
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }
        return chat to gateway
    }

    private fun FakeGateway.Call.switched() = reply(
        """{"session_id":"rt1","operation":"model","revision":5,"execution_generation":3,"model":"${payload()["model"]}","provider":"p2"}""",
    )

    private fun FakeGateway.Call.payload() = params["payload"]!!.jsonObject.mapValues { (it.value as JsonPrimitive).content }

    private fun FakeGateway.Call.confirmationRequired(token: String) = reply(
        """{"session_id":"rt1","operation":"model","status":"confirmation_required","confirm_required":true,"confirm":"$token","target_model":"big","target_provider":"p2","confirm_message":"Costs a lot"}""",
    )

    private fun FakeGateway.Call.fenceRefusal(revision: Int, generation: Int) =
        """{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"revision_conflict","data":{"reason":"revision_conflict","revision":$revision,"execution_generation":$generation}}}"""

    private fun JsonObject.payload() = this["params"]!!.jsonObject["payload"]!!.jsonObject

    @Test
    fun theSessionAuthoritySwitchesWithAFencedModelMutation() = runTest {
        val (chat, gateway) = chat(backgroundScope, authority = true)

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))

        val mutate = gateway.sent("session.mutate").single()
        assertEquals("rt1", mutate.param("session_id"))
        assertEquals("model", mutate.param("operation"))
        assertEquals("4", mutate.param("expected_revision"))
        assertEquals("2", mutate.param("expected_generation"))
        assertNotNull(mutate.param("request_id"))
        // Strings only (persistence/authority/runtime.py), no `--session`: the mutation is the session's own.
        assertEquals(JsonObject(mapOf("model" to JsonPrimitive("m2"), "provider" to JsonPrimitive("p2"))), mutate.payload())
        assertTrue(gateway.sent("config.set").none { it.param("key") == "model" })
        val state = chat.state.value
        assertEquals("m2" to "p2", state.model to state.provider)
        assertNull(state.error)

        // Reasoning still goes over config.set, which the authority accepts.
        chat.setReasoningEffort("low")
        gateway.socket.awaitSent { it.isCall("config.set") && it.param("key") == "reasoning" && it.param("value") == "low" }
    }

    @Test
    fun anExpensivePickOnTheSessionAuthorityIsConfirmedWithTheGatewaysToken() = runTest {
        var calls = 0
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { call ->
            calls++
            if (call.payload()["confirm"] == "tok-1") call.switched() else call.confirmationRequired("tok-${calls}")
        })

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2"))
        assertEquals("m1", chat.state.value.model)
        assertNull(gateway.sent("session.mutate").single().payload()["confirm"])

        assertEquals(ModelSwitch.Done, chat.setModel("big", "p2", confirm = true))
        val confirmed = gateway.sent("session.mutate").last()
        // The token the gateway gave, as a string: a boolean is invalid_params.
        assertEquals(JsonPrimitive("tok-1"), confirmed.payload()["confirm"])
        assertEquals("big", chat.state.value.model)
        assertNull(chat.state.value.error)
        assertTrue(gateway.sent("config.set").none { it.param("key") == "model" })
    }

    @Test
    fun aGuardedPickMarkedOnlyByItsStatusStillAsks() = runTest {
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { call ->
            if (call.payload()["confirm"] == "tok-1") {
                call.switched()
            } else {
                call.reply("""{"session_id":"rt1","operation":"model","status":"confirmation_required","confirm":"tok-1","confirm_message":"Costs a lot"}""")
            }
        })

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2"))
        assertEquals("m1", chat.state.value.model)
        assertEquals(ModelSwitch.Done, chat.setModel("big", "p2", confirm = true))
        assertEquals(JsonPrimitive("tok-1"), gateway.sent("session.mutate").last().payload()["confirm"])
    }

    @Test
    fun aStaleConfirmationOnTheSessionAuthorityAsksAgain() = runTest {
        // A token bound to a revision that moved on gets a fresh confirmation_required, never a silent apply.
        var calls = 0
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { call -> calls++; call.confirmationRequired("tok-$calls") })

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2"))
        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2", confirm = true))
        assertEquals(JsonPrimitive("tok-1"), gateway.sent("session.mutate")[1].payload()["confirm"])
        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2", confirm = true))
        assertEquals(JsonPrimitive("tok-2"), gateway.sent("session.mutate")[2].payload()["confirm"])
        assertEquals("m1", chat.state.value.model)

        // A token is only for the pick it was given for.
        chat.setModel("other", "p3", confirm = true)
        assertNull(gateway.sent("session.mutate").last().payload()["confirm"])
    }

    @Test
    fun aPickDuringATurnOnTheSessionAuthorityIsDeferredNotFailed() = runTest {
        val (chat, _) = chat(backgroundScope, authority = true, mutate = { call ->
            call.reply("""{"session_id":"rt1","operation":"model","status":"deferred","model":"m2","revision":4,"execution_generation":2}""")
        })

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))
        assertEquals("m2", chat.state.value.model)
        assertNull(chat.state.value.error)
    }

    @Test
    fun aStaleFenceOnTheSessionAuthorityIsRetriedOnceWithTheCurrentOnes() = runTest {
        var calls = 0
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { call ->
            if (++calls == 1) call.fenceRefusal(revision = 7, generation = 3) else call.switched()
        })

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))
        val (first, retry) = gateway.sent("session.mutate")
        assertEquals("7", retry.param("expected_revision"))
        assertEquals("3", retry.param("expected_generation"))
        assertNotEquals(first.param("request_id"), retry.param("request_id"))
        assertEquals("m2", chat.state.value.model)
    }

    @Test
    fun aSecondStaleFenceOnTheSessionAuthorityRollsBackWithAReadableError() = runTest {
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { it.fenceRefusal(revision = 7, generation = 3) })

        assertEquals(ModelSwitch.Failed, chat.setModel("m2", "p2"))
        assertEquals(2, gateway.sent("session.mutate").size)
        val state = chat.state.value
        assertEquals("m1" to "p1", state.model to state.provider)
        assertEquals("The chat changed on the gateway while switching the model. Try again.", state.error)
    }

    @Test
    fun aStaleFenceWithoutTheCurrentOnesRollsBackWithAReadableError() = runTest {
        // hermes-agent v0.22.1 refuses a stale fence without the current revision and generation (v0.22.3 on add them).
        val (chat, gateway) = chat(backgroundScope, authority = true, mutate = { "error:4001:revision_conflict" })

        assertEquals(ModelSwitch.Failed, chat.setModel("m2", "p2"))
        assertEquals(1, gateway.sent("session.mutate").size)
        val state = chat.state.value
        assertEquals("m1" to "p1", state.model to state.provider)
        assertEquals("The chat changed on the gateway while switching the model. Try again.", state.error)
    }

    @Test
    fun aPickDuringATurnOnAnEarlySessionAuthorityIsExplained() = runTest {
        // hermes-agent v0.22.1 has no deferred pick: a turn in progress refuses it as session_busy.
        val (chat, _) = chat(backgroundScope, authority = true, mutate = { "error:4001:session_busy" })

        assertEquals(ModelSwitch.Failed, chat.setModel("m2", "p2"))
        val state = chat.state.value
        assertEquals("m1", state.model)
        assertEquals("The model can't change while a reply is running. Switch it when this one is done.", state.error)
    }

    @Test
    fun aSessionAuthorityWithoutModelMutationsFallsBackToConfigSet() = runTest {
        val (chat, gateway) = chat(backgroundScope, authority = true, refusesConfigSetModel = false, mutate = { "error:-32601" })

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))
        assertEquals(1, gateway.sent("session.mutate").size)
        assertEquals("m2 --provider p2 --session", gateway.sent("config.set").single { it.param("key") == "model" }.param("value"))
        assertEquals("m2", chat.state.value.model)
    }

    @Test
    fun anOlderGatewayStillSwitchesOverConfigSet() = runTest {
        val (chat, gateway) = chat(backgroundScope, authority = false, configSet = { call ->
            if (call.param("key") == "model" && call.params["confirm_expensive_model"] == null) {
                """{"key":"model","confirm_required":true,"confirm_message":"Costs a lot"}"""
            } else {
                """{"key":"model","value":"big"}"""
            }
        })

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p2"))
        assertEquals("m1", chat.state.value.model)
        assertEquals(ModelSwitch.Done, chat.setModel("big", "p2", confirm = true))

        val (asked, confirmed) = gateway.sent("config.set").filter { it.param("key") == "model" }
        assertEquals("rt1", asked.param("session_id"))
        assertEquals("big --provider p2 --session", asked.param("value"))
        assertNull(asked.param("confirm_expensive_model"))
        assertEquals("big --provider p2 --session", confirmed.param("value"))
        assertEquals("true", confirmed.param("confirm_expensive_model"))
        assertTrue(gateway.sent("session.mutate").isEmpty())
        assertEquals("big", chat.state.value.model)
        assertNull(chat.state.value.error)
    }
}
