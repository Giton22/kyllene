package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `/title`, `/compress`, `/status` and the account's limits on a gateway whose `/api/ws` runs on its session
 * authority, which refuses `session.title`, `session.compress`, `session.status` and `session.usage` with -32601
 * (gateway/session_controls.py `dispatch`, tui_gateway/ws_legacy_fallback.py), and on an older gateway, which has
 * only those.
 */
class AuthorityChatActionsTest {

    private lateinit var gateway: FakeGateway

    /** How often the transcript was read over REST. */
    private var historyReads = 0

    /** The `session.*` and `slash.*` calls sent, in order. */
    private fun methods() = gateway.sent.mapNotNull { it["method"]?.jsonPrimitive?.contentOrNull }
        .filter { it.startsWith("session.") || it.startsWith("slash.") }

    private fun params(method: String): List<JsonObject> = gateway.sent(method).map { it["params"]!!.jsonObject }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    /**
     * An open chat on a gateway on the session [authority] or not. Each verb the other kind has is -32601, as
     * upstream answers it: on the authority with `data.reason`, on the legacy dashboard without.
     */
    private suspend fun chat(
        scope: CoroutineScope,
        authority: Boolean,
        extra: (FakeGateway.Call) -> String? = { null },
    ): ChatSession {
        gateway = FakeGateway(scope, reconnects = false).apply {
            ready = { if (authority) AUTHORITY_READY else FakeGateway.QUIET_READY }
            answer = { call ->
                extra(call) ?: when {
                    authority && call.method in LEGACY -> call.unknown(withReason = true)
                    !authority && call.method in CANONICAL -> call.unknown(withReason = false)
                    call.method == "session.resume" ->
                        """{"session_id":"stored-1","running":false,"revision":4,"execution_generation":2}"""
                    else -> "{}"
                }
            }
            http = { request ->
                if (request.url.encodedPath.endsWith("/messages")) historyReads++
                json("""{"session_id":"stored-1","messages":[]}""")
            }
            start(awaitConnected = true)
        }
        val client = (gateway.connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected).client
        client.ready.await()
        assertEquals(authority, client.sessionAuthority)
        val chat = ChatSession(FakeGateway.URL, "stored-1", "Greeting", gateway.connection, SessionsApi(gateway.client), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "stored-1" && it.historyLoaded }
        return chat
    }

    private fun ChatSession.lastCommand() = state.value.messages.filterIsInstance<ChatMessage.Command>().last()

    private fun FakeGateway.Call.operation() = params["operation"]?.jsonPrimitive?.contentOrNull

    // --- The session authority ---

