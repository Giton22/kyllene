package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.kanbanBoard
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D26's shared reads: one combined refresh fetches the sessions page, the cron jobs and the boards
 * list once each — not once per widget. The old per-widget re-fetching spent the whole 8 s deadline
 * on duplicate requests, so the tail widgets always fell back to their stored snapshots with a
 * "Couldn't refresh" notice even right after a refresh.
 */
class WidgetSharedReadsTest {

    private val url = GatewayUrl.parse("https://gw.example.ts.net")

    private class Harness(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val paths = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var boardsJson = """{"boards":[],"current":""}"""

        private val engine = MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val path = request.url.encodedPath
                paths += path
                queries += request.url.encodedQuery.orEmpty()
                val body = when {
                    path.endsWith("/api/sessions") -> """{"sessions":[]}"""
                    path.endsWith("/api/cron/jobs") -> "[]"
                    path.endsWith("/kanban/boards") -> boardsJson
                    path.endsWith("/kanban/board") -> """{"columns":[],"tenants":[],"assignees":[],"latest_event_id":0,"now":1}"""
                    else -> "{}"
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        private val client = createHttpClient(engine)
        private val connection = GatewayConnection(
            AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())),
            { _, _ -> error("a REST-only refresh never opens a socket") },
            scope,
        )
        private val sessionsApi = SessionsApi(client)
        private val host = ChatHost(connection, sessionsApi, scope)
        private val active = ActiveSessions(connection, scope, clock = { 1L })
        val settings = SettingsStore(InMemoryKeyValueStore(), scope)
        val assembler = WidgetDataAssembler(
            sessionsApi = sessionsApi,
            cronApi = CronApi(client),
            kanbanApi = KanbanApi(client),
            botsApi = BotsApi(connection),
            attention = AttentionTracker(connection, host, active, scope, clock = { 1L }),
            botHealth = BotHealth(connection, host, scope) { error("no runtime checks in a REST-only test") },
            seenStore = SeenStore(InMemoryKeyValueStore(), now = { 1.0 }),
            offline = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 1L },
            settings = settings,
            snapshotStore = WidgetSnapshotStore(InMemoryKeyValueStore()),
            dismissals = WidgetAlertDismissals(InMemoryKeyValueStore()),
            nowSeconds = { 1_800_000_000L },
        )

        fun count(suffix: String) = paths.count { it.endsWith(suffix) }
    }

    @Test
    fun aColdPassReadsEachRestInputOnce() = runTest {
        val harness = Harness(backgroundScope, StandardTestDispatcher(testScheduler))
        val all = harness.assembler.refreshAllCold(url, profile = null, knownBots = emptyList())
        assertEquals(1, harness.count("/api/sessions"))
        assertEquals(1, harness.count("/api/cron/jobs"))
        assertEquals(1, harness.count("/kanban/boards"))
        // No boards on the gateway: the reachable "No Kanban board" state, and no board detail read.
        assertEquals(0, harness.count("/kanban/board"))
        assertTrue(all.now != null && all.chats != null && all.scheduled != null)
        assertEquals("", all.kanban?.boardSlug)
    }

    @Test
    fun aLivePassSharesTheSameReads() = runTest {
        val harness = Harness(backgroundScope, StandardTestDispatcher(testScheduler))
        // The socket never opens in this harness; the roster read fails honestly and the pass continues.
        val all = harness.assembler.refreshAllLive(url, profile = null)
        assertEquals(1, harness.count("/api/sessions"))
        assertEquals(1, harness.count("/api/cron/jobs"))
        assertEquals(1, harness.count("/kanban/boards"))
        assertTrue(all.now != null && all.chats != null && all.scheduled != null)
    }

    @Test
    fun theChosenBoardIsReadOnceWithItsExplicitSlug() = runTest {
        val harness = Harness(backgroundScope, StandardTestDispatcher(testScheduler))
        harness.boardsJson = """{"boards":[{"slug":"feedbucket","name":"FeedBucket"},{"slug":"waypoint","name":"Waypoint"}],"current":"feedbucket"}"""
        val all = harness.assembler.refreshAllCold(url, profile = null, knownBots = emptyList())
        assertEquals(1, harness.count("/kanban/board"))
        assertEquals("feedbucket", all.kanban?.boardSlug)
        assertEquals(2, all.kanban?.boardCount)
        assertTrue(harness.queries.any { it.contains("board=feedbucket") })
    }

    @Test
    fun theSwitchButtonPinsTheNextBoardAndWraps() = runTest {
        val harness = Harness(backgroundScope, StandardTestDispatcher(testScheduler))
        harness.boardsJson = """{"boards":[{"slug":"feedbucket","name":"FeedBucket"},{"slug":"waypoint","name":"Waypoint"}],"current":"feedbucket"}"""
        assertEquals("waypoint", harness.assembler.switchToNextBoard(url))
        assertEquals("waypoint", harness.settings.settings.filterNotNull().first().kanbanBoard(url))
        assertEquals("feedbucket", harness.assembler.switchToNextBoard(url))
        assertEquals("feedbucket", harness.settings.settings.filterNotNull().first().kanbanBoard(url))
    }

    @Test
    fun switchingWithOneBoardChangesNothing() = runTest {
        val harness = Harness(backgroundScope, StandardTestDispatcher(testScheduler))
        harness.boardsJson = """{"boards":[{"slug":"only","name":"Only"}],"current":"only"}"""
        assertEquals(null, harness.assembler.switchToNextBoard(url))
        assertEquals(null, harness.settings.settings.filterNotNull().first().kanbanBoard(url))
    }
}