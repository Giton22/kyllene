package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class UsageControllerTest {

    @Test
    fun contextFractionGuardsAgainstZeroAndNegativeMax() {
        assertEquals(0f, contextFraction(0L, 0L))
        assertFalse(contextFraction(0L, 0L).isNaN())
        assertEquals(0f, contextFraction(100L, 0L))
        assertEquals(0f, contextFraction(50L, -10L))
        assertEquals(0.5f, contextFraction(50L, 100L))
        assertEquals(1f, contextFraction(200L, 100L))
    }

    @Test
    fun newerLoadCancelsAndDropsStaleTotals() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val firstGate = CompletableDeferred<Unit>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val config = MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                when (request.url.encodedPath) {
                    "/api/sessions/s1" -> {
                        firstGate.await()
                        respond("""{"id":"s1","model":"old-model","input_tokens":10,"output_tokens":5}""", HttpStatusCode.OK, json)
                    }
                    "/api/sessions/s2" ->
                        respond("""{"id":"s2","model":"new-model","input_tokens":200,"output_tokens":100}""", HttpStatusCode.OK, json)
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            }
        }
        val client = createHttpClient(MockEngine(config), PersistentCookiesStorage(InMemoryKeyValueStore()))
        val controller = UsageController(SessionsApi(client), backgroundScope)
        val gateway = SavedGateway("https://hermes.example.ts.net").gatewayUrl

        controller.load(gateway, "s1", profile = null, chat = null)
        runCurrent()
        assertEquals(true, controller.state.value.loading)

        controller.load(gateway, "s2", profile = null, chat = null)
        runCurrent()
        assertEquals("new-model", assertNotNull(controller.state.value.totals).model)
        assertFalse(controller.state.value.loading)

        // Even if the first request completes late, it must not overwrite the newer load's state.
        firstGate.complete(Unit)
        runCurrent()
        assertEquals("new-model", assertNotNull(controller.state.value.totals).model)
    }
}
