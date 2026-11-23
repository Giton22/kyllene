package dev.hermeskotlin.ui.pet

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.pet.PetChoice
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PetControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class PetSocket(
        private val scope: CoroutineScope,
        private val answer: suspend (String, JsonObject) -> String,
    ) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming: Flow<String> = inbound.receiveAsFlow()
        override suspend fun send(text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            val id = msg["id"] ?: return
            val method = msg["method"]?.jsonPrimitive?.content ?: return
            val params = msg["params"]?.jsonObject ?: JsonObject(emptyMap())
            scope.launch {
                val reply = answer(method, params)
                inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$reply}""")
            }
        }
        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private suspend fun controller(scope: CoroutineScope, answer: suspend (String, JsonObject) -> String): PetController {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json) }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> PetSocket(scope, answer) }, scope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        return PetController(PetApi(connection), scope)
    }

    @Test
    fun cancellingAdoptClearsAdoptingState() = runTest(dispatcher) {
        val selectGate = CompletableDeferred<Unit>()
        val controller = controller(backgroundScope) { method, _ ->
            when (method) {
                "pet.select" -> {
                    selectGate.await()
                    """{"ok":true,"displayName":"Mochi"}"""
                }
                else -> """{"enabled":false}"""
            }
        }
        controller.bind("alpha")
        val pet = PetChoice("mochi", "Mochi", installed = true, spritesheetUrl = "", generated = false)

        val job = launch { controller.adopt(pet) }
        assertEquals("mochi", controller.gallery.value.adopting)

        job.cancel()
        runCurrent()
        assertNull(controller.gallery.value.adopting)
        selectGate.complete(Unit)
    }

    @Test
    fun loadGalleryCancelsPriorCallAndDropsStaleProfileResult() = runTest(dispatcher) {
        val alphaGate = CompletableDeferred<Unit>()
        val controller = controller(backgroundScope) { method, params ->
            when (method) {
                "pet.gallery" -> {
                    val profile = params["profile"]?.jsonPrimitive?.contentOrNull
                    if (profile == "alpha") {
                        alphaGate.await()
                        """{"enabled":true,"active":"cat","pets":[{"slug":"cat","displayName":"Cat","installed":true}]}"""
                    } else {
                        """{"enabled":true,"active":"dog","pets":[{"slug":"dog","displayName":"Dog","installed":true}]}"""
                    }
                }
                else -> """{"enabled":false}"""
            }
        }
        controller.bind("alpha")
        controller.loadGallery()

        controller.bind("beta")
        controller.loadGallery()
        alphaGate.complete(Unit)
        runCurrent()

        assertFalse(controller.gallery.value.loading)
        assertEquals("dog", controller.gallery.value.gallery?.active)
    }
}
