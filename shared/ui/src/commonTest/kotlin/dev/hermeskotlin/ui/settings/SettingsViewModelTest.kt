package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val gatewayA = SavedGateway("https://a.example.com")
    private val gatewayB = SavedGateway("https://b.example.com")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private object NoopPushKeys : PushKeys {
        override val deviceId: String = "test"
        override fun registration(name: String): PushRegistration = error("unused")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway): Boolean = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String): Set<String> = emptySet()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    private fun viewModel(
        statusHandler: suspend (host: String) -> Pair<HttpStatusCode, String>,
        meHandler: suspend (host: String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"display_name":"User"}""" },
    ): SettingsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.encodedPath == "/api/status" -> {
                    val (code, body) = statusHandler(request.url.host)
                    respond(body, code, json)
                }
                request.url.encodedPath == "/api/auth/me" -> {
                    val (code, body) = meHandler(request.url.host)
                    respond(body, code, json)
                }
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val store = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(store)
        val client = createHttpClient(MockEngine(config), cookies)
        val auth = AuthApi(client, cookies)
        val probe = GatewayProbe(client)
        val scope = CoroutineScope(dispatcher)
        val connection = GatewayConnection(auth, { _, _ -> error("no socket") }, scope)
        val settingsStore = SettingsStore(store, scope)
        val push = PushSetup(PushApi(connection), NoopPushKeys, connection, GatewayRepository(store), settingsStore, scope) { "Test" }
        return SettingsViewModel(
            store = settingsStore,
            auth = auth,
            probe = probe,
            connectionCheck = ConnectionCheck(probe, auth, { _, _ -> error("no socket") }),
            push = push,
            wallpapers = WallpaperStore(InMemoryBlobFile(), scope),
            profilesApi = ProfilesApi(client),
            sessionsApi = SessionsApi(client),
            nextPrompt = NextPromptApi(client, PluginsApi(client), connection),
        )
    }

    @Test
    fun failedProbeStopsCheckingAndLeavesVersionUnknown() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(
            statusHandler = {
                gate.await()
                HttpStatusCode.BadGateway to ""
            },
        )

        vm.bind(gatewayA)
        assertTrue(vm.gateway.value.checkingVersion)

        gate.complete(Unit)
        runCurrent()

        assertFalse(vm.gateway.value.checkingVersion)
        assertNull(vm.gateway.value.version)
    }

    @Test
    fun reachableGatewayWithoutVersionStopsChecking() = runTest(dispatcher) {
        val vm = viewModel(
            statusHandler = { HttpStatusCode.OK to """{"auth_required":true,"auth_providers":["basic"]}""" },
        )

        vm.bind(gatewayA)
        runCurrent()

        assertFalse(vm.gateway.value.checkingVersion)
        assertNull(vm.gateway.value.version)
    }

    @Test
    fun switchingGatewayCancelsPriorProbeAndMeAndResetsCheck() = runTest(dispatcher) {
        val gateA = CompletableDeferred<Unit>()
        val vm = viewModel(
            statusHandler = { host ->
                if (host == gatewayA.gatewayUrl.host) {
                    gateA.await()
                    HttpStatusCode.OK to """{"version":"0.1.0","auth_required":true,"auth_providers":["basic"]}"""
                } else {
                    HttpStatusCode.OK to """{"version":"0.9.0","auth_required":true,"auth_providers":["basic"]}"""
                }
            },
            meHandler = { host ->
                if (host == gatewayA.gatewayUrl.host) {
                    gateA.await()
                    HttpStatusCode.OK to """{"display_name":"OldUser"}"""
                } else {
                    HttpStatusCode.OK to """{"display_name":"NewUser"}"""
                }
            },
        )

        vm.bind(gatewayA)
        vm.runConnectionCheck()
        assertTrue(vm.check.value.running)

        vm.bind(gatewayB)
        gateA.complete(Unit)
        runCurrent()

        assertFalse(vm.check.value.running)
        assertTrue(vm.check.value.results.isEmpty())
        assertEquals("0.9.0", vm.gateway.value.version)
        assertEquals("NewUser", vm.gateway.value.userLabel)
        assertFalse(vm.gateway.value.checkingVersion)
    }
}
