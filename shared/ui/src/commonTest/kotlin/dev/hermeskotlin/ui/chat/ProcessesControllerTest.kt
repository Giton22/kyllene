package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sheet's bookkeeping: what a poll that didn't answer keeps, on a chat that never connects. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessesControllerTest {

    private val process = BackgroundProcess("proc_1", "fixture build", null, null, true, null, null, "")
    private val subagent = SubagentRow(key = "fixture", goal = "Check documentation links", status = SubagentStatus.Running)

    private fun TestScope.chat(id: String): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { respond("{}") }, cookies)
        val chats = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, chats)
        return ChatSession(SavedGateway("https://hermes.example.ts.net").gatewayUrl, id, "Chat", connection, SessionsApi(client), chats)
    }

    @Test
    fun aPollThatDidntAnswerKeepsTheLastLists() {
        val shown = ProcessesState().withProcesses(listOf(process)).withSubagents(listOf(subagent))
        val missed = shown.withProcesses(null).withSubagents(null)
        assertEquals(listOf(process), missed.processes)
        assertEquals(listOf(subagent), missed.subagents)
        assertFalse(missed.unavailable || missed.subagentsUnavailable)
    }

    @Test
    fun withNoAnswerYetAMissedPollSaysUnavailableUntilOneComes() {
        val missed = ProcessesState().withProcesses(null).withSubagents(null)
        assertTrue(missed.unavailable && missed.subagentsUnavailable)
        val answered = missed.withProcesses(emptyList()).withSubagents(emptyList())
        assertFalse(answered.unavailable || answered.subagentsUnavailable)
    }

    @Test
    fun aChatThatIsntAttachedIsUnavailableAndStoppingItsProcessDoesNothing() = runTest(StandardTestDispatcher()) {
        val chats = MutableStateFlow<ChatSession?>(chat("a"))
        val controller = ProcessesController(backgroundScope)
        controller.start(chats)
        runCurrent()
        assertTrue(controller.state.value.unavailable && controller.state.value.subagentsUnavailable)
        // Nothing listed says this process runs in the chat shown.
        controller.kill(chats, process)
        assertTrue(controller.state.value.stopping.isEmpty())
    }
}
