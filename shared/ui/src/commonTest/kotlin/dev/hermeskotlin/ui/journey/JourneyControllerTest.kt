package dev.hermeskotlin.ui.journey

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val gatewayA = GatewayUrl.parse("https://a.example.com")
    private val gatewayB = GatewayUrl.parse("https://b.example.com")

    @Test
    fun switchingGatewayOrProfileCancelsPriorLoadAndDropsStaleResults() = runTest(dispatcher) {
        val gateA = CompletableDeferred<Unit>()
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            if (request.url.host == gatewayA.host) {
                gateA.await()
                respond("""{"nodes":[{"id":"old","label":"Stale"}]}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"nodes":[{"id":"new","label":"Fresh"}]}""", HttpStatusCode.OK, json)
            }
        }
        val controller = JourneyController(JourneyApi(createHttpClient(MockEngine(config))), backgroundScope)

        controller.load(gatewayA, "alpha")
        controller.load(gatewayB, "beta")
        gateA.complete(Unit)
        runCurrent()

        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
        assertEquals(listOf("new"), controller.state.value.graph?.nodes?.map { it.id })
    }
}
