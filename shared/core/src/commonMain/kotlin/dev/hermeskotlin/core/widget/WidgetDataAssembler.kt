package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.SavedList
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.common.runCatchingCancellable
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanBoardResponse
import dev.hermeskotlin.core.kanban.KanbanBoardsResponse
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionPage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.kanbanBoard
import dev.hermeskotlin.core.settings.withKanbanBoard
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Fetches the REST inputs, combines them with the caller's socket-side maps, computes all four widget
 * snapshots deterministically and writes them to [snapshotStore] (D9-D14, D26; `docs/WIDGETS-SPEC.md`).
 *
 * Two entry points mirror the two freshness modes:
 * - [refreshAllLive] (D13) runs inside the app process: it collects the live `AttentionTracker` /
 *   `BotHealth` state and the bot roster from the Koin singletons and derives Now fresh, stamped
 *   `socketSavedAtEpochSeconds = null` (live-fresh).
 * - [refreshAllCold] (D14) never opens a socket: it re-derives the REST-derivable tiers from REST alone
 *   and carries the socket-only rows of the stored Now snapshot across via [NowReducer.mergeCold].
 *
 * Both combined entry points share one set of REST reads across the four widgets (D26): the sessions
 * page, the cron jobs, the boards list and the roster are each fetched once per pass, the independent
 * reads run concurrently, and only the chosen board's detail waits on the boards list. The per-widget
 * [refreshNow] / [refreshChats] / [refreshKanban] / [refreshScheduled] methods keep the same pipelines
 * one widget at a time, each performing only the reads its widget needs.
 *
 * Failure semantics (D26): a failed Kanban or Scheduled read writes nothing — the renderer re-renders the
 * stored snapshot. Chats falls back (D9): `OfflineCache`, then the stored snapshot's rows with
 * `savedCopy = true`, then an empty snapshot — the widget always renders something honest.
 */
/**
 * The per-widget pipelines a refresher drives. An interface (not [WidgetDataAssembler] itself) so
 * the refresher's routing, deadline and fallback behaviour are testable without a gateway.
 */
interface WidgetRefreshPipelines {
    /** Every widget over REST alone (D14), with the saved roster for carried rows and bot-chat filtering. */
    suspend fun refreshAllCold(
        url: GatewayUrl,
        profile: String?,
        knownBots: List<Bot>,
        threadIds: Set<String> = emptySet(),
    ): WidgetDataAssembler.AllWidgets

    /** Every widget with the app process alive (D13), sharing one set of REST reads (D26). */
    suspend fun refreshAllLive(
        url: GatewayUrl,
        profile: String?,
        threadIds: Set<String> = emptySet(),
    ): WidgetDataAssembler.AllWidgets

    /** One widget's pipeline with the app process alive; only the reads that widget needs run (D26). */
    suspend fun refreshNow(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): NowWidgetSnapshot

    /** One widget's pipeline over REST alone (D14): carried socket rows merge across from the stored snapshot. */
    suspend fun refreshNowCold(url: GatewayUrl, profile: String?, knownBots: List<Bot>, threadIds: Set<String> = emptySet()): NowWidgetSnapshot

    /** One widget's pipeline with the app process alive; only the Recent page and its evidence run (D26). */
    suspend fun refreshChats(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): ChatsWidgetSnapshot

    /** One widget's pipeline: `boards()` and the chosen board, nothing else (D26). */
    suspend fun refreshKanban(url: GatewayUrl): KanbanWidgetSnapshot?

    /** One widget's pipeline: the cross-profile `jobs()` read and the warm roster, nothing else (D26). */
    suspend fun refreshScheduled(url: GatewayUrl, live: Boolean = true): ScheduledWidgetSnapshot?
}

