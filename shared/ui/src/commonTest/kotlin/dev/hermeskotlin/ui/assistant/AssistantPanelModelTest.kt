package dev.hermeskotlin.ui.assistant

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.AssistantTarget

class AssistantPanelModelTest {

    private val home = SavedGateway("http://100.64.0.1:9119")
    private val work = SavedGateway("https://hermes.work.example")

    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)

    // The connection waits on its ticket for good (a retry loop would keep the test clock busy, so a missed
    // chat would hang instead of time out); every other call fails.
    private var account = "sample-user"
    private var missing = false
    private var authStatus = HttpStatusCode.OK
    private var totalsStatus = HttpStatusCode.OK
    private var authGate: CompletableDeferred<Unit>? = null
    private val totalsStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var totalsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val reads = mutableListOf<String?>()
    private val client = createHttpClient(
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("ws-ticket")) awaitCancellation()
            when {
                request.url.encodedPath.endsWith("/auth/me") -> { authGate?.await(); respond("""{"user_id":"$account"}""", authStatus, headers = io.ktor.http.headersOf("Content-Type", "application/json")) }
                request.url.encodedPath.endsWith("/sample-chat") -> {
                    reads += request.url.parameters["profile"]
                    totalsStarted.complete(Unit)
                    totalsGate?.await()
                    respond(if (missing) "{}" else """{"id":"sample-chat"}""", if (missing) HttpStatusCode.NotFound else totalsStatus,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"))
                }
                else -> respond("", HttpStatusCode.ServiceUnavailable)
            }
        },
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    private object Silent : VoiceRecorder, SpeechPlayer {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) =
            Recording(byteArrayOf(), "audio/wav", false)

        override fun finish() = Unit

        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun TestScope.model(settings: SettingsStore? = null): AssistantPanelModel {
        val connection = GatewayConnection(auth, openSocket = { _, _ -> error("no socket in tests") }, scope = backgroundScope)
        return AssistantPanelModel(
            gateways, auth, ProfileStore(store), connection, SessionsApi(client), AudioApi(client), Silent, Silent, backgroundScope, settings = settings,
        )
    }

    private suspend fun signedIn(gateway: SavedGateway) =
        cookies.addCookie(Url(gateway.url + "/"), Cookie("hermes_session_at", "token", path = "/"))

    @Test
    fun aCallUpAfterHeraldSwitchedGatewaysStartsTheChatOnTheNewOne() = runTest {
        gateways.save(home)
        signedIn(home)
        gateways.save(work)
        signedIn(work)
        gateways.select(home.url)
        val model = model()
        val first = model.session.first { it != null }
        model.checkingTarget.first { !it }

        // Herald switches to the other gateway while the panel is hidden, then the user calls it up again.
        gateways.select(work.url)
        model.begin(expectText = false, expectScreenshot = false)

        val next = model.session.first { it != null && it !== first }
        assertNotNull(next)
        assertNotSame(first, next)
        model.close()
    }

    @Test
    fun aCallUpOnTheSameGatewayKeepsAnUnusedChat() = runTest {
        gateways.save(home)
        signedIn(home)
        val model = model()
        val first = model.session.first { it != null }
        model.checkingTarget.first { !it }

        model.begin(expectText = false, expectScreenshot = false)

        assertEquals(first, model.session.value)
        model.close()
    }
    @Test
    fun fixedTargetSurvivesRepeatedInvocationsAndModelRecreation() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat", "Example")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        val first = model.session.value
        assertEquals("sample-chat", first?.state?.value?.storedSessionId)
        model.begin(true, true)
        model.checkingTarget.first { !it }
        assertEquals(first, model.session.value)
        model.setIncludeScreen(true)
        model.onScreenshot(model.callUp, byteArrayOf(1))
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertNull(model.screen.value.screenshot)
        assertEquals(false, model.includeScreen.value)
        model.close()
        runCurrent()
        val reopened = model(SettingsStore(store, backgroundScope))
        reopened.checkingTarget.first { !it }
        assertEquals("sample-chat", reopened.session.value?.state?.value?.storedSessionId)
        reopened.close()
    }

    @Test
    fun profileAndAccountChangesCannotReuseTheSelectedChat() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        ProfileStore(store).set(home.gatewayUrl, "other")
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertEquals("other", model.session.value?.profile)
        assertNull(model.session.value?.state?.value?.storedSessionId)
        ProfileStore(store).set(home.gatewayUrl, null)
        account = "another-user"
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertNull(model.session.value?.state?.value?.storedSessionId)
        model.close()
    }

    @Test
    fun missingTargetShowsFailureAndDoesNotCreateAFreshChat() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("work" to AssistantTarget(account, "sample-chat")))) }
        ProfileStore(store).set(home.gatewayUrl, "work")
        missing = true
        val model = model(settings)
        model.checkingTarget.first { !it }
        assertTrue(model.targetUnavailable.value)
        assertNull(model.session.value)
        assertEquals(listOf<String?>("work"), reads)
        model.composer.edit { append("Harmless sample") }
        model.send()
        runCurrent()
        assertNull(model.session.value)
        model.close()
    }

    @Test
    fun profileSwitchDuringTargetReadDropsTheOldTarget() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        totalsGate = gate
        val model = model(settings)
        totalsStarted.await()
        assertEquals(listOf<String?>(null), reads)
        ProfileStore(store).set(home.gatewayUrl, "other")
        gate.complete(Unit)
        model.checkingTarget.first { !it }
        assertEquals("other", model.session.value?.profile)
        assertNull(model.session.value?.state?.value?.storedSessionId)
        model.close()
    }

    @Test
    fun clearingTargetReturnsToNewChatAndGatewaySwitchCannotReuseIt() = runTest {
        gateways.save(home)
        signedIn(home)
        signedIn(work)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        gateways.save(work)
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertNull(model.session.value?.state?.value?.storedSessionId)
        gateways.select(home.url)
        settings.update { it.copy(assistantTargets = emptyMap()) }
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertNull(model.session.value?.state?.value?.storedSessionId)
        model.close()
    }

    @Test
    fun sameContextRevalidationKeepsTheChatAndDisablesUnsafeActions() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        val existing = model.session.value
        assertTrue(model.targetVerified.value)
        authGate = CompletableDeferred()
        model.begin(false, false)
        assertEquals(AssistantPhase.Ready, model.phase.value)
        assertEquals(existing, model.session.value)
        assertFalse(model.targetVerified.value)
        model.composer.setTextAndPlaceCursorAtEnd("Keep these words")
        model.send()
        model.toggleDictation()
        assertEquals("Keep these words", model.composer.text.toString())
        assertFalse(model.voice.dictation.value.active)
        authStatus = HttpStatusCode.ServiceUnavailable
        authGate!!.complete(Unit)
        model.checkingTarget.first { !it }
        assertEquals(existing, model.session.value)
        assertFalse(model.targetUnavailable.value)
        assertFalse(model.targetVerified.value)
        authStatus = HttpStatusCode.OK
        totalsStatus = HttpStatusCode.ServiceUnavailable
        model.retryTarget()
        model.checkingTarget.first { !it }
        assertEquals(existing, model.session.value)
        assertFalse(model.targetVerified.value)
        totalsStatus = HttpStatusCode.OK
        val shows = model.shows.value
        model.retryTarget()
        model.checkingTarget.first { !it }
        assertEquals(existing, model.session.value)
        assertEquals(shows, model.shows.value)
        assertTrue(model.targetVerified.value)
        model.close()
    }

    @Test
    fun definiteTargetAccessLossClearsTheChatAndBlocksDictationAndSend() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        totalsStatus = HttpStatusCode.Forbidden
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertNull(model.session.value)
        assertTrue(model.targetUnavailable.value)
        assertFalse(model.targetVerified.value)
        model.composer.setTextAndPlaceCursorAtEnd("Do not discard")
        model.toggleDictation()
        model.send()
        assertFalse(model.voice.dictation.value.active)
        assertEquals("Do not discard", model.composer.text.toString())
        model.close()
    }

    @Test fun expiredIdentityClearsTheOldChatAndRequiresSignIn() = runTest {
        gateways.save(home)
        signedIn(home)
        val settings = SettingsStore(store, backgroundScope)
        runCurrent()
        settings.update { it.copy(assistantTargets = mapOf(home.url to mapOf("" to AssistantTarget(account, "sample-chat")))) }
        runCurrent()
        val model = model(settings)
        model.checkingTarget.first { !it }
        assertNotNull(model.session.value)
        authStatus = HttpStatusCode.Unauthorized
        model.begin(false, false)
        model.checkingTarget.first { !it }
        assertEquals(AssistantPhase.SignedOut, model.phase.value)
        assertNull(model.session.value)
        assertFalse(model.targetVerified.value)
        model.close()
    }

    @Test fun profileChangeWhileWaitingForCaptureRestoresDraftWithoutAnotherCallUp() = captureContextChange(false)
    @Test fun gatewayChangeWhileWaitingForCaptureRestoresDraftWithoutAnotherCallUp() = captureContextChange(true)

    private fun captureContextChange(changeGateway: Boolean) = runTest {
        gateways.save(home)
        signedIn(home)
        val model = model()
        model.checkingTarget.first { !it }
        model.begin(expectText = true, expectScreenshot = true)
        model.checkingTarget.first { !it }
        val capture = model.callUp
        val existing = model.session.value
        val shows = model.shows.value
        model.setIncludeScreen(true)
        model.composer.setTextAndPlaceCursorAtEnd("Unsent question")
        model.send()
        runCurrent()
        model.composer.setTextAndPlaceCursorAtEnd("New draft")
        if (changeGateway) { gateways.save(work); signedIn(work) }
        else ProfileStore(store).set(home.gatewayUrl, "other")
        model.onScreenText(capture, "Synthetic app", emptyList())
        model.onScreenshot(capture, byteArrayOf(1, 2, 3))
        model.session.first { it != null && it !== existing }
        model.checkingTarget.first { !it }
        assertEquals(if (changeGateway) null else "other", model.session.value?.profile)
        assertEquals("Unsent question\nNew draft", model.composer.text.toString())
        assertEquals(shows, model.shows.value)
        assertFalse(model.includeScreen.value)
        assertTrue(model.screen.value.context.isEmpty)
        assertFalse(model.voice.dictation.value.active)
        assertNull(model.session.value?.state?.value?.storedSessionId)
        model.onScreenshot(capture, byteArrayOf(9))
        assertTrue(model.screen.value.context.isEmpty, "Late capture from the old context is ignored")
        model.close()
    }

    @Test
    fun aSendThatCantGoLeavesTheComposerAsTyped() = runTest {
        val model = model()
        // No gateway, so no chat to send to: the empty item Enter left stays until the send goes ahead.
        model.composer.setTextAndPlaceCursorAtEnd("- milk\n- ")
        model.send()
        assertEquals("- milk\n- ", model.composer.text.toString())
        model.close()
    }
}
