package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.FakeGateway.Call
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `/stop` stops only this chat's background processes, in the shape each gateway takes: `process.stop` with the
 * chat's `session_id` on the session authority (gateway/session_ancillary.py stop_processes, `invalid_params`
 * without it), and `process.list` + `process.kill` on the legacy sidecar, whose `process.stop` is the registry-wide
 * `kill_all` (tui_gateway/methods_tools.py).
 */
class StopProcessScopeTest {

    private val url = FakeGateway.URL

    private companion object {
        const val SILENT = "silent"
    }

    private val authorityReady =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""

    /** A chat attached as `rt1` on a gateway that answers with [answer]: `{}` for a call it leaves out, none for [SILENT]. */
    private suspend fun chatOn(
        scope: CoroutineScope,
        authority: Boolean,
        running: Boolean = false,
        profile: String? = null,
        answer: (Call) -> String?,
    ): Pair<FakeGateway, ChatSession> {
        val gw = FakeGateway(scope, reconnects = false).apply {
            ready = { if (authority) authorityReady else FakeTransport.READY }
            this.answer = { call ->
                when (call.method) {
                    "session.resume" -> """{"session_id":"rt1","running":$running}"""
                    "session.interrupt" -> """{"status":"interrupted"}"""
                    else -> answer(call).let { if (it == SILENT) null else it ?: "{}" }
                }
            }
            http = { json("""{"session_id":"stored-1","messages":[]}""") }
            start(awaitConnected = true)
        }
        val chat = ChatSession(url, "stored-1", "Busy", gw.connection, SessionsApi(gw.client), scope, profile = profile)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.running == running }
        return gw to chat
    }

    private fun ChatSession.lastOutput() = (state.value.messages.last() as ChatMessage.Command).output

    private fun ChatSession.lastFailed() = (state.value.messages.last() as ChatMessage.Command).failed

    /** Upstream's authority `stop_processes`: no `session_id` is `invalid_params`. */
    private fun authorityStop(call: Call, killed: Int = 1) =
        if (call.param("session_id") == null) "error:4001:invalid_params" else """{"killed":$killed,"delegations":0}"""

    @Test
    fun stopOnSessionAuthoritySendsThisChatsSessionId() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = true) { call ->
            if (call.method == "process.stop") authorityStop(call) else null
        }

        chat.stopEverything()

        val stop = gw.socket.sent.value.last { it.isCall("process.stop") }
        assertEquals("rt1", stop.param("session_id"), "process.stop on the session authority must name this chat")
        assertFalse(gw.socket.sent.value.any { it.isCall("process.list") || it.isCall("process.kill") })
        assertEquals("Stopped 1 background process.", chat.lastOutput())
    }

    @Test
    fun stopOnSessionAuthorityKeepsStoppedTheReply() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = true, running = true) { call ->
            if (call.method == "process.stop") authorityStop(call, killed = 2) else null
        }

        chat.stopEverything()

        assertTrue(gw.socket.sent.value.any { it.isCall("session.interrupt") })
        assertEquals("Stopped the reply.\nStopped 2 background processes.", chat.lastOutput())
        assertFalse(chat.lastFailed())
    }

    @Test
    fun stopKeepsStoppedTheReplyWhenProcessesCannotBeStopped() = runTest(timeout = 2.seconds) {
        val (_, chat) = chatOn(backgroundScope, authority = true, running = true) { call ->
            if (call.method == "process.stop") "error:4003:permission_denied" else null
        }

        chat.stopEverything()

        val output = chat.lastOutput()
        assertTrue(output.startsWith("Stopped the reply.\nCouldn't stop the background processes:"), output)
        assertFalse(chat.lastFailed())
    }

    @Test
    fun stopOnSessionAuthorityCarriesANamedProfile() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = true, profile = "work") { call ->
            if (call.method == "process.stop") authorityStop(call) else null
        }

        chat.stopEverything()

        val stop = gw.socket.sent.value.last { it.isCall("process.stop") }
        assertEquals("rt1", stop.param("session_id"))
        assertEquals("work", stop.param("profile"), "a named profile's chat is only on that profile's connection")
    }

    @Test
    fun stopOnLegacySidecarKillsOnlyThisChatsProcesses() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = false) { call ->
            when (call.method) {
                // tui_gateway/methods_tools.py: legacy process.stop is process_registry.kill_all(), every chat's.
                "process.stop" -> """{"killed":5}"""
                "process.list" -> """{"processes":[
                    {"session_id":"proc_a","command":"npm run dev","status":"running"},
                    {"session_id":"proc_c","command":"watch","status":"running"},
                    {"session_id":"proc_b","command":"old","status":"exited","exit_code":0}]}"""
                "process.kill" ->
                    if (call.param("process_id") == "proc_c") """{"status":"already_exited"}""" else """{"status":"killed"}"""
                else -> null
            }
        }

        chat.stopEverything()

        val sent = gw.socket.sent.value
        assertFalse(sent.any { it.isCall("process.stop") }, "legacy process.stop is kill_all: every chat's processes")
        assertEquals("rt1", sent.last { it.isCall("process.list") }.param("session_id"))
        val kills = sent.filter { it.isCall("process.kill") }
        assertEquals(listOf("proc_a", "proc_c"), kills.map { it.param("process_id") })
        assertTrue(kills.all { it.param("session_id") == "rt1" })
        // proc_c had already finished: only proc_a counts.
        assertEquals("Stopped 1 background process.", chat.lastOutput())
    }

    @Test
    fun stopOnLegacySidecarTriesEveryProcessWhenOneKillFails() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = false) { call ->
            when (call.method) {
                "process.list" -> """{"processes":[
                    {"session_id":"proc_a","command":"a","status":"running"},
                    {"session_id":"proc_b","command":"b","status":"running"},
                    {"session_id":"proc_c","command":"c","status":"running"},
                    {"session_id":"proc_d","command":"d","status":"running"}]}"""
                "process.kill" -> when (call.param("process_id")) {
                    "proc_a" -> "error:5010:boom"
                    // tools/process_registry.py kill_process answers a failed kill as a result.
                    "proc_d" -> """{"status":"error","error":"permission denied"}"""
                    else -> """{"status":"killed"}"""
                }
                else -> null
            }
        }

        chat.stopEverything()

        val kills = gw.socket.sent.value.filter { it.isCall("process.kill") }
        assertEquals(listOf("proc_a", "proc_b", "proc_c", "proc_d"), kills.map { it.param("process_id") })
        val output = chat.lastOutput()
        assertTrue(output.startsWith("Stopped 2 background processes.\nCouldn't stop every background process:"), output)
        assertFalse(chat.lastFailed())
    }

    @Test
    fun stopKeepsStoppedTheReplyWhenKillsRunPastTheTimeLimit() = runTest(timeout = 2.seconds) {
        // Every kill goes unanswered: three 30 s request timeouts are past /stop's own limit.
        val (_, chat) = chatOn(backgroundScope, authority = false, running = true) { call ->
            when (call.method) {
                "process.list" -> """{"processes":[
                    {"session_id":"proc_a","command":"a","status":"running"},
                    {"session_id":"proc_b","command":"b","status":"running"},
                    {"session_id":"proc_c","command":"c","status":"running"}]}"""
                "process.kill" -> SILENT
                else -> null
            }
        }

        chat.stopEverything()

        val output = chat.lastOutput()
        assertTrue(output.startsWith("Stopped the reply.\nCouldn't stop the background processes:"), output)
        assertFalse(chat.lastFailed())
    }

    @Test
    fun stopOnLegacySidecarWithNothingRunning()= runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = false) { call ->
            if (call.method == "process.list") """{"processes":[]}""" else null
        }

        chat.stopEverything()

        assertFalse(gw.socket.sent.value.any { it.isCall("process.stop") || it.isCall("process.kill") })
        assertEquals("No background processes were running.", chat.lastOutput())
    }

    @Test
    fun stopOnLegacySidecarDoesNotFallBackToKillAllWhenTheListFails() = runTest(timeout = 2.seconds) {
        val (gw, chat) = chatOn(backgroundScope, authority = false, running = true) { call ->
            if (call.method == "process.list") "error:5010:boom" else null
        }

        chat.stopEverything()

        assertFalse(gw.socket.sent.value.any { it.isCall("process.stop") || it.isCall("process.kill") })
        val output = chat.lastOutput()
        assertTrue(output.startsWith("Stopped the reply.\nCouldn't stop the background processes:"), output)
    }
}