class WidgetDataAssembler(
    private val sessionsApi: SessionsApi,
    private val cronApi: CronApi,
    private val kanbanApi: KanbanApi,
    private val botsApi: BotsApi,
    private val attention: AttentionTracker,
    private val botHealth: BotHealth,
    private val seenStore: SeenStore,
    private val offline: OfflineCache,
    private val settings: SettingsStore,
    private val snapshotStore: WidgetSnapshotStore,
    /** The user's dismissed Now alerts (D27); null in tests that don't exercise dismissal. */
    private val dismissals: WidgetAlertDismissals? = null,
    private val nowSeconds: () -> Long = { getTimeMillis() / 1000 },
) : WidgetRefreshPipelines {

    /** The socket-side state the app process can supply (empty everywhere cold, or in a pure test). */
    data class LiveMaps(
        val waiting: Map<String, Waiting> = emptyMap(),
        val runningChats: Map<String, Boolean> = emptyMap(),
        val bots: List<Bot> = emptyList(),
        val troubles: Map<String, BotTrouble> = emptyMap(),
    )

    data class AllWidgets(
        val now: NowWidgetSnapshot?,
        val chats: ChatsWidgetSnapshot?,
        val kanban: KanbanWidgetSnapshot?,
        val scheduled: ScheduledWidgetSnapshot?,
    )

    /** D13: refresh every widget from inside the app process, with live socket state. */
    override suspend fun refreshAllLive(url: GatewayUrl, profile: String?, threadIds: Set<String>): AllWidgets =
        refreshAllShared(url, profile, live = null, cold = false, threadIds = threadIds)

    /** D14: refresh every widget REST-only, using the saved roster without opening a socket. */
    override suspend fun refreshAllCold(
        url: GatewayUrl,
        profile: String?,
        knownBots: List<Bot>,
        threadIds: Set<String>,
    ): AllWidgets = refreshAllShared(url, profile, live = LiveMaps(bots = knownBots), cold = true, threadIds = threadIds)

    /** One widget's pipeline with the app process alive; only the reads that widget needs run (D26). */
    override suspend fun refreshNow(url: GatewayUrl, profile: String?, threadIds: Set<String>): NowWidgetSnapshot =
        nowPipeline(url, profile, liveMaps(), cold = false, threadIds)

    /**
     * One widget's pipeline over REST alone (D14): the carried socket rows of the stored Now snapshot
     * merge across via [NowReducer.mergeCold], judged for existence against [knownBots] — the roster a
     * background refresh has is the saved one (`BotNotifier.knownRoster`), since a broadcast-started
     * process never ran `profiles.list` over the socket.
     */
    override suspend fun refreshNowCold(url: GatewayUrl, profile: String?, knownBots: List<Bot>, threadIds: Set<String>): NowWidgetSnapshot =
        nowPipeline(url, profile, LiveMaps(bots = knownBots), cold = true, threadIds)

    /** One widget's pipeline with the app process alive; only the Recent page and its evidence run (D26). */
    override suspend fun refreshChats(url: GatewayUrl, profile: String?, threadIds: Set<String>): ChatsWidgetSnapshot =
        chatsPipeline(url, profile, liveMaps(), threadIds)

    /** One widget's pipeline: `boards()` and the chosen board, nothing else (D26). */
    override suspend fun refreshKanban(url: GatewayUrl): KanbanWidgetSnapshot? = kanbanPipeline(url)

    /** One widget's pipeline: the cross-profile `jobs()` read and the warm roster, nothing else (D26). */
    override suspend fun refreshScheduled(url: GatewayUrl, live: Boolean): ScheduledWidgetSnapshot? =
        scheduledPipeline(url, if (live) liveMaps() else LiveMaps())

    /**
     * The combined pipeline with caller-supplied socket maps. [refreshAllLive]/[refreshAllCold] keep the
     * freshness mode explicit; this overload serves tests that hold the maps themselves. Every REST read
     * is shared across the four widgets and best-effort, as D26's deadline demands, and `threadIds` is
     * the app's best-effort thread knowledge (empty cold).
     */
    suspend fun refreshAll(url: GatewayUrl, profile: String?, live: LiveMaps, cold: Boolean, threadIds: Set<String>): AllWidgets =
        refreshAllShared(url, profile, live, cold, threadIds)

    /**
     * The Tasks header's switch button: pin the board after the resolved one in the gateway's list
     * order (wrapping), the same pin the Kanban page writes, so the app and the widgets agree on the
     * board. Returns the new slug, or null when the boards can't be read or there is nothing to switch
     * to; the caller refreshes afterwards either way.
     */
    suspend fun switchToNextBoard(url: GatewayUrl): String? {
        val boards = runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull() ?: return null
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val current = KanbanWidgetAssembler.resolveBoard(boards, pin) ?: return null
        val next = KanbanWidgetAssembler.nextBoard(boards.boards, current.slug) ?: return null
        settings.update { it.withKanbanBoard(url, next.slug) }
        return next.slug
    }

    /**
     * One pass, one set of reads (D26): the four pipelines used to re-fetch the sessions page, the cron
     * jobs and the boards list each, which spent the whole refresh deadline on duplicate requests and
     * left the tail widgets on their stored snapshots ("Couldn't refresh" after every refresh). The
     * independent reads now run concurrently and every widget computes from the same answers.
     */
    private suspend fun refreshAllShared(url: GatewayUrl, profile: String?, live: LiveMaps?, cold: Boolean, threadIds: Set<String>): AllWidgets {
        val reads = fetchShared(url, profile, live)
        return AllWidgets(
            now = computeNow(url, profile, reads, cold),
            chats = computeChats(url, profile, reads, threadIds),
            kanban = computeKanban(url, reads),
            scheduled = computeScheduled(url, reads),
        )
    }

    /** What one pass read; a null field is a read that failed or that the pass didn't need. */
    private class SharedReads(
        val sessionsPage: SessionPage?,
        val offlineSaved: SavedList?,
        val seen: SeenChats?,
        val jobs: List<CronJob>?,
        val boards: KanbanBoardsResponse?,
        val pin: String?,
        val chosen: KanbanBoardMeta?,
        val board: KanbanBoardResponse?,
        val live: LiveMaps,
    )

    /** The shared reads: sessions, jobs, boards and (when live) the roster concurrently; the board detail after. */
    private suspend fun fetchShared(url: GatewayUrl, profile: String?, live: LiveMaps?): SharedReads = coroutineScope {
        val sessionsDeferred = async { readSessionsPage(url, profile) }
        val jobsDeferred = async { runCatchingCancellable { cronApi.jobs(url).orNull() }.getOrNull() }
        val boardsDeferred = async { runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull() }
        val liveDeferred = async { live ?: liveMaps() }
        val sessionsPage = sessionsDeferred.await()
        val offlineSaved = readOfflineSaved(url, profile, sessionsPage)
        val seen = readSeen(url, profile)
        val jobs = jobsDeferred.await()
        val boards = boardsDeferred.await()
        val liveMaps = liveDeferred.await()
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val chosen = boards?.let { KanbanWidgetAssembler.resolveBoard(it, pin) }
        val board = chosen?.let { runCatchingCancellable { kanbanApi.board(url, board = it.slug).orNull() }.getOrNull() }
        SharedReads(sessionsPage, offlineSaved, seen, jobs, boards, pin, chosen, board, liveMaps)
    }

    /** The socket-side maps as the app's Koin singletons currently hold them (D13). */
    private suspend fun liveMaps(): LiveMaps {
        val roster = runCatchingCancellable { botsApi.roster().bots }.getOrDefault(emptyList())
        return LiveMaps(
            waiting = attention.waiting.value,
            runningChats = attention.running.value,
            bots = roster,
            troubles = botHealth.troubles.value,
        )
    }

    // Now (D12-D14).
    private suspend fun nowPipeline(url: GatewayUrl, profile: String?, live: LiveMaps, cold: Boolean, threadIds: Set<String>): NowWidgetSnapshot {
        val sessionsPage = readSessionsPage(url, profile)
        val offlineSaved = readOfflineSaved(url, profile, sessionsPage)
        val jobs = runCatchingCancellable { cronApi.jobs(url).orNull() }.getOrNull()
        val boards = runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull()
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val chosen = boards?.let { KanbanWidgetAssembler.resolveBoard(it, pin) }
        val board = chosen?.let { runCatchingCancellable { kanbanApi.board(url, board = it.slug).orNull() }.getOrNull() }
        val seen = readSeen(url, profile)
        val reads = SharedReads(sessionsPage, offlineSaved, seen, jobs, boards, pin, chosen, board, live)
        return computeNow(url, profile, reads, cold)
    }

    private suspend fun computeNow(url: GatewayUrl, profile: String?, reads: SharedReads, cold: Boolean): NowWidgetSnapshot {
        val scope = url.value
        val clock = nowSeconds()
        val sessions = reads.sessionsPage?.sessions ?: reads.offlineSaved?.sessions.orEmpty()
        val nowInputs = NowInputs(
            sessions = sessions,
            sessionsKnown = reads.sessionsPage != null || reads.offlineSaved != null,
            seen = reads.seen,
            waiting = reads.live.waiting,
            runningChats = reads.live.runningChats,
            bots = reads.live.bots,
            troubles = reads.live.troubles,
            failingRoutines = failingRoutines(reads.live.bots, reads.jobs.orEmpty()),
            cronJobs = reads.jobs.orEmpty(),
            kanbanRows = reads.board?.columns.orEmpty().flatMap { it.tasks },
            kanbanBoardSlug = reads.chosen?.slug ?: reads.pin,
            nowSeconds = clock,
            dismissedAlerts = dismissals?.keys(scope).orEmpty(),
        )
        val now = if (cold) {
            NowReducer.mergeCold(scope, profile, snapshotStore.readNow(scope, profile), nowInputs, reads.live.bots)
        } else {
            NowReducer.derive(scope, profile, nowInputs)
        }
        snapshotStore.saveNow(now)
        return now
    }

    // Chats (D9): the REST page; else the offline saved list; else the stored snapshot's rows.
    private suspend fun chatsPipeline(url: GatewayUrl, profile: String?, live: LiveMaps, threadIds: Set<String>): ChatsWidgetSnapshot {
        val sessionsPage = readSessionsPage(url, profile)
        val offlineSaved = readOfflineSaved(url, profile, sessionsPage)
        val seen = readSeen(url, profile)
        val reads = SharedReads(sessionsPage, offlineSaved, seen, jobs = null, boards = null, pin = null, chosen = null, board = null, live = live)
        return computeChats(url, profile, reads, threadIds)
    }

    private suspend fun computeChats(url: GatewayUrl, profile: String?, reads: SharedReads, threadIds: Set<String>): ChatsWidgetSnapshot {
        val scope = url.value
        val clock = nowSeconds()
        // Both lineage ids: a compressed bot chat answers to either its canonical id or its open id.
        val botChatIds = reads.live.bots.flatMap { listOfNotNull(it.canonicalSession?.id, it.canonicalSession?.openId) }.toSet()
        val sessions = reads.sessionsPage?.sessions ?: reads.offlineSaved?.sessions.orEmpty()

        val chats = when {
            reads.sessionsPage != null -> ChatsWidgetAssembler.assemble(scope, profile, sessions, reads.seen, reads.live.waiting, reads.live.runningChats, botChatIds, threadIds, savedCopy = false, nowSeconds = clock)
            reads.offlineSaved != null -> ChatsWidgetAssembler.assemble(scope, profile, sessions, reads.seen, reads.live.waiting, reads.live.runningChats, botChatIds, threadIds, savedCopy = true, nowSeconds = clock)
            else -> snapshotStore.readChats(scope, profile)?.let { stored ->
                stored.copy(rows = stored.rows.map { it.copy(savedCopy = true) }, savedAtEpochSeconds = clock)
            } ?: ChatsWidgetSnapshot(gatewayUrl = scope, profile = profile, rows = emptyList(), savedAtEpochSeconds = clock)
        }
        snapshotStore.saveChats(chats)
        return chats
    }

    // Kanban (D10): the resolved slug — never the pin's alone — is what REST passed and what stores.
    private suspend fun kanbanPipeline(url: GatewayUrl): KanbanWidgetSnapshot? {
        val boards = runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull()
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val chosen = boards?.let { KanbanWidgetAssembler.resolveBoard(it, pin) }
        val board = chosen?.let { runCatchingCancellable { kanbanApi.board(url, board = it.slug).orNull() }.getOrNull() }
        val reads = SharedReads(sessionsPage = null, offlineSaved = null, seen = null, jobs = null, boards = boards, pin = pin, chosen = chosen, board = board, live = LiveMaps())
        return computeKanban(url, reads)
    }

    private suspend fun computeKanban(url: GatewayUrl, reads: SharedReads): KanbanWidgetSnapshot? {
        val scope = url.value
        val clock = nowSeconds()
        val boardsResult = reads.boards
        val chosen = reads.chosen
        val board = reads.board
        val kanban = if (boardsResult != null && chosen != null) {
            KanbanWidgetAssembler.assemble(
                gatewayUrl = scope,
                boards = boardsResult,
                board = board,
                boardSlug = chosen.slug,
                serverNowSeconds = board?.now ?: clock,
                deviceNowSeconds = clock,
            ).also { snapshotStore.saveKanban(it) }
        } else if (boardsResult != null) {
            // The boards list came back empty: the reachable "No Kanban board" state (design §8.3),
            // stored so a later failure doesn't resurrect a board the gateway no longer has.
            KanbanWidgetAssembler.assemble(
                gatewayUrl = scope,
                boards = boardsResult,
                board = null,
                boardSlug = "",
                serverNowSeconds = clock,
                deviceNowSeconds = clock,
            ).also { snapshotStore.saveKanban(it) }
        } else {
            val fallbackSlug = chosen?.slug ?: reads.pin
            if (fallbackSlug != null) snapshotStore.readKanban(scope, fallbackSlug) else null
        }
        return kanban
    }

    // Scheduled (D11): an empty success overwrites (a cleared gateway has no jobs); a failed read
    // leaves the stored snapshot standing.
    private suspend fun scheduledPipeline(url: GatewayUrl, live: LiveMaps): ScheduledWidgetSnapshot? {
        val jobs = runCatchingCancellable { cronApi.jobs(url).orNull() }.getOrNull()
        val reads = SharedReads(sessionsPage = null, offlineSaved = null, seen = null, jobs = jobs, boards = null, pin = null, chosen = null, board = null, live = live)
        return computeScheduled(url, reads)
    }

    private suspend fun computeScheduled(url: GatewayUrl, reads: SharedReads): ScheduledWidgetSnapshot? {
        val scope = url.value
        val clock = nowSeconds()
        val scheduled = if (reads.jobs != null) {
            ScheduledWidgetAssembler.assemble(scope, reads.jobs, reads.live.bots, clock)
                .also { snapshotStore.saveScheduled(it) }
        } else {
            snapshotStore.readScheduled(scope)
        }
        return scheduled
    }

    private suspend fun readSessionsPage(url: GatewayUrl, profile: String?) =
        runCatchingCancellable { sessionsApi.list(url, limit = 50, filter = SessionListFilter.Recent, profile = profile).orNull() }.getOrNull()

    private suspend fun readOfflineSaved(url: GatewayUrl, profile: String?, sessionsPage: SessionPage?) =
        if (sessionsPage == null) runCatchingCancellable { offline.savedList(url, profile, SessionListFilter.Recent) }.getOrNull() else null

    private suspend fun readSeen(url: GatewayUrl, profile: String?) =
        runCatchingCancellable { seenStore.seen(url, profile).first() }.getOrNull()

    /** The success value of an [ApiResult], or null for every failure shape. */
    private fun <T> dev.hermeskotlin.core.network.ApiResult<T>.orNull(): T? =
        (this as? dev.hermeskotlin.core.network.ApiResult.Success)?.value

    /** The app's own per-bot failing-routine rule, verbatim (`BotsViewModel`): D11 restricted to one bot's routines. */
    internal fun failingRoutines(bots: List<Bot>, jobs: List<CronJob>): Map<String, CronJob> =
        bots.mapNotNull { bot ->
            jobs.firstOrNull { it.isRoutineOf(bot.name) && ScheduledWidgetAssembler.isProblem(it) }
                ?.let { bot.name to it }
        }.toMap()
}