package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModelStore
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.*
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.*
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.*
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopAssistantAccountTest {
    @Test fun desktopSettingsHaveNoAdditionalAssistantAccountLookup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val disk = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(disk)
        var identityReads = 0
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val body = if (request.url.encodedPath.endsWith("/auth/me")) {
                    identityReads++
                    """{"user_id":"desktop-user"}"""
                } else if (request.url.encodedPath.endsWith("/plugins")) "[]" else "{}"
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }), cookies)
        val auth = AuthApi(client, cookies)
        val settings = SettingsStore(disk, backgroundScope)
        val probe = GatewayProbe(client)
        val connection = GatewayConnection(auth, { _, _ -> error("no socket") }, backgroundScope)
        val models = ViewModelStore()
        try {
            val vm = SettingsViewModel(settings, auth, probe, ConnectionCheck(probe, auth, { _, _ -> error("no socket") }),
                PushSetup(PushApi(connection), NoPushKeys, connection, GatewayRepository(disk), settings, backgroundScope) { "Synthetic desktop" },
                WallpaperStore(InMemoryBlobFile(), backgroundScope), ProfilesApi(client), SessionsApi(client), NextPromptApi(client, PluginsApi(client)))
            models.put("settings", vm)
            val gateway = SavedGateway("https://example.com")
            vm.bind(gateway)
            runCurrent()
            assertEquals(1, identityReads, "The ordinary Settings account row still loads")
            assertNull(vm.assistantUser(gateway))
            runCurrent()
            assertEquals(1, identityReads, "The phone-only assistant adds no desktop request")
        } finally {
            models.clear()
            connection.stop()
            client.close()
            Dispatchers.resetMain()
        }
    }

    private object NoPushKeys : PushKeys {
        override val deviceId = "test"
        override fun registration(name: String) = error("no registration")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }
}
