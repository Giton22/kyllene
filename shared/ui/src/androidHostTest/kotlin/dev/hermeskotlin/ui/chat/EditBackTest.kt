package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.PlatformBackHandler
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** On a phone, Back while editing a sent prompt leaves the edit, before the chat list behind the chat takes it (#560). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class EditBackTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway = SavedGateway("https://fixture.example")
    private lateinit var viewModel: ChatViewModel

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }).around(composeRule)

    @After
    fun tearDown() {
        composeRule.runOnIdle { models.clear() }
        scope.cancel()
        stopKoin()
    }

    @Test
    fun backLeavesAnEditBeforeTheChatList() {
        var listOpened by mutableStateOf(false)
        showChat { PlatformBackHandler(enabled = !listOpened) { listOpened = true } }
        composeRule.runOnIdle { viewModel.composer.edit { append("half typed") } }
        val prompt = composeRule.runOnIdle {
            assertNotNull(viewModel.state.value.messages.firstOrNull { it is ChatMessage.User }).key
        }
        composeRule.runOnIdle { viewModel.startEdit(prompt) }
        composeRule.waitForIdle()
        assertEquals(prompt, viewModel.editing.value)

        back()

        assertNull(viewModel.editing.value)
        assertEquals("half typed", viewModel.composer.text.toString())
        assertFalse(listOpened)

        back()

        assertEquals(true, listOpened)
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** The chat, with [behind] composed before it as Home's chat-list Back is. */
    private fun showChat(behind: @androidx.compose.runtime.Composable () -> Unit) {
        val store = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(store)
        val client = createHttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> """{"ticket":"fixture","ttl_seconds":30}"""
                "/api/sessions/stored/messages" -> """{"session_id":"stored","messages":[
                    {"id":1,"role":"user","content":"Say hi"},
                    {"id":2,"role":"assistant","content":"Hi"}]}"""
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }, cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> FixtureSocket() }, scope)
        val sessions = SessionsApi(client)
        val host = ChatHost(connection, sessions, scope)
        val settings = SettingsStore(store, scope)
        startKoin {
            modules(module {
                single { settings }
                single { GatewayRepository(InMemoryKeyValueStore()) }
                single { ProjectsApi(connection) }
                single { WallpaperStore(InMemoryBlobFile(), scope) }
            })
        }
        composeRule.runOnIdle {
            viewModel = ChatViewModel(
                connection, host, LastChatStore(store), DraftStore(store), ModelsApi(connection), MediaApi(client),
                SlashApi(connection), sessions, ProfilesApi(client), settings, PetApi(connection),
                JourneyApi(client), AudioApi(client),
                object : VoiceRecorder {
                    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) =
                        error("No recording in this test")
                    override fun finish() = Unit
                },
                object : SpeechPlayer { override suspend fun play(audio: SpokenAudio) = Unit },
                scope, BotsApi(connection), { null },
                object : VoiceKeepAlive {
                    override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = false
                    override fun release() = Unit
                },
                NoDeviceDictation, { _, _ -> null },
            )
            models.put("chat", viewModel)
            connection.start(gateway.gatewayUrl)
        }
        val target = ChatTarget(gateway, "stored", "Edit test")
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                behind()
                ChatScreen(target, {}, {}, null, { _, _ -> }, {}, viewModel)
            }
        }
        composeRule.waitUntil(5_000) {
            viewModel.state.value.let { it.model == "fixture-model" && it.historyLoaded && it.messages.isNotEmpty() }
        }
    }

    private class FixtureSocket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val call = Json.parseToJsonElement(text).jsonObject
            val method = call["method"]?.jsonPrimitive?.content ?: return
            val id = call["id"] ?: return
            val result = when (method) {
                "session.resume" -> """{"session_id":"runtime","running":false,"info":{"model":"fixture-model"}}"""
                else -> "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }
}
