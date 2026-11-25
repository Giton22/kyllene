package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.bots.BotChatTakenException
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.threads.ThreadTitleTakenException
import dev.hermeskotlin.core.threads.ThreadsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A titled chat (a thread's, a Bot Chat) made, and let go of, on a gateway whose `/api/ws` runs on its session
 * authority, which refuses the legacy `session.title`, `session.close` and `session.delete` with -32601
 * (gateway/session_controls.py `dispatch`, tui_gateway/ws_legacy_fallback.py), and on an older gateway, which has
 * only those.
 */
class TitledChatTest {

    private lateinit var gateway: FakeGateway

    /** The `session.*` calls sent, in order (the connection's own handshake left out). */
    private fun methods() = gateway.sent.mapNotNull { it["method"]?.jsonPrimitive?.contentOrNull }.filter { it.startsWith("session.") }

    private fun sent(method: String): JsonObject = gateway.sent(method).single()["params"]!!.jsonObject

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    /**
     * A gateway on the session [authority] or not. Each verb the other kind has is -32601, as upstream answers it:
     * on the authority with `data.reason`, on the legacy dashboard without.
     */
    private suspend fun connect(
        scope: CoroutineScope,
        authority: Boolean,
        extra: (FakeGateway.Call) -> String? = { null },
    ): JsonRpcClient {
        gateway = FakeGateway(scope, reconnects = false).apply {
            ready = { if (authority) AUTHORITY_READY else FakeGateway.QUIET_READY }
            answer = { call ->
                extra(call) ?: when {
                    authority && call.method in LEGACY -> call.unknown(withReason = true)
                    !authority && call.method in CANONICAL -> call.unknown(withReason = false)
                    call.method == "session.create" ->
                        """{"session_id":"rt-new","stored_session_id":"stored-new","revision":3,"execution_generation":1,"subscription_id":"sub-1"}"""
                    else -> "{}"
                }
            }
            start(awaitConnected = true)
        }
        return (gateway.connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected).client.also {
            it.ready.await()
            assertEquals(authority, it.sessionAuthority)
        }
    }

    // --- The session authority ---

    @Test
    fun onTheSessionAuthorityAChatIsMadeThenRenamed() = runTest(timeout = 5.seconds) {
        val client = connect(backgroundScope, authority = true)

        val made = client.createTitledChat(null, "Misc", taken = { IllegalStateException("taken") })

        assertEquals("rt-new" to "stored-new", made)
        assertEquals(listOf("session.create", "session.mutate"), methods())
        // A titled create would open a chat that has the title already (gateway/session_controls.py `create`).
        assertNull(sent("session.create")["title"])
        val rename = sent("session.mutate")
        assertEquals("rt-new", rename.text("session_id"))
        assertEquals("rename", rename.text("operation"))
        assertEquals("3", rename.text("expected_revision"))
        assertEquals("1", rename.text("expected_generation"))
        assertEquals("Misc", rename["payload"]!!.jsonObject.text("title"))
        assertTrue(rename.text("request_id").orEmpty().isNotEmpty())
    }

    @Test
    fun onTheSessionAuthorityATakenTitleIsTakenWithNothingLeftBehind() = runTest(timeout = 5.seconds) {
        // hermes-agent v0.23.4 answers a rename to a taken title with a bare internal error; the title lookup finds
        // the chat that holds it.
        val client = connect(backgroundScope, authority = true, extra = { call ->
            when {
                call.isRename() -> call.error(-32603, "internal error")
                call.method == "session.list" -> """{"sessions":[{"session_id":"other","title":"Misc"}],"scope":"stored"}"""
                else -> null
            }
        })

        assertFailsWith<ThreadTitleTakenException> {
            client.createTitledChat(null, "Misc", taken = { ThreadTitleTakenException("Misc") })
        }

        assertEquals(
            listOf("session.create", "session.mutate", "session.detach", "session.mutate", "session.list"),
            methods(),
        )
        assertEquals("Misc", sent("session.list").text("title"))
        assertTrue(methods().none { it in LEGACY })
        val delete = gateway.sent("session.mutate").last()["params"]!!.jsonObject
        assertEquals("delete", delete.text("operation"))
        assertEquals("rt-new", delete.text("session_id"))
        assertEquals("3", delete.text("expected_revision"))
        assertEquals("1", delete.text("expected_generation"))
        val detach = sent("session.detach")
        assertEquals(setOf("session_id", "subscription_id"), detach.keys)
        assertEquals("sub-1", detach.text("subscription_id"))
    }

    @Test
    fun onTheSessionAuthorityATitleOnlyAnotherLoginHasIsTaken() = runTest(timeout = 5.seconds) {
        // The title lookup never confirms another principal's chat: it is not_found (gateway/session_local_title.py).
        val client = connect(backgroundScope, authority = true, extra = { call ->
            when {
                call.isRename() -> call.error(-32603, "internal error")
                call.method == "session.list" -> call.error(4004, "not_found", reason = "not_found")
                else -> null
            }
        })

        assertFailsWith<ThreadTitleTakenException> {
            client.createTitledChat(null, "Misc", taken = { ThreadTitleTakenException("Misc") })
        }
    }

    @Test
    fun onTheSessionAuthorityAnInternalErrorWithTheTitleFreeIsThatError() = runTest(timeout = 5.seconds) {
        // Any crash in a handler is a bare -32603 too (tui_gateway/ws.py): with no chat holding the title, it is
        // that error, not "every name is taken" after trying each one.
        val client = connect(backgroundScope, authority = true, extra = { call ->
            when {
                call.isRename() -> call.error(-32603, "internal error")
                call.method == "session.list" -> """{"sessions":[],"scope":"stored"}"""
                else -> null
            }
        })

        val error = assertFailsWith<RpcException> {
            client.createTitledChat(null, "Misc", taken = { ThreadTitleTakenException("Misc") })
        }

        assertEquals(-32603, error.code)
        assertEquals("delete", gateway.sent("session.mutate").last()["params"]!!.jsonObject.text("operation"))
    }

    @Test
    fun onTheSessionAuthorityANewBotsIntroGoesOnlyToAChatJustMade() = runTest(timeout = 5.seconds) {
        // Another client opened the new bot's chat first: a titled create would hand that chat back, and the intro
        // would be a second one. Made untitled and renamed, the title is taken instead, as on an older gateway.
        connect(backgroundScope, authority = true, extra = { call ->
            when {
                call.isRename() -> call.error(-32603, "internal error")
                call.method == "session.list" -> """{"sessions":[{"session_id":"theirs","title":"Bot Chat"}],"scope":"stored"}"""
                else -> null
            }
        })

        assertFailsWith<BotChatTakenException> { BotsApi(gateway.connection).startWithIntro("work") }

        assertNull(sent("session.create")["title"])
        assertEquals("true", sent("session.create").text("hidden"))
        assertTrue(gateway.sent("prompt.submit").isEmpty())
    }

    @Test
    fun onTheSessionAuthorityANewBotsChatIsMadeThenRenamedAndGreeted() = runTest(timeout = 5.seconds) {
        connect(backgroundScope, authority = true)

        val stored = BotsApi(gateway.connection).startWithIntro("work")

        assertEquals("stored-new", stored)
        assertEquals("Bot Chat", gateway.sent("session.mutate").single()["params"]!!.jsonObject["payload"]!!.jsonObject.text("title"))
        assertEquals("rt-new", gateway.sent("prompt.submit").single()["params"]!!.jsonObject.text("session_id"))
    }

    @Test
    fun onTheSessionAuthorityAnotherRefusedRenameIsThatErrorWithNothingLeftBehind() = runTest(timeout = 5.seconds) {
        val client = connect(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate" && call.params["operation"]?.jsonPrimitive?.contentOrNull == "rename") {
                call.error(4001, "invalid_params", reason = "invalid_params")
            } else {
                null
            }
        })

        val error = assertFailsWith<RpcException> {
            client.createTitledChat(null, "Misc", taken = { ThreadTitleTakenException("Misc") })
        }

        assertEquals(4001, error.code)
        assertEquals("delete", gateway.sent("session.mutate").last()["params"]!!.jsonObject.text("operation"))
    }

    @Test
    fun onTheSessionAuthorityABotChatIsATitledCreate() = runTest(timeout = 5.seconds) {
        connect(backgroundScope, authority = true)

        val stored = BotsApi(gateway.connection).startBotChat("work")

        // The authority answers with the profile's Bot Chat when it has one, and makes it otherwise.
        assertEquals("stored-new", stored)
        assertEquals(listOf("session.create"), methods())
        val create = sent("session.create")
        assertEquals("Bot Chat", create.text("title"))
        assertEquals("work", create.text("profile"))
        assertEquals("true", create.text("hidden"))
    }

    @Test
    fun onTheSessionAuthorityAThreadChatLetsGoWithTheSubscriptionItWasOpenedWith() = runTest(timeout = 5.seconds) {
        connect(backgroundScope, authority = true)
        val threads = ThreadsApi(gateway.connection)
        val (runtime, _) = threads.startChat("work", "Misc", cwd = null)

        threads.close(runtime)
        threads.close(runtime)
        threads.close("rt-never-opened")

        assertEquals(1, gateway.sent("session.detach").size, "sent: ${methods()}")
        val detach = sent("session.detach")
        assertEquals("rt-new", detach.text("session_id"))
        assertEquals("sub-1", detach.text("subscription_id"))
        assertTrue(methods().none { it in LEGACY })
    }

    // --- An older gateway (the legacy dashboard `/api/ws`) ---

    @Test
    fun onAnOlderGatewayAChatIsMadeTitledThenTitled() = runTest(timeout = 5.seconds) {
        val client = connect(backgroundScope, authority = false)

        val made = client.createTitledChat(null, "Misc", taken = { IllegalStateException("taken") })

        assertEquals("rt-new" to "stored-new", made)
        assertEquals(listOf("session.create", "session.title"), methods())
        assertEquals("Misc", sent("session.create").text("title"))
        assertEquals("Misc", sent("session.title").text("title"))
    }

    @Test
    fun onAnOlderGatewayATakenTitleClosesAndDeletesWhatWasMade() = runTest(timeout = 5.seconds) {
        val client = connect(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.title") call.error(4022, "Title 'Misc' is already in use by session x") else null
        })

        assertFailsWith<ThreadTitleTakenException> {
            client.createTitledChat("work", "Misc", taken = { ThreadTitleTakenException("Misc") })
        }

        assertEquals(listOf("session.create", "session.title", "session.close", "session.delete"), methods())
        assertEquals("rt-new", sent("session.close").text("session_id"))
        assertEquals("stored-new", sent("session.delete").text("session_id"))
        assertEquals("work", sent("session.delete").text("profile"))
    }

    @Test
    fun onAnOlderGatewayAnInternalErrorIsNotATakenTitle() = runTest(timeout = 5.seconds) {
        val client = connect(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.title") call.error(-32603, "internal error") else null
        })

        val error = assertFailsWith<RpcException> {
            client.createTitledChat(null, "Misc", taken = { ThreadTitleTakenException("Misc") })
        }

        assertEquals(-32603, error.code)
    }

    @Test
    fun onAnOlderGatewayABotChatIsCreatedThenTitled() = runTest(timeout = 5.seconds) {
        connect(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.title") call.error(4022, "Title 'Bot Chat' is already in use") else null
        })

        assertFailsWith<BotChatTakenException> { BotsApi(gateway.connection).startBotChat("work") }

        assertEquals(listOf("session.create", "session.title", "session.close", "session.delete"), methods())
    }

    @Test
    fun onAnOlderGatewayAThreadChatLetsGoWithSessionClose() = runTest(timeout = 5.seconds) {
        connect(backgroundScope, authority = false)

        ThreadsApi(gateway.connection).close("rt-new")

        assertEquals(listOf("session.close"), methods())
        assertEquals("rt-new", sent("session.close").text("session_id"))
        assertFalse(methods().any { it in CANONICAL })
    }

    private companion object {
        const val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

        /** Not in the session authority's handlers (gateway/session_controls.py `handlers`). */
        val LEGACY = setOf("session.title", "session.close", "session.delete")

        /** Only on the session authority. */
        val CANONICAL = setOf("session.mutate", "session.detach")

        /**
         * -32601 as hermes-agent v0.23.4 answers it: gateway/session_controls.py `dispatch` with
         * tui_gateway/contracts/registry.py `unknown_method_message` on the authority; the legacy dispatcher has no
         * `data.reason`.
         */
        fun FakeGateway.Call.isRename() =
            method == "session.mutate" && params["operation"]?.jsonPrimitive?.contentOrNull == "rename"

        fun FakeGateway.Call.unknown(withReason: Boolean) =
            error(-32601, "unknown method: $method — not available on this Hermes backend", if (withReason) "unknown_method" else null)
    }
}
