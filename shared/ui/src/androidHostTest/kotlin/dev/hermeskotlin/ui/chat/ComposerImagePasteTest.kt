package dev.hermeskotlin.ui.chat

import android.content.ClipDescription
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerImagePasteTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway = SavedGateway("https://fixture.example")
    private val socket = FixtureSocket()
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
    fun sendDiscardsAnUnfinishedImagePaste() {
        val input = showChat()
        input.commitText("Look at this", 1)
        readSlowImage(input) {
            composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            composeRule.waitUntil(5_000) { "prompt.submit" in socket.methods }
        }

        assertTrue("image.attach_bytes" !in socket.methods)
        assertEquals("", viewModel.composer.text.toString())
        composeRule.onNodeWithContentDescription("Remove copied.jpg").assertDoesNotExist()
        assertNull(viewModel.attachmentError.value)
    }

    @Test
    fun openingModelPickerKeepsAnAcceptedPaste() {
        val input = showChat()
        readSlowImage(input) {
            composeRule.onNodeWithContentDescription("Change model", substring = true).performClick()
            composeRule.onNodeWithText("Model").assertExists()
        }

        composeRule.onNodeWithText("Model").assertExists()
        composeRule.waitUntil(5_000) {
            composeRule.runOnIdle { viewModel.attachments.value.isNotEmpty() || viewModel.attachmentError.value != null }
        }
        assertNull(viewModel.attachmentError.value)
        assertEquals("copied.jpg", viewModel.attachments.value.single().name)
    }

    private fun showChat(): InputConnection {
        val store = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(store)
        val client = createHttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/auth/ws-ticket") {
                """{"ticket":"fixture","ttl_seconds":30}"""
            } else "{}"
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }, cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, scope)
        val sessions = SessionsApi(client)
        val host = ChatHost(connection, sessions, scope)
        val settings = SettingsStore(store, scope)
        startKoin {
            modules(module {
                single { settings }
                single { ProjectsApi(connection) }
                single { WallpaperStore(InMemoryBlobFile(), scope) }
            })
        }
        var root: View? = null
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
        val target = ChatTarget(gateway, "stored", "Paste test")
        composeRule.setContent {
            root = LocalView.current
            HermesTheme(ColorScheme.Light) {
                ChatScreen(target, {}, {}, null, { _, _ -> }, {}, viewModel)
            }
        }
        composeRule.waitUntil(5_000) { viewModel.state.value.model == "fixture-model" }
        composeRule.onNode(hasSetTextAction()).performClick()
        return composeRule.runOnIdle { assertNotNull(root?.onCreateInputConnection(EditorInfo())) }
    }

    private fun readSlowImage(input: InputConnection, whileReading: () -> Unit) {
        val uri = Uri.parse("content://clipboard.test/copied.png")
        val png = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Shadows.shadowOf(resolver).registerInputStreamSupplier(uri) {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the image read" }
            object : ByteArrayInputStream(png) {
                override fun close() {
                    super.close()
                    closed.countDown()
                }
            }
        }
        val content = InputContentInfo(uri, ClipDescription("Copied image", arrayOf("image/png")), null)
        try {
            composeRule.runOnIdle { assertTrue(input.commitContent(content, 0, null)) }
            composeRule.waitUntil(5_000) { started.count == 0L }
            whileReading()
        } finally {
            release.countDown()
        }
        composeRule.waitUntil(5_000) { closed.count == 0L }
        composeRule.waitForIdle()
    }

    private class FixtureSocket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming = inbound.receiveAsFlow()
        val methods = CopyOnWriteArrayList<String>()

        override suspend fun send(text: String) {
            val call = Json.parseToJsonElement(text).jsonObject
            val method = call["method"]?.jsonPrimitive?.content ?: return
            methods += method
            val id = call["id"] ?: return
            val result = when (method) {
                "session.resume" -> """{"session_id":"runtime","running":false,"info":{"model":"fixture-model"}}"""
                "image.attach_bytes" -> """{"attached":true,"path":"/tmp/copied.jpg"}"""
                "prompt.submit" -> """{"status":"started"}"""
                else -> "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }
}
