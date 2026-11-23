package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.RuntimeCheck
import dev.hermeskotlin.core.bots.SidebarModeStore
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gatewayA = GatewayUrl.parse("https://a.example.com")
    private val gatewayB = GatewayUrl.parse("https://b.example.com")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class BotsSocket(
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
                inbound.send(
                    if (reply.startsWith("!")) """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"${reply.drop(1)}"}}"""
                    else """{"jsonrpc":"2.0","id":$id,"result":$reply}""",
                )
            }
        }
        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private suspend fun viewModel(
        scope: CoroutineScope,
        onPatch: suspend () -> HttpStatusCode = { HttpStatusCode.OK },
        rpc: suspend (String, JsonObject) -> String = { method, _ ->
            when (method) {
                "profiles.list" -> """{"profiles":[]}"""
                else -> "{}"
            }
        },
    ): BotsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.encodedPath == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/mutation-snapshot") -> respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
                request.method == HttpMethod.Patch -> {
                    val code = onPatch()
                    respond(if (code == HttpStatusCode.OK) "{}" else """{"detail":"Busy"}""", code, json)
                }
                request.url.encodedPath == "/api/cron/jobs" -> respond("[]", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val store = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(store)
        val client = createHttpClient(MockEngine(config), cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { _, _ -> BotsSocket(scope, rpc) }, scope)
        connection.start(gatewayA)
        connection.state.first { it is ConnectionState.Connected }
        val sessions = SessionsApi(client)
        val host = ChatHost(connection, sessions, scope)
        val api = BotsApi(connection)
        val health = BotHealth(connection, host, scope) { RuntimeCheck(ok = true) }
        val attention = AttentionTracker(connection, host, ActiveSessions(connection, scope), scope)
        return BotsViewModel(
            api = api,
            chats = BotChats(api),
            connection = connection,
            seenStore = SeenStore(store) { 0.0 },
            modes = SidebarModeStore(store),
            sessions = sessions,
            profiles = ProfilesApi(client),
            health = health,
            cron = CronApi(client),
            models = ModelsApi(connection),
            host = host,
            attention = attention,
        )
    }

    @Test
    fun startFreshGuardsConcurrentCallsAndClearsOpeningOnFailure() = runTest(dispatcher) {
        val patchGate = CompletableDeferred<Unit>()
        var patches = 0
        val vm = viewModel(
            scope = backgroundScope,
            onPatch = {
                patches++
                patchGate.await()
                HttpStatusCode.ServiceUnavailable
            },
        )
        vm.bind(gatewayA)
        val bot = Bot(name = "scribe", canonicalSession = BotSession(id = "s1"))

        vm.startFresh(bot) {}
        vm.startFresh(bot) {}
        assertEquals(1, patches)
        assertEquals("scribe", vm.state.value.opening)

        patchGate.complete(Unit)
        runCurrent()

        assertNull(vm.state.value.opening)
    }

    @Test
    fun staleRefreshErrorAndSetModelFromPreviousGatewayAreDropped() = runTest(dispatcher) {
        val rosterGate = CompletableDeferred<Unit>()
        val modelGate = CompletableDeferred<Unit>()
        var firstRoster = true
        val vm = viewModel(
            scope = backgroundScope,
            rpc = { method, _ ->
                when (method) {
                    "profiles.list" -> {
                        if (firstRoster) {
                            firstRoster = false
                            rosterGate.await()
                            "!Old gateway boom"
                        } else {
                            """{"profiles":[]}"""
                        }
                    }
                    "profiles.configure" -> {
                        modelGate.await()
                        "!Model save boom"
                    }
                    else -> "{}"
                }
            },
        )
        vm.bind(gatewayA)
        val bot = Bot(name = "scribe")
        vm.loadModels(bot)
        vm.setModel(bot, ModelOption(id = "gpt-5", provider = "openai"))

        vm.bind(gatewayB)
        rosterGate.complete(Unit)
        modelGate.complete(Unit)
        runCurrent()

        assertNull(vm.state.value.error)
        assertNull(vm.modelPicker.value.saveError)
        assertFalse(vm.modelPicker.value.saving)
        assertTrue(vm.state.value.bots.isEmpty())
    }
}