    @Test
    fun onTheSessionAuthorityTitleIsAFencedRename() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") """{"session_id":"stored-1","revision":5,"operation":"rename","title":"Renamed"}""" else null
        })

        chat.retitle("  Renamed ")

        val command = chat.lastCommand()
        assertFalse(command.failed, command.output)
        assertEquals("Title set: Renamed", command.output)
        assertEquals("Renamed", chat.state.value.title)
        val rename = params("session.mutate").single()
        assertEquals("rename", rename.text("operation"))
        assertEquals("4", rename.text("expected_revision"))
        assertEquals("2", rename.text("expected_generation"))
        assertEquals("Renamed", rename["payload"]!!.jsonObject.text("title"))
        assertTrue("session.title" !in methods())
    }

    @Test
    fun onTheSessionAuthorityAnEmptyTitleClearsIt() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") """{"session_id":"stored-1","revision":5,"operation":"rename","title":null}""" else null
        })

        chat.retitle("")

        assertEquals("Title cleared.", chat.lastCommand().output)
        assertEquals(null, chat.state.value.title)
    }

    @Test
    fun onTheSessionAuthorityARenameAtStaleFencesGoesOnceMoreAtTheCurrentOnes() = runTest(timeout = 5.seconds) {
        var refused = false
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            when {
                call.method != "session.mutate" -> null
                !refused -> {
                    refused = true
                    call.fenceRefusal("revision_conflict", revision = 7, generation = 3)
                }
                else -> """{"session_id":"stored-1","revision":8,"operation":"rename","title":"Renamed"}"""
            }
        })

        chat.retitle("Renamed")

        assertFalse(chat.lastCommand().failed, chat.lastCommand().output)
        val (first, second) = params("session.mutate")
        assertEquals("4", first.text("expected_revision"))
        assertEquals("7", second.text("expected_revision"))
        assertEquals("3", second.text("expected_generation"))
        assertTrue(first.text("request_id") != second.text("request_id"))
    }

    @Test
    fun onTheSessionAuthorityARenameRefusedTwiceForStaleFencesSaysTheChatChanged() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") call.fenceRefusal("stale_generation", revision = 7, generation = 3) else null
        })

        chat.retitle("Renamed")

        assertTrue(chat.lastCommand().failed)
        assertEquals("The chat changed on the gateway while renaming. Try again.", chat.lastCommand().output)
        assertEquals(2, params("session.mutate").size)
        assertEquals("Greeting", chat.state.value.title)
    }

    @Test
    fun onTheSessionAuthorityARenameToATakenTitleSaysWhy() = runTest(timeout = 5.seconds) {
        // hermes-agent v0.23.4 answers a rename to a title another chat has with a bare internal error.
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") call.error(-32603, "internal error") else null
        })

        chat.retitle("Taken")

        assertTrue(chat.lastCommand().failed)
        assertEquals("The gateway couldn't rename the chat. Another chat may have this title already.", chat.lastCommand().output)
        assertEquals("Greeting", chat.state.value.title)
    }

    @Test
    fun onTheSessionAuthorityCompressIsAFencedCompressWithItsFocus() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") {
                // As hermes-agent v0.23.4 answers it: no top-level `status` when something was removed.
                """{"session_id":"stored-1","revision":5,"operation":"compress","message_count":4,""" +
                    """"summary":{"noop":false,"headline":"Compressed: 12 → 4 messages","token_line":"~9,000 → ~2,000 tokens","note":null}}"""
            } else {
                null
            }
        })

        val before = historyReads
        chat.compress(" the build ")
        testScheduler.runCurrent()

        val command = chat.lastCommand()
        assertFalse(command.failed, command.output)
        assertEquals("Compressed: 12 → 4 messages\n~9,000 → ~2,000 tokens", command.output)
        val compress = params("session.mutate").single()
        assertEquals("compress", compress.text("operation"))
        assertEquals("4", compress.text("expected_revision"))
        assertEquals("2", compress.text("expected_generation"))
        // The contract's key is `focus` (persistence/authority/runtime.py); `focus_topic` is invalid_params.
        assertEquals(mapOf("focus" to "the build"), compress["payload"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertTrue("session.compress" !in methods())
        assertTrue(historyReads > before, "the transcript was not read again after it was compressed")
    }

    @Test
    fun onTheSessionAuthorityACompressWithNothingToRemoveSaysSo() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.mutate") {
                """{"session_id":"stored-1","operation":"compress","status":"noop",""" +
                    """"summary":{"noop":true,"headline":"No changes from compression: 2 messages","token_line":"~40 tokens","note":null}}"""
            } else {
                null
            }
        })

        val before = historyReads
        chat.compress("")
        testScheduler.runCurrent()

        assertEquals("No changes from compression: 2 messages\n~40 tokens", chat.lastCommand().output)
        assertEquals(JsonObject(emptyMap()), params("session.mutate").single()["payload"])
        assertEquals(before, historyReads, "nothing was removed, yet the transcript was read again")
    }

    @Test
    fun onTheSessionAuthorityStatusIsTheStatusCommand() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "slash.exec") """{"type":"exec","output":"**Hermes Gateway Status**\n**Agent Running:** No"}""" else null
        })

        chat.status()

        assertEquals("**Hermes Gateway Status**\n**Agent Running:** No", chat.lastCommand().output)
        val exec = params("slash.exec").single()
        assertEquals("status", exec.text("command"))
        assertEquals("stored-1", exec.text("session_id"))
        assertTrue("session.status" !in methods())
    }

    @Test
    fun onTheSessionAuthorityAccountLimitsComeFromAccountUsage() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.account_usage") {
                """{"lines":["5h window: 40% used"," "],"nous_lines":["Credits: 12.50"],"nous_route":true}"""
            } else {
                null
            }
        })

        assertEquals(listOf("5h window: 40% used", "Credits: 12.50"), chat.accountLimits())
        assertTrue("session.usage" !in methods())
    }

    @Test
    fun onASessionAuthorityWithoutAccountUsageTheLimitsAreEmpty() = runTest(timeout = 5.seconds) {
        // session.account_usage came with hermes-agent v0.23.2; v0.22.1 to v0.23.1 answer -32601.
        val chat = chat(backgroundScope, authority = true, extra = { call ->
            if (call.method == "session.account_usage") call.unknown(withReason = true) else null
        })

        assertEquals(emptyList(), chat.accountLimits())
        assertEquals(1, params("session.account_usage").size)
    }

    // --- An older gateway (the legacy dashboard `/api/ws`) ---

    @Test
    fun onAnOlderGatewayTitleIsSessionTitle() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.title") """{"pending":false,"title":"Renamed"}""" else null
        })

        chat.retitle("Renamed")

        assertEquals("Title set: Renamed", chat.lastCommand().output)
        assertEquals("Renamed", params("session.title").single().text("title"))
        assertTrue(methods().none { it in CANONICAL })
    }

    @Test
    fun onAnOlderGatewayCompressIsSessionCompress() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.compress") {
                """{"compressed":true,"before_messages":12,"after_messages":4,"before_tokens":9000,"after_tokens":2000}"""
            } else {
                null
            }
        })

        chat.compress("the build")

        assertEquals("Compressed: 12 → 4 messages, about 9000 → 2000 tokens.", chat.lastCommand().output)
        assertEquals("the build", params("session.compress").single().text("focus_topic"))
        assertTrue(methods().none { it in CANONICAL })
    }

    @Test
    fun onAnOlderGatewayStatusIsSessionStatus() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.status") """{"output":"Session: stored-1"}""" else null
        })

        chat.status()

        assertEquals("Session: stored-1", chat.lastCommand().output)
        assertTrue("slash.exec" !in methods())
    }

    @Test
    fun onAnOlderGatewayAccountLimitsComeFromSessionUsage() = runTest(timeout = 5.seconds) {
        val chat = chat(backgroundScope, authority = false, extra = { call ->
            if (call.method == "session.usage") """{"account_lines":["5h window: 40% used"],"credits_lines":["Credits: 12.50"]}""" else null
        })

        assertEquals(listOf("5h window: 40% used", "Credits: 12.50"), chat.accountLimits())
        assertTrue("session.account_usage" !in methods())
    }

    private companion object {
        const val AUTHORITY_READY =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

        /** Not in the session authority's handlers (gateway/session_controls.py `handlers`). */
        val LEGACY = setOf("session.title", "session.compress", "session.status", "session.usage")

        /** Only on the session authority. */
        val CANONICAL = setOf("session.mutate", "session.account_usage")

        /**
         * -32601 as hermes-agent v0.23.4 answers it: gateway/session_controls.py `dispatch` with
         * tui_gateway/contracts/registry.py `unknown_method_message` on the authority; the legacy dispatcher has no
         * `data.reason`.
         */
        fun FakeGateway.Call.unknown(withReason: Boolean) =
            error(-32601, "unknown method: $method — not available on this Hermes backend", if (withReason) "unknown_method" else null)

        /** A `session.mutate` refused for stale fences, carrying the current ones (gateway/session_mutations.py). */
        fun FakeGateway.Call.fenceRefusal(reason: String, revision: Long, generation: Long) =
            """{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"$reason",""" +
                """"data":{"reason":"$reason","revision":$revision,"execution_generation":$generation}}}"""
    }
}
