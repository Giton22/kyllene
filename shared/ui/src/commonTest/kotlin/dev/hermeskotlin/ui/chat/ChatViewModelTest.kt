package dev.hermeskotlin.ui.chat

import androidx.lifecycle.ViewModelStore
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.CaughtUp
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Test
    fun dismissIgnoresRetiredOwnersAndCardsFromADifferentChat() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "first", "First")
        vm.open(target)
        val first = assertNotNull(host.session.value)
        first.showCommandOutput("/btw sample question", "Sample answer.")
        val oldCard = first.state.value.messages.filterIsInstance<ChatMessage.Command>().single()

        host.invalidateOwner()
        vm.dismissSideCard(oldCard)
        assertTrue(oldCard in first.state.value.messages)

        val other = target.copy(storedSessionId = "second", title = "Second")
        host.open(gateway.gatewayUrl, "second", "Second")
        vm.dismissSideCard(oldCard)
        assertTrue(oldCard in first.state.value.messages)

        vm.open(other)
        val second = assertNotNull(host.session.value)
        second.showCommandOutput("/btw sample question", "Sample answer.")
        val newCard = second.state.value.messages.filterIsInstance<ChatMessage.Command>().single()
        assertEquals(oldCard.key, newCard.key)
        vm.dismissSideCard(oldCard)
        assertTrue(newCard in second.state.value.messages)
        vm.dismissSideCard(newCard)
        assertTrue(second.state.value.messages.none { it is ChatMessage.Command })
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val scope = CoroutineScope(dispatcher)
    private val viewModels = ViewModelStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    /** Stops the chat's own coroutines while the test Main is still set, so none outlive the test. */
    @AfterTest fun tearDown() {
        viewModels.clear()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private object NoRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
            error("not recording in tests")

        override fun finish() = Unit
    }

    private object NoPlayer : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    /**
     * A gateway socket that says it's ready and answers every call with an empty result, or with the result [answer]
     * gives for its method; on the session [authority] when asked.
     */
    private class ReadyGateway(
        private val sent: MutableList<String>,
        authority: Boolean = false,
        private val answer: (String) -> String? = { null },
    ) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            val ready = if (authority) """{"session_authority":true}""" else "{}"
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":$ready}}""")
        }

        /** A frame from the gateway. */
        fun push(frame: String) {
            inbound.trySend(frame)
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            sent += text
            val call = Json.parseToJsonElement(text).jsonObject
            val id = call["id"] ?: return
            val method = call["method"]?.jsonPrimitive?.content.orEmpty()
            val result = answer(method)
            // Held: the test answers it later by its id, with [push].
            if (result == HOLD) return
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":${result ?: "{}"}}""")
        }

        companion object {
            /** What [answer] gives for a call the gateway doesn't answer yet. */
            const val HOLD = "hold"
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    /** The connection of the last [viewModel]; [GatewayConnection.start] connects it to a [ReadyGateway]. */
    private lateinit var connection: GatewayConnection

    /** The socket of the last [viewModel]'s connection. */
    private var socket: ReadyGateway? = null

    /** Every frame the app sent on the gateway socket. */
    private val sent = mutableListOf<String>()

    private fun viewModel(drafts: DraftStore = DraftStore(InMemoryKeyValueStore()), fileReads: MutableList<Pair<String, String?>>? = null,
        seenStore: SeenStore? = null,
        readState: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
        authority: Boolean = false,
        answer: (String) -> String? = { null },
    ): Pair<ChatViewModel, ChatHost> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { request ->
            readState(request)?.let { return@addHandler it }
            if (request.url.encodedPath.startsWith("/api/fs/")) {
                fileReads?.add(request.url.host to request.url.parameters["profile"])
                if (request.url.encodedPath.endsWith("read-text")) respond("""{"text":"<p>widget</p>","byte_size":13,"truncated":false,"binary":false}""", HttpStatusCode.OK, json)
                else respond("<p>widget</p>", HttpStatusCode.OK)
            } else if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
            else respond("{}", HttpStatusCode.OK, json)
        }
        val client = createHttpClient(MockEngine(engine), cookies)
        connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> ReadyGateway(sent, authority, answer).also { socket = it } }, scope)
        val sessions = SessionsApi(client)
        val chatHost = ChatHost(connection, sessions, scope)
        val vm = ChatViewModel(
            connection, chatHost, LastChatStore(InMemoryKeyValueStore()), drafts,
            ModelsApi(connection), MediaApi(client), SlashApi(connection), sessions, ProfilesApi(client),
            SettingsStore(InMemoryKeyValueStore(), scope), PetApi(connection), JourneyApi(client), AudioApi(client),
            NoRecorder, NoPlayer, scope, BotsApi(connection), { null },
            object : VoiceKeepAlive {
                override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = false
                override fun release() = Unit
            },
            NoDeviceDictation,
            { _, _ -> null },
            seen = seenStore,
        )
        viewModels.put("chat", vm)
        return vm to chatHost
    }

    @Test
    fun widgetFilesStayWithTheirTranscriptWhileTheSelectedChatChanges() = runTest(dispatcher) {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val reads = mutableListOf<Pair<String, String?>>()
        val (vm, _) = viewModel(fileReads = reads)
        val first = ChatTarget(gateway, "same-id", "First", profile = "first")
        vm.open(first)
        runCurrent()
        val oldTranscript = vm.transcript.value
        val oldFiles = assertNotNull(oldTranscript.widgetFiles)
        val file = dev.hermeskotlin.core.media.GatewayFile("/tmp/widget.html")

        listOf(first.copy(profile = "second"), first.copy(gateway = SavedGateway("https://other.example"))).forEach { next ->
            val previous = vm.transcript.value
            vm.open(next)
            // Session delivery is delayed: the old emission must still carry its old callbacks.
            assertSame(previous, vm.transcript.value)
            runCurrent()
            val newTranscript = vm.transcript.value
            assertEquals("same-id", newTranscript.state.storedSessionId)
            assertEquals(next.gateway.gatewayUrl to next.profile, assertNotNull(newTranscript.widgetFiles).identity)
            // The screen may still hold this prior emission while the selected route has changed.
            assertTrue(loadWidget(oldFiles, file) is FileLoad.Ready)
            assertTrue(downloadWidget(oldFiles, file) is FileDownload.Ready)
        }
        assertEquals<List<Pair<String, String?>>>(List(4) { "hermes.example.ts.net" to "first" }, reads)
        assertEquals(first.gateway.gatewayUrl to first.profile, oldFiles.identity)
        assertEquals("same-id", oldTranscript.state.storedSessionId)

        // A new title on the same chat reads the same files, so its widgets keep their key and don't reload.
        val current = vm.transcript.value.widgetFiles
        vm.open(first.copy(gateway = SavedGateway("https://other.example"), title = "Renamed"))
        runCurrent()
        assertEquals(assertNotNull(current).identity, assertNotNull(vm.transcript.value.widgetFiles).identity)
    }

    @Test
    fun theSameChatReopenedAfterTheHostClosedGetsAFreshSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // An expired session closes the host's chat; signing back in lands on the same chat.
        host.close()
        assertNull(host.session.value)
        vm.open(target)

        assertNotSame(first, assertNotNull(host.session.value))
    }

    @Test
    fun aBotsChatIsRememberedWhereTheChatsListWasTheLaunchProfileIncluded() {
        // Opened from the launch profile's Chats list: the next launch reads the launch profile's last chat.
        assertNull(ChatTarget(gateway, "b1", "Side", profile = "side", bot = BotIdentity("side", "Side")).rememberedIn)
        assertEquals("work", ChatTarget(gateway, "b1", "Side", profile = "side", bot = BotIdentity("side", "Side", chatsProfile = "work")).rememberedIn)
        assertEquals("work", ChatTarget(gateway, "s1", "Chat", profile = "work").rememberedIn)
    }

    private fun attachment(id: String) = OutgoingAttachment(id, "$id.txt", "text/plain", byteArrayOf(1))

    @Test
    fun sendingDropsAReadThatFinishesInTheNextDraft() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val pending = CompletableDeferred<OutgoingAttachment>()
        vm.composer.setTextAndPlaceCursorAtEnd("Look at this")
        vm.pasteImages(target) { listOf(withContext(NonCancellable) { pending.await() }) to null }
        try {
            vm.send()
            vm.composer.setTextAndPlaceCursorAtEnd("Next draft")
            vm.pasteImages(target) { listOf(attachment("new")) to null }
        } finally {
            pending.complete(attachment("old"))
        }

        runCurrent()
        assertEquals(listOf("new"), vm.attachments.value.map { it.id })
        assertNull(vm.attachmentError.value)
    }

    @Test
    fun aTimedOutImagePasteShowsAnErrorAndIgnoresALateImage() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val result = CompletableDeferred<List<OutgoingAttachment>>()
        vm.pasteImages(target) { withContext(NonCancellable) { result.await() } to null }
        try {
            advanceTimeBy(29_999)
            assertNull(vm.attachmentError.value)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(ComposerNotice.ImagePasteTimedOut, vm.attachmentError.value)
        } finally {
            result.complete(listOf(OutgoingAttachment("image", "copied.jpg", "image/jpeg", byteArrayOf(1))))
        }
        runCurrent()
        assertTrue(vm.attachments.value.isEmpty())
    }

    @Test
    fun aDraftFromOutsideFillsTheNewChatWithoutSending() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val draft = ComposeDraft(text = "look at this", attachments = listOf(attachment("a"), attachment("b")), notice = "Only some fit.")

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = draft))

        assertEquals("look at this", vm.composer.text.toString())
        assertEquals(listOf("a", "b"), vm.attachments.value.map { it.id })
        assertEquals(ComposerNotice.Said("Only some fit."), vm.attachmentError.value)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun aSharedDraftWinsOverTheStoredNewChatDraft() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, null, "half-typed earlier")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(text = "shared")))

        assertEquals("shared", vm.composer.text.toString())
    }

    @Test
    fun aSharedPictureAloneDoesNotBringBackAnOldNewChatDraft() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, null, "text of an earlier share")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals("", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aShareDoesNotPickUpFilesLeftInAnEarlierNewChat() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, null, null, nonce = 1))
        vm.addAttachments(listOf(attachment("old")))
        // Another chat takes the composer; the new chat's file waits in its tray.
        vm.open(ChatTarget(gateway, "s1", "Chat"))

        vm.open(ChatTarget(gateway, null, null, nonce = 2, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun pickingAProjectKeepsTheNewChatAndStartsItThere() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        val fresh = ChatTarget(gateway, null, null, nonce = 1)
        vm.open(fresh)
        val chat = assertNotNull(host.session.value)
        vm.composer.setTextAndPlaceCursorAtEnd("half typed")
        vm.addAttachments(listOf(attachment("a")))

        // The greeting's project menu moves the chat before anything was sent in it.
        vm.open(fresh.copy(cwd = "/srv/site"))

        assertSame(chat, host.session.value)
        assertEquals("half typed", vm.composer.text.toString())
        assertEquals(listOf("a"), vm.attachments.value.map { it.id })
        vm.send(null)
        val create = sent.map { Json.parseToJsonElement(it).jsonObject }.first { it["method"]?.toString() == "\"session.create\"" }
        assertEquals("\"/srv/site\"", create["params"]?.jsonObject?.get("cwd")?.toString())
    }

    @Test
    fun anEmptyListItemLeftAtTheEndIsntSent() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, null, null, nonce = 1))
        // What Enter after the last item leaves behind.
        vm.composer.setTextAndPlaceCursorAtEnd("1. eggs\n2. bread\n3. ")
        vm.send(null)
        // The fake gateway can't start the chat, so what was sent comes back to the composer as it went out.
        assertEquals("1. eggs\n2. bread", vm.composer.text.toString())
    }

    private val scribe = BotIdentity("scribe", "Scribe")

    @Test
    fun aShareToABotGoesAfterWhatWasTypedInItsChat() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, "b1", "my own note", "scribe")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(text = "shared link", attachments = listOf(attachment("p")))))

        assertEquals("my own note\nshared link", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
        assertTrue(vm.state.value.messages.none { it is ChatMessage.User })
    }

    @Test
    fun aShareIntoTheOpenBotChatKeepsItsTextAndFiles() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe))
        vm.composer.setTextAndPlaceCursorAtEnd("half typed")
        vm.addAttachments(listOf(attachment("old")))

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(text = "shared", attachments = listOf(attachment("p")))))

        assertEquals("half typed\nshared", vm.composer.text.toString())
        assertEquals(listOf("old", "p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aSharedPictureAloneKeepsTheBotChatsText() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, "b1", "my own note", "scribe")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals("my own note", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aShareThatOverfillsTheBotChatsTraySaysSo() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe))
        vm.addAttachments((1..3).map { attachment("old$it") })
        val shared = (1..OutgoingAttachment.MAX_COUNT).map { attachment("f$it") }

        // The share sheet sent more files than fit, and the tray already holds some.
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(attachments = shared, notice = "Only the first 10 files were attached.")))

        assertEquals(OutgoingAttachment.MAX_COUNT, vm.attachments.value.size)
        // Not "the first 10 were attached": only 7 of them were.
        assertEquals(ComposerNotice.TooManyAttachments(OutgoingAttachment.MAX_COUNT), vm.attachmentError.value)
    }

    @Test
    fun aFileOverTheSizeLimitStaysOutOfTheTray() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        // A drop or a share comes without the picker's check; past what the gateway takes it couldn't be sent.
        val huge = OutgoingAttachment(
            "d1", "dump.bin", "application/octet-stream", ByteArray(0),
            source = dev.hermeskotlin.core.chat.AttachmentSource(OutgoingAttachment.MAX_BYTES + 1L) { error("not read") },
        )

        vm.addAttachments(listOf(attachment("ok"), huge))

        assertEquals(listOf("ok"), vm.attachments.value.map { it.id })
        assertEquals(ComposerNotice.FileTooLarge("dump.bin", 100), vm.attachmentError.value)
    }

    @Test
    fun sharedFilesStopAtTheTrayLimit() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val many = (1..OutgoingAttachment.MAX_COUNT + 2).map { attachment("f$it") }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(attachments = many)))

        assertEquals(OutgoingAttachment.MAX_COUNT, vm.attachments.value.size)
    }

    @Test
    fun theVoiceShortcutAsksTheScreenToDictate() = runTest(dispatcher) {
        val (vm, _) = viewModel()

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.Dictation)))

        assertEquals(ChatRequest.StartDictation, vm.requests.first())
    }

    @Test
    fun aChatOpenedToTypeInAsksTheScreenForTheKeyboard() = runTest(dispatcher) {
        val (vm, _) = viewModel()

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.Typing)))

        assertEquals(ChatRequest.FocusComposer, vm.requests.first())
    }

    @Test
    fun onlyAChatOpenedToTypeInAsksForTheKeyboard() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        val starts = listOf(null) + DraftStart.entries.filter { it != DraftStart.Typing }
        starts.forEachIndexed { i, start ->
            vm.open(ChatTarget(gateway, null, null, nonce = i + 1L, draft = ComposeDraft(text = "hi", start = start)))
        }
        vm.open(ChatTarget(gateway, null, null, nonce = 100))

        assertTrue(ChatRequest.FocusComposer !in requests, "asked for the keyboard: $requests")
    }

    @Test
    fun theCallShortcutStartsVoiceOnceInItsFreshChat() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Previous chat"))
        val previous = host.session.value
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        val call = ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat))
        vm.open(call)
        assertEquals(listOf<ChatRequest>(ChatRequest.StartCall), requests)
        assertNotSame(previous, host.session.value)
        assertNull(vm.state.value.storedSessionId)

        // The same shortcut isn't replayed, and a plain new chat starts nothing.
        vm.open(call)
        vm.open(ChatTarget(gateway, null, null, nonce = 2))
        assertEquals(listOf<ChatRequest>(ChatRequest.StartCall), requests)
    }

    @Test
    fun theCallShortcutWaitsForTheGateway() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        assertEquals(emptyList(), requests)

        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(listOf<ChatRequest>(ChatRequest.StartCall), requests)
    }

    @Test
    fun theCallShortcutGivesUpWithANoticeWhenTheGatewayNeverAnswers() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        advanceTimeBy(30_001)

        assertEquals(emptyList(), requests)
        assertEquals(ComposerNotice.CallNotStarted, vm.attachmentError.value)
        // Connecting afterwards doesn't start a call the person has given up on.
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun theCallShortcutWaitsForThePersonToComeBackToTheApp() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.onScreen(false)
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(emptyList(), requests)

        vm.onScreen(true)
        assertEquals(listOf<ChatRequest>(ChatRequest.StartCall), requests)
    }

    @Test
    fun aStoredCallStillStartsAfterItsUnreadPositionChanges() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }
        vm.onScreen(false)
        val call = ChatTarget(gateway, "s1", "Call target", nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat))
        vm.open(call)
        val updated = call.copy(readUpTo = 1_000.0)
        vm.open(updated)
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(emptyList(), requests)

        vm.onScreen(true)
        vm.open(updated)
        assertEquals(listOf<ChatRequest>(ChatRequest.StartCall), requests)
    }

    @Test
    fun theCallShortcutGivesUpQuietlyWhenThePersonStaysAway() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.onScreen(false)
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        advanceTimeBy(30_001)
        vm.onScreen(true)

        assertEquals(emptyList(), requests)
        // The gateway was there; the notice would blame it wrongly.
        assertNull(vm.attachmentError.value)
    }

    @Test
    fun theCallShortcutDoesNotFollowThePersonToAnotherChat() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.open(ChatTarget(gateway, "s1", "Another chat"))
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }

        assertEquals(emptyList(), requests)
    }

    @Test
    fun reopeningTheOpenChatKeepsItsSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = host.session.value

        vm.open(target)

        assertSame(first, host.session.value)
    }

    @Test
    fun theSameChatWithANewReadPositionKeepsItsSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // Reopened from a notification: only where the "New messages" line goes has changed.
        vm.open(target.copy(readUpTo = 1_300.0))

        assertSame(first, host.session.value)
    }

    @Test
    fun whereTheReaderWasIsntKeptForTheSameChatReopenedAtANewReadPosition() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val key = "s1-list"
        vm.saveViewport(TranscriptViewport(key, "m13", 0, pinned = false, readUpTo = null, savedCopy = false,
            readerNavigated = true, unreadSettled = true, positioned = true, unreadFrom = null, unreadPagesLoaded = 0))
        assertNotNull(vm.viewportFor(key, target))

        // Reopened from a notification: the screen asks before open() has run, and the list opens afresh.
        val reopened = target.copy(readUpTo = 1_300.0)
        assertNull(vm.viewportFor(key, reopened))
        vm.open(reopened)
        assertNull(vm.viewportFor(key, reopened))
    }

    @Test
    fun theSameChatFromANotificationAfterTheHostClosedGetsAFreshSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // The session expired while away; the reply's notification reopens the chat with a read position.
        host.close()
        vm.open(target.copy(readUpTo = 1_300.0))

        assertNotSame(first, assertNotNull(host.session.value))
    }
    @Test
    fun sharedReadPositionsAreUsedForRegularAndBotChats() = runTest(dispatcher) {
        for (bot in listOf(false, true)) {
            val reads = mutableListOf<String?>()
            val (vm, _) = viewModel(readState = { request ->
                if (request.url.encodedPath == "/api/sessions/s1") {
                    reads += request.url.parameters["profile"]
                    respond("""{"id":"s1","last_read_at":1200.0}""", HttpStatusCode.OK, json)
                } else null
            })
            connection.start(gateway.gatewayUrl)
            connection.state.first { it is ConnectionState.Connected }
            val target = ChatTarget(gateway, "s1", "Chat", profile = "scribe".takeIf { bot },
                bot = BotIdentity("scribe", "Scribe").takeIf { bot }, readUpTo = 1000.0)
            vm.open(target)
            runCurrent()

            assertEquals(1200.0, assertNotNull(vm.readPosition.value).readUpTo)
            assertEquals(listOf("scribe".takeIf { bot }), reads)
        }
    }

    @Test
    fun untrackedAndUnavailableReadStateKeepTheLocalFallback() = runTest(dispatcher) {
        for (body in listOf("{}", """{"last_read_at":null}""", """{"last_read_at":0}""", """{"last_read_at":-1}""")) {
            val (vm, _) = viewModel(readState = { request ->
                if (request.url.encodedPath == "/api/sessions/s1") respond(body, HttpStatusCode.OK, json) else null
            })
            connection.start(gateway.gatewayUrl)
            connection.state.first { it is ConnectionState.Connected }
            vm.open(ChatTarget(gateway, "s1", "Chat", readUpTo = 1000.0))
            runCurrent()
            assertEquals(1000.0, assertNotNull(vm.readPosition.value).readUpTo, body)
        }
        val (vm, _) = viewModel(readState = { request ->
            if (request.url.encodedPath == "/api/sessions/s1") respond("{}", HttpStatusCode.ServiceUnavailable, json) else null
        })
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Chat", readUpTo = 1000.0))
        runCurrent()
        assertEquals(1000.0, assertNotNull(vm.readPosition.value).readUpTo)
    }

    @Test
    fun aLateReadPositionDoesNotCrossGatewayOrProfile() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val (vm, _) = viewModel(readState = { request ->
            if (request.url.encodedPath == "/api/sessions/s1") {
                if (request.url.host == gateway.gatewayUrl.host) pending.await()
                respond("""{"last_read_at":1200.0}""", HttpStatusCode.OK, json)
            } else null
        })
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "First", profile = "first"))
        runCurrent()
        val next = ChatTarget(SavedGateway("https://other.example.test"), "s1", "Second", profile = "second", readUpTo = 900.0)
        vm.open(next)
        runCurrent()
        pending.complete(Unit)
        runCurrent()

        assertEquals(next, assertNotNull(vm.readPosition.value).target)
        assertEquals(1200.0, vm.readPosition.value?.readUpTo)
    }

    @Test
    fun aReadStateTimeoutKeepsTheLocalBoundaryAndDoesNotJumpLater() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val (vm, _) = viewModel(readState = { request ->
            if (request.url.encodedPath == "/api/sessions/s1") {
                pending.await()
                respond("""{"last_read_at":1500}""", HttpStatusCode.OK, json)
            } else null
        })
        val target = ChatTarget(gateway, "s1", "Chat", readUpTo = 1000.0)
        vm.open(target)
        runCurrent()
        assertNull(vm.readPosition.value)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1000.0, assertNotNull(vm.readPosition.value).readUpTo)
        pending.complete(Unit)
        runCurrent()
        vm.open(target)
        assertEquals(1000.0, vm.readPosition.value?.readUpTo)
    }

    @Test
    fun sharedReadStateDoesNotDependOnTheSocketAlreadyBeingConnected() = runTest(dispatcher) {
        var reads = 0
        val (vm, _) = viewModel(readState = { request ->
            if (request.url.encodedPath == "/api/sessions/s1") {
                reads++
                respond("""{"last_read_at":1500}""", HttpStatusCode.OK, json)
            } else null
        })
        val target = ChatTarget(gateway, "s1", "Chat", readUpTo = 1000.0)
        vm.open(target)
        runCurrent()
        assertEquals(1500.0, vm.readPosition.value?.readUpTo)
        vm.open(target)
        runCurrent()
        assertEquals(1, reads)
    }

    @Test
    fun onlyReachingTheLatestMessagesMarksRegularAndBotChatsRead() = runTest(dispatcher) {
        for (bot in listOf(false, true)) {
            val writes = mutableListOf<Pair<String, String?>>()
            val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
            val (vm, host) = viewModel(seenStore = seen, readState = { request ->
                when {
                    request.url.encodedPath.endsWith("/messages") -> respond(
                        """{"session_id":"s1","messages":[{"id":1,"role":"user","content":"Hello","timestamp":1100},{"id":2,"role":"assistant","content":"Reply","timestamp":1300}]}""",
                        HttpStatusCode.OK, json,
                    )
                    request.url.encodedPath.endsWith("/mutation-snapshot") -> respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
                    request.method == HttpMethod.Patch -> {
                        writes += request.url.encodedPath to (request.body as io.ktor.http.content.TextContent).text
                        // A failed shared write keeps the actual local look and is not blindly retried.
                        respond("{}", if (bot) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, json)
                    }
                    request.url.encodedPath == "/api/sessions/s1" -> respond("""{"last_read_at":1200}""", HttpStatusCode.OK, json)
                    else -> null
                }
            })
            connection.start(gateway.gatewayUrl)
            connection.state.first { it is ConnectionState.Connected }
            val profile = "scribe".takeIf { bot }
            val target = ChatTarget(gateway, "s1", "Chat", profile = profile, bot = BotIdentity("scribe", "Scribe").takeIf { bot })
            vm.open(target)
            runCurrent()
            vm.markLooked()
            runCurrent()
            assertTrue(writes.isEmpty())
            assertEquals(1000.0, seen.lastLook(gateway.gatewayUrl, "s1", profile))

            val loaded = assertNotNull(host.session.value).state.value
            assertTrue(loaded.historyLoaded, loaded.toString())
            assertTrue(loaded.historyRead, loaded.toString())
            assertTrue(!loaded.running, loaded.toString())
            assertEquals(2, loaded.messages.size, loaded.toString())
            assertEquals(target, vm.readPosition.value?.target)
            vm.readLatest(target, loaded.copy(messages = loaded.messages.dropLast(1)))
            runCurrent()
            assertTrue(writes.isEmpty())
            assertEquals(1000.0, seen.lastLook(gateway.gatewayUrl, "s1", profile))
            vm.onScreen(false)
            vm.readLatest(target, loaded)
            runCurrent()
            assertTrue(writes.isEmpty())
            vm.onScreen(true)
            vm.readLatest(target, loaded.copy(storedSessionId = "other"))
            runCurrent()
            assertTrue(writes.isEmpty())
            vm.readLatest(target, loaded)
            vm.readLatest(target, loaded)
            runCurrent()
            assertEquals(1, writes.size)
            assertEquals("/api/sessions/s1", writes.single().first)
            val body = Json.parseToJsonElement(writes.single().second!!).jsonObject
            assertEquals("false", body["unread"].toString())
            assertEquals(profile, body["profile"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            assertEquals(1300.0, seen.lastLook(gateway.gatewayUrl, "s1", profile))
            vm.readLatest(target.copy(profile = "other"), loaded)
            runCurrent()
            assertEquals(1, writes.size)
        }
    }

    /** A chat with a prompt at 1100 and a reply at 1300; the detail route answers [detail], PATCHes go to [writes]. */
    private fun readableChat(seen: SeenStore, detail: String, writes: MutableList<String>, pending: () -> CompletableDeferred<Unit>? = { null }) =
        viewModel(seenStore = seen, readState = { request ->
            when {
                request.url.encodedPath.endsWith("/messages") -> respond(
                    """{"session_id":"s1","messages":[{"id":1,"role":"user","content":"Hello","timestamp":1100},{"id":2,"role":"assistant","content":"Reply","timestamp":1300}]}""",
                    HttpStatusCode.OK, json,
                )
                request.url.encodedPath.endsWith("/mutation-snapshot") -> respond("""{"detail":"No such API endpoint"}""", HttpStatusCode.NotFound, json)
                request.method == HttpMethod.Patch -> {
                    writes += request.url.encodedPath
                    respond("{}", HttpStatusCode.OK, json)
                }
                request.url.encodedPath == "/api/sessions/s1" -> {
                    pending()?.await()
                    respond(detail, HttpStatusCode.OK, json)
                }
                else -> null
            }
        })

    @Test
    fun aChatTheListFoundReadOpensAtItsEnd() = runTest(dispatcher) {
        // Never opened here, from a messaging platform, on a gateway without read tracking: nothing marks it new.
        val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
        val (vm, _) = readableChat(seen, "{}", mutableListOf())
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Chat"))
        runCurrent()

        assertNull(assertNotNull(vm.readPosition.value).readUpTo)
    }

    @Test
    fun aGatewayWithoutReadTrackingIsNotWrittenTo() = runTest(dispatcher) {
        val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
        val writes = mutableListOf<String>()
        val (vm, host) = readableChat(seen, """{"id":"s1"}""", writes)
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        runCurrent()
        vm.readLatest(target, assertNotNull(host.session.value).state.value)
        runCurrent()

        assertTrue(writes.isEmpty(), writes.toString())
        // Read here all the same.
        assertEquals(1300.0, seen.lastLook(gateway.gatewayUrl, "s1"))
    }

    @Test
    fun aChatReadToItsEndIsCaughtUpUntilTheUserLeaves() = runTest(dispatcher) {
        val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
        val (vm, host) = readableChat(seen, """{"last_read_at":1200}""", mutableListOf())
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        val target = ChatTarget(gateway, "s1", "Chat", profile = "scribe")
        vm.open(target)
        runCurrent()
        assertNull(seen.caughtUp.value)
        vm.readLatest(target, assertNotNull(host.session.value).state.value)
        runCurrent()
        assertEquals(CaughtUp(gateway.gatewayUrl, "scribe", "s1"), seen.caughtUp.value)

        // Something in the chat's place (a room) is enough, without the app leaving the screen.
        vm.onScreen(false)
        assertNull(seen.caughtUp.value)
        vm.markLooked()
        runCurrent()
        assertNull(seen.caughtUp.value)
        // Shown again on the same end: caught up again without another write.
        vm.onScreen(true)
        vm.readLatest(target, assertNotNull(host.session.value).state.value)
        assertEquals(CaughtUp(gateway.gatewayUrl, "scribe", "s1"), seen.caughtUp.value)
        // Another chat opened lets go of it.
        vm.open(ChatTarget(gateway, "s2", "Other", profile = "scribe"))
        runCurrent()
        assertNull(seen.caughtUp.value)
    }

    @Test
    fun theSameChatReopenedAfterSigningInWaitsForItsReadStateBeforeReading() = runTest(dispatcher) {
        val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
        val writes = mutableListOf<String>()
        var pending: CompletableDeferred<Unit>? = null
        val (vm, host) = readableChat(seen, """{"last_read_at":1200}""", writes) { pending }
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        runCurrent()
        val first = assertNotNull(host.session.value).state.value
        vm.readLatest(target, first)
        runCurrent()
        assertEquals(1, writes.size)

        // Signed in again on the same chat: until the gateway answers again, reaching the end records nothing.
        host.close()
        pending = CompletableDeferred()
        vm.open(target)
        runCurrent()
        vm.readLatest(target, assertNotNull(host.session.value).state.value)
        runCurrent()
        assertEquals(1, writes.size)
        assertNull(seen.caughtUp.value)
        pending.complete(Unit)
    }

    @Test
    fun theSameChatReopenedKeepsItsTranscriptWhileTheNewPositionIsRead() = runTest(dispatcher) {
        val seen = SeenStore(InMemoryKeyValueStore()) { 1000.0 }
        var pending: CompletableDeferred<Unit>? = null
        val (vm, _) = viewModel(seenStore = seen, readState = { request ->
            if (request.url.encodedPath == "/api/sessions/s1") {
                pending?.await()
                respond("""{"last_read_at":1200}""", HttpStatusCode.OK, json)
            } else null
        })
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        val target = ChatTarget(gateway, "s1", "Chat", readUpTo = 1000.0)
        vm.open(target)
        runCurrent()
        assertEquals(1200.0, vm.readPosition.value?.readUpTo)

        // A notification for it: the old position stays (the screen keeps its list) until the new one comes.
        pending = CompletableDeferred()
        val again = target.copy(readUpTo = 1100.0)
        vm.open(again)
        runCurrent()
        assertEquals(target, vm.readPosition.value?.target)
        pending.complete(Unit)
        runCurrent()
        assertEquals(again, vm.readPosition.value?.target)
    }

    // --- Editing a prompt queued behind the running turn ---

    /**
     * Chat `s1` open on the session authority with a turn running and "second" queued behind it as `adm-2`, which the
     * gateway offers to replace; `prompt.replace_queued` admits the edit as `adm-3`.
     */
    private suspend fun queuedChat(
        replace: String = """{"admission_id":"adm-3","status":"queued","sequence":3,"outcome":null,"authority_epoch":1}""",
    ): ChatViewModel {
        val (vm, _) = viewModel(authority = true, answer = { method ->
            when (method) {
                "session.resume" -> """{"session_id":"s1","running":true,"revision":1,"execution_generation":2}"""
                "prompt.submit" -> """{"status":"queued","admission_id":"adm-2","execution_generation":2}"""
                "prompt.replace_queued" -> replace
                else -> null
            }
        })
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Chat"))
        vm.state.first { it.runtimeSessionId == "s1" && it.running }
        vm.composer.setTextAndPlaceCursorAtEnd("second")
        vm.send(RunningSend.Queue)
        vm.state.first { s -> s.messages.any { it is ChatMessage.User && it.queued } }
        socket!!.push(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"s1","payload":{"pending":[""" +
                """{"admission_id":"adm-2","status":"queued","text":"second","input_id":"x","controls":["steer","replace"]}]}}}""",
        )
        vm.state.first { "adm-2" in it.replaceableAdmissions }
        return vm
    }

    private fun ChatViewModel.queuedKey() = state.value.messages.filterIsInstance<ChatMessage.User>().last().key

    private fun sentMethods() = sent.mapNotNull { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content }

    @Test
    fun aQueuedPromptsEditChangesItWhereItWaits() = runTest(dispatcher) {
        val vm = queuedChat()
        vm.composer.setTextAndPlaceCursorAtEnd("half-typed")
        val key = vm.queuedKey()

        vm.startQueuedEdit(key)
        assertEquals(key, vm.editing.value)
        assertTrue(vm.editingQueued.value)
        assertEquals("second", vm.composer.text.toString())

        vm.composer.setTextAndPlaceCursorAtEnd("second, edited")
        vm.send(null)
        vm.state.first { s -> s.messages.any { it is ChatMessage.User && it.text == "second, edited" } }

        assertTrue("prompt.replace_queued" in sentMethods())
        // Nothing else went out for it: no new prompt, no steer.
        assertEquals(1, sentMethods().count { it == "prompt.submit" })
        val prompt = vm.state.value.messages.filterIsInstance<ChatMessage.User>().last()
        assertTrue(prompt.queued)
        assertEquals("adm-3", prompt.admissionId)
        assertNull(vm.editing.value)
        assertEquals("half-typed", vm.composer.text.toString())
    }

    @Test
    fun cancellingAQueuedPromptsEditLeavesItAsItWas() = runTest(dispatcher) {
        val vm = queuedChat()
        val key = vm.queuedKey()

        vm.startQueuedEdit(key)
        vm.composer.setTextAndPlaceCursorAtEnd("never mind")
        vm.cancelEdit()

        assertNull(vm.editing.value)
        assertTrue(!vm.editingQueued.value)
        assertEquals("", vm.composer.text.toString())
        assertTrue("prompt.replace_queued" !in sentMethods())
        assertEquals("second", (vm.state.value.messages.first { it.key == key } as ChatMessage.User).text)
    }

    @Test
    fun aQueuedPromptWhoseTurnStartsMidEditKeepsTheEditInTheComposer() = runTest(dispatcher) {
        val vm = queuedChat()
        val key = vm.queuedKey()
        vm.startQueuedEdit(key)
        vm.composer.setTextAndPlaceCursorAtEnd("second, edited")

        // The running turn ends and the gateway starts the queued one, as it was.
        socket!!.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"s1","payload":{"status":"complete"}}}""")
        socket!!.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s1","admission_id":"adm-2","payload":{}}}""")
        vm.state.first { s -> s.messages.none { it is ChatMessage.User && it.queued } }
        runCurrent()

        assertNull(vm.editing.value)
        assertEquals("second, edited", vm.composer.text.toString())
        assertEquals(ComposerNotice.QueuedEditOvertaken, vm.attachmentError.value)
        assertTrue("prompt.replace_queued" !in sentMethods())
        assertEquals("second", (vm.state.value.messages.first { it.key == key } as ChatMessage.User).text)
    }

    @Test
    fun aQueuedPromptTheGatewayDoesNotOfferIsNotEdited() = runTest(dispatcher) {
        val (vm, _) = viewModel(answer = { method ->
            when (method) {
                "session.resume" -> """{"session_id":"s1","running":true}"""
                "prompt.submit" -> """{"status":"queued"}"""
                else -> null
            }
        })
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Chat"))
        vm.state.first { it.runtimeSessionId == "s1" && it.running }
        vm.composer.setTextAndPlaceCursorAtEnd("second")
        vm.send(RunningSend.Queue)
        vm.state.first { s -> s.messages.any { it is ChatMessage.User && it.queued } }

        vm.startQueuedEdit(vm.queuedKey())

        assertNull(vm.editing.value)
        assertEquals("", vm.composer.text.toString())
    }

    @Test
    fun aQueuedEditsLateRefusalAfterAChatSwitchLeavesTheNewChatAlone() = runTest(dispatcher) {
        val vm = queuedChat(replace = ReadyGateway.HOLD)
        val key = vm.queuedKey()
        vm.startQueuedEdit(key)
        vm.composer.setTextAndPlaceCursorAtEnd("second, edited")
        vm.send(null)
        val call = sent.map { Json.parseToJsonElement(it).jsonObject }.last { it["method"]?.jsonPrimitive?.content == "prompt.replace_queued" }

        vm.open(ChatTarget(gateway, "s2", "Other"))
        runCurrent()
        // Its turn took it first; the answer comes once another chat is open.
        socket!!.push("""{"jsonrpc":"2.0","id":${call["id"]},"error":{"code":4001,"message":"admission_started","data":{"reason":"admission_started"}}}""")
        runCurrent()

        assertNull(vm.editing.value)
        assertTrue("second, edited" !in vm.composer.text.toString())
    }

    @Test
    fun aStopWhileEditingAQueuedPromptDoesNotSayItsTurnStarted() = runTest(dispatcher) {
        val vm = queuedChat()
        val key = vm.queuedKey()
        vm.startQueuedEdit(key)
        vm.composer.setTextAndPlaceCursorAtEnd("second, edited")

        vm.interrupt()
        vm.state.first { s -> s.messages.none { it.key == key } }
        runCurrent()

        assertNull(vm.editing.value)
        assertTrue(vm.attachmentError.value != ComposerNotice.QueuedEditOvertaken)
        // Nothing typed is lost: the edit stays, and the stop gives back the prompt it dropped.
        assertTrue("second, edited" in vm.composer.text.toString())
    }
}
