package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotDraft
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.RuntimeCheck
import dev.hermeskotlin.core.bots.SidebarModeStore
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.ui.inbox.Words
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A bot just made here, whose Bot Chat another client (Desktop, a second phone) opens in the moment between our
 * `session.create` and our rename: the bot's chat exists, and is the one to open, without a second intro and
 * without "Couldn't make the bot". On the session authority (the rename is a bare -32603 and the title lookup
 * finds the other chat) and on an older gateway (`session.title` answers 4022 "already in use").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTakenTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher(dispatcher.scheduler))
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * A gateway on the session [authority] or not, with one profile `work` made by `profiles.create`. When our
     * `session.create` for its Bot Chat comes in, another client has just made the Bot Chat `theirs`: from then
     * on the roster has it (when [listed]), the title lookup finds it, and our rename is refused as each kind of
     * gateway does.
     */
    private class RaceGateway(
        private val scope: CoroutineScope,
        private val authority: Boolean,
        /** Whether the roster lists the other client's chat yet. */
        private val listed: Boolean = true,
        /** Whether our `session.create` fails outright instead (and no other client takes the chat). */
        private val createFails: Boolean = false,
        var fixedRoster: String? = null,
    ) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            val payload = if (authority) """{"session_authority":true}""" else "{}"
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":$payload}}""")
        }

        val requests = mutableListOf<Pair<String, JsonObject>>()
        private var made = false
        private var theirs = false

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val method = message["method"]?.jsonPrimitive?.content ?: return
            val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
            requests += method to params
            val reply = answer(method, params)
            scope.launch {
                inbound.send(
                    if (reply is Refused) """{"jsonrpc":"2.0","id":$id,"error":{"code":${reply.code},"message":"${reply.message}"}}"""
                    else """{"jsonrpc":"2.0","id":$id,"result":$reply}""",
                )
            }
        }

        private data class Refused(val code: Int, val message: String)

        private fun answer(method: String, params: JsonObject): Any = when (method) {
            "profiles.create" -> { made = true; "{}" }
            "profiles.list" -> fixedRoster ?: roster()
            "profiles.configure" -> """{"applied":{"ui_meta":true}}"""
            "setup.runtime_check" -> """{"ok":true}"""
            "session.create" -> if (createFails) Refused(5000, "Session store is busy") else {
                // The other client's titled create lands first.
                theirs = true
                """{"session_id":"rt-new","stored_session_id":"stored-new","revision":3,"execution_generation":1,"subscription_id":"sub-1"}"""
            }
            "session.mutate" ->
                if (params["operation"]?.jsonPrimitive?.contentOrNull == "rename") Refused(-32603, "internal error") else "{}"
            // hermes-agent v0.22.0 tui_gateway/methods_session.py `session.title`: the store's ValueError is 4022.
            "session.title" -> Refused(4022, "Title 'Bot Chat' is already in use by session theirs")
            "session.list" ->
                if (params["title"] != null && theirs) """{"sessions":[{"session_id":"theirs","title":"Bot Chat"}],"scope":"stored"}"""
                else """{"sessions":[]}"""
            else -> "{}"
        }

        private fun roster(): String {
            val chat = if (theirs && listed) ""","canonical_session":{"id":"theirs","message_count":2}""" else ""
            val work = if (made) """,{"name":"work","ui_meta":{"hermes-bots":{"title":"Work"}}$chat}""" else ""
            return """{"profiles":[{"name":"default","is_default":true}$work],"bot_mode_protocol":true}"""
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }

        fun sent(method: String): List<JsonObject> = requests.filter { it.first == method }.map { it.second }
    }

    private suspend fun viewModel(
        socket: RaceGateway,
        history: suspend (String?, Int) -> Pair<HttpStatusCode, String> = { _, _ -> error("Unexpected history request") },
    ): BotsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            val (status, body) = when {
                request.url.encodedPath.endsWith("/messages") -> history(
                    request.url.parameters["profile"], request.url.parameters["offset"]?.toInt() ?: 0,
                )
                request.url.encodedPath.endsWith("api/cron/jobs") -> HttpStatusCode.OK to """{"jobs":[]}"""
                else -> HttpStatusCode.OK to """{"ticket":"T","ttl_seconds":30}"""
            }
            respond(body, status, json)
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val scope = CoroutineScope(dispatcher)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, scope)
        connection.start(gateway.gatewayUrl)
        (connection.state.first { it is ConnectionState.Connected } as ConnectionState.Connected).client.ready.await()
        val api = BotsApi(connection)
        val host = ChatHost(connection, SessionsApi(client), scope)
        return BotsViewModel(
            api = api,
            chats = BotChats(api),
            connection = connection,
            seenStore = SeenStore(InMemoryKeyValueStore()) { 1000.0 },
            modes = SidebarModeStore(InMemoryKeyValueStore()),
            sessions = SessionsApi(client),
            profiles = ProfilesApi(client),
            health = BotHealth(connection, host, scope) { RuntimeCheck(ok = true) },
            cron = CronApi(client),
            models = ModelsApi(connection),
            host = host,
            attention = AttentionTracker(connection, host, ActiveSessions(connection, scope), scope),
        )
    }

    private fun aNewBotsChatTakenElsewhereOpensIt(authority: Boolean) = runTest(dispatcher) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority)
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        runCurrent()

        var opened: Pair<Bot, String>? = null
        var done = 0
        vm.create(BotDraft(profile = "work", title = "Work"), onDone = { done++ }, onOpened = { bot, id -> opened = bot to id })
        runCurrent()
        vm.busy.first { it == null }
        runCurrent()

        // The bot was made on the gateway either way.
        assertEquals("work", socket.sent("profiles.create").single()["name"]?.jsonPrimitive?.contentOrNull)
        // The other client's Bot Chat opens, the bot's one chat, and it isn't greeted a second time.
        assertTrue(socket.sent("prompt.submit").isEmpty(), "intro sent: ${socket.sent("prompt.submit")}")
        assertNull(vm.state.value.notice, "notice shown: ${vm.state.value.notice}")
        assertEquals("work", opened?.first?.name, "opened: $opened")
        assertEquals("theirs", opened?.second, "opened: $opened")
        // No second chat was started for it.
        assertEquals(1, socket.sent("session.create").size)
        assertEquals(1, done)
    }

    /**
     * The roster doesn't list the other client's chat yet: the chat can't be found to open, which says so (as a tap
     * on the bot would), still without "Couldn't make the bot", an intro or a chat of ours left behind.
     */
    @Test
    fun aNewBotsChatTakenElsewhereThatCantBeFoundYetSaysSo() = runTest(dispatcher) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, listed = false)
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        runCurrent()

        var opened: Pair<Bot, String>? = null
        var done = 0
        vm.create(BotDraft(profile = "work", title = "Work"), onDone = { done++ }, onOpened = { bot, id -> opened = bot to id })
        runCurrent()
        vm.busy.first { it == null }
        runCurrent()

        assertTrue(socket.sent("prompt.submit").isEmpty(), "intro sent: ${socket.sent("prompt.submit")}")
        assertEquals(Words.Raw("Couldn't open Work's chat. Try again in a moment."), vm.state.value.notice)
        assertNull(opened)
        // Each chat we started was removed again when its title was taken.
        assertEquals(socket.sent("session.create").size, socket.sent("session.delete").size)
        assertEquals(1, done)
    }

    /** The bot is made but its chat can't be started for another reason: the editor, already closed, isn't closed twice. */
    @Test
    fun aNewBotWhoseChatFailsToStartClosesTheEditorOnce() = runTest(dispatcher) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, createFails = true)
        val vm = viewModel(socket)
        vm.bind(gateway.gatewayUrl)
        runCurrent()

        var opened: Pair<Bot, String>? = null
        var done = 0
        vm.create(BotDraft(profile = "work", title = "Work"), onDone = { done++ }, onOpened = { bot, id -> opened = bot to id })
        runCurrent()
        vm.busy.first { it == null }
        runCurrent()

        assertTrue(socket.sent("prompt.submit").isEmpty(), "intro sent: ${socket.sent("prompt.submit")}")
        assertNull(opened)
        assertTrue(vm.state.value.notice != null, "no notice")
        assertEquals(1, done)
    }

    @Test
    fun rosterLoadsAndWatchesBeforeUnreadHistoryReturns() = runTest(dispatcher, timeout = 10.seconds) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, fixedRoster = historyRoster())
        val reply = CompletableDeferred<String>()
        val historyStarted = CompletableDeferred<Unit>()
        var reads = 0
        val vm = viewModel(socket) { _, _ ->
            reads++
            historyStarted.complete(Unit)
            HttpStatusCode.OK to reply.await()
        }

        try {
            vm.bind(gateway.gatewayUrl)
            runCurrent()

            vm.refresh()
            historyStarted.await()
            assertFalse(vm.state.value.loading, "The roster must load while history is suspended")
            assertEquals(listOf("work"), vm.state.value.all.map { it.name })
            assertTrue(socket.sent("session.list").isNotEmpty())
            assertTrue(vm.state.value.unread.isEmpty())

            reply.complete("""{"session_id":"chat","messages":[{"role":"assistant","content":"Done.","timestamp":1100}]}""")
            vm.state.first { "work" in it.unread }

            vm.refresh()
            runCurrent()
            assertEquals(1, reads, "An unchanged unread chat does not need its history again")
        } finally {
            reply.complete("""{"session_id":"chat","messages":[]}""")
        }
    }

    private fun historyRoster(activity: Int = 1100, nextActivity: Int? = null): String {
        val next = nextActivity?.let {
            """,{"name":"after","canonical_session":{"id":"chat-after","last_active":$it,"message_count":1}}"""
        }.orEmpty()
        return """{"profiles":[{"name":"work","canonical_session":{"id":"chat","last_active":$activity,"message_count":800}}$next],"bot_mode_protocol":true}"""
    }

    private fun historyPage(vararg rows: String) =
        HttpStatusCode.OK to """{"session_id":"chat","messages":[${rows.joinToString(",")}]}"""

    private fun toolRows(offset: Int = 0, timestamp: Int? = 1100, withIds: Boolean = true) =
        Array(100) { index ->
            val id = if (withIds) """"id":${offset + index + 1},""" else ""
            """{$id"role":"tool","timestamp":$timestamp}"""
        }

    private val readableReply = """{"role":"assistant","content":"Done.","timestamp":1050}"""

    @Test
    fun aReplyOnPageTwoMakesTheBotUnread() = runTest(dispatcher, timeout = 10.seconds) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, fixedRoster = historyRoster())
        val offsets = mutableListOf<Int>()
        val vm = viewModel(socket) { _, offset ->
            offsets += offset
            if (offset == 0) historyPage(*toolRows()) else historyPage(readableReply)
        }
        vm.bind(gateway.gatewayUrl)
        vm.state.first { "work" in it.unread }
        assertEquals(listOf(0, 100), offsets)
    }

    @Test
    fun aFailedFetchKeepsTheBadgeAndTheNextRefreshCanClearIt() = runTest(dispatcher, timeout = 10.seconds) {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, fixedRoster = historyRoster(nextActivity = 1050))
        var response = historyPage(readableReply)
        var nextResponse = historyPage(readableReply)
        var reads = 0
        val vm = viewModel(socket) { profile, _ ->
            if (profile == "after") nextResponse else {
                reads++
                response
            }
        }
        vm.bind(gateway.gatewayUrl)
        vm.state.first { it.unread == setOf("work", "after") }

        socket.fixedRoster = historyRoster(activity = 1200, nextActivity = 1150)
        response = HttpStatusCode.ServiceUnavailable to "{}"
        nextResponse = historyPage()
        vm.refresh()
        val afterFailure = vm.state.first { "after" !in it.unread }
        assertEquals(2, reads)
        assertEquals(setOf("work"), afterFailure.unread)

        response = historyPage("""{"role":"tool","timestamp":1200}""")
        vm.refresh()
        vm.state.first { it.unread.isEmpty() }
        assertEquals(3, reads)
    }

    @Test
    fun anUntimedToolRowDoesNotExtendAReadPage() = runTest(dispatcher, timeout = 10.seconds) {
        val offsets = scannedOffsets { offset ->
            if (offset == 0) {
                val rows = toolRows(timestamp = 900)
                rows[99] = """{"id":100,"role":"tool"}"""
                historyPage(*rows)
            } else historyPage()
        }
        assertEquals(listOf(0), offsets)
    }

    @Test
    fun aRepeatedPageWithoutRowIdsStopsTheScan() = runTest(dispatcher, timeout = 10.seconds) {
        val offsets = scannedOffsets { offset ->
            if (offset < 200) historyPage(*toolRows(withIds = false)) else historyPage()
        }
        assertEquals(listOf(0, 100), offsets)
    }

    @Test
    fun pagesWithoutTimestampsCannotKeepTheScanGoingForever() = runTest(dispatcher, timeout = 10.seconds) {
        val offsets = scannedOffsets { offset ->
            if (offset < 700) historyPage(*toolRows(offset, timestamp = null)) else historyPage()
        }
        assertEquals(listOf(0, 100, 200, 300, 400), offsets)
    }

    private suspend fun scannedOffsets(
        page: (Int) -> Pair<HttpStatusCode, String>,
    ): List<Int> {
        val socket = RaceGateway(CoroutineScope(dispatcher), authority = false, fixedRoster = historyRoster(nextActivity = 1050))
        val offsets = mutableListOf<Int>()
        val vm = viewModel(socket) { profile, offset ->
            if (profile == "after") historyPage(readableReply) else {
                offsets += offset
                page(offset)
            }
        }
        vm.bind(gateway.gatewayUrl)
        // The second bot's badge confirms the worker has finished the first bot's scan.
        assertEquals(setOf("after"), vm.state.first { "after" in it.unread }.unread)
        return offsets
    }

    @Test
    fun onTheSessionAuthorityANewBotsChatTakenElsewhereOpensIt() = aNewBotsChatTakenElsewhereOpensIt(authority = true)

    @Test
    fun onAnOlderGatewayANewBotsChatTakenElsewhereOpensIt() = aNewBotsChatTakenElsewhereOpensIt(authority = false)
}
