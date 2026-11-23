package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.LiveSessions
import dev.hermeskotlin.core.sessions.LiveStatus
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What a chat is waiting on the user for, in words: the list shows it as text, not only as a colour. */
enum class Waiting(val label: String) {
    Approval("Needs approval"),
    Question("Has a question"),
    Input("Needs input"),

    /** The gateway says the chat waits on an answer, but this phone hasn't seen the request (it went to another client). */
    Unknown("Waiting for you"),
    ;

    companion object {
        fun of(request: InputRequest): Waiting = when (request) {
            is InputRequest.Approval -> Approval
            is InputRequest.Clarify -> Question
            is InputRequest.Secret, is InputRequest.VaultSaveLogin -> Input
        }
    }
}

/** An open request: the runtime session it's for, and when it arrived (epoch ms). */
private data class OpenRequest(val runtimeId: String, val request: InputRequest, val atMillis: Long)

/** Whether a runtime session's turn is running, as of [atMillis] (epoch ms). */
private data class Turn(val running: Boolean, val atMillis: Long)

/** State heard on one socket connection: cleared on sign-out or when another socket takes over. */
private data class TrackerState(
    val client: JsonRpcClient? = null,
    val links: Map<String, String> = emptyMap(),
    val requests: Map<String, OpenRequest> = emptyMap(),
    val turns: Map<String, Turn> = emptyMap(),
) {
    fun forClient(next: JsonRpcClient): TrackerState = when {
        client === next -> this
        client != null -> TrackerState(client = next)
        else -> copy(client = next)
    }

    fun withRequest(id: String, request: OpenRequest): TrackerState {
        val next = requests + (id to request)
        val trimmed = if (next.size <= MAX_REQUESTS) next else next - next.keys.take(next.size - MAX_REQUESTS)
        return copy(requests = trimmed)
    }

    companion object {
        const val MAX_REQUESTS = 200
    }
}

/**
 * The chats waiting on the user, and the chats with a turn running, by stored session id. Two sources feed
 * it. The first is what this phone hears itself: requests and turn events arrive by runtime session id, so
 * each chat's ids are linked as it attaches; a request stays until it's answered, withdrawn
 * (`request.cancel`) or its turn ends. The second is the gateway's own list of live sessions
 * ([ActiveSessions]), which also covers chats that only other clients opened. Where the two disagree, the
 * newer one wins. The open chat speaks for itself: its own requests are the ones it still shows.
 *
 * Both maps ask the gateway only while something collects them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionTracker(
    private val connection: GatewayConnection,
    host: ChatHost,
    private val active: ActiveSessions,
    scope: CoroutineScope,
    private val clock: () -> Long = { getTimeMillis() },
) {

    /** Links, open requests and turns heard on the current socket. */
    private val tracker = MutableStateFlow(TrackerState())

    private val open: StateFlow<ChatState?> = host.session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Cold tap on [ActiveSessions.live]: prunes settled requests only while [waiting] or [running] is collected. */
    private val live: Flow<LiveSessions> = active.live.onEach(::pruneStaleRequests)

    val waiting: StateFlow<Map<String, Waiting>> = combine(tracker, open, live) { state, open, live ->
        val openId = open?.storedSessionId
        buildMap {
            // Newest first: an answer from another client sends no `request.cancel`, so an older request for the
            // same chat may be one already answered (an approval, then the question that followed it).
            state.requests.values.reversed().forEach { (runtimeId, request, at) ->
                val storedId = state.links[runtimeId] ?: return@forEach
                // Asked after the request came and not waiting: answered elsewhere while this phone missed the word.
                val stale = live.askedAtMillis > at && live.statuses[storedId] != LiveStatus.Waiting
                if (storedId != openId && storedId !in this && !stale) put(storedId, Waiting.of(request))
            }
            live.statuses.forEach { (storedId, status) ->
                if (status == LiveStatus.Waiting && storedId != openId && storedId !in this) put(storedId, Waiting.Unknown)
            }
            if (openId != null) open.inputRequests.firstOrNull()?.let { put(openId, Waiting.of(it)) }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    /**
     * The newest open request of each other chat this phone heard, by stored session id: what [waiting] says it
     * waits on, in full, so a banner can say what is asked (the command, the question). Read by value when a
     * banner comes, so it is kept up eagerly; it only combines what is already in memory, and asks the gateway nothing.
     */
    val requestsByChat: StateFlow<Map<String, InputRequest>> = combine(tracker, open) { state, open ->
        val openId = open?.storedSessionId
        val latestLive = active.live.value
        buildMap {
            state.requests.values.reversed().forEach { (runtimeId, request, at) ->
                val storedId = state.links[runtimeId] ?: return@forEach
                val stale = latestLive.askedAtMillis > at && latestLive.statuses[storedId] != LiveStatus.Waiting
                if (storedId != openId && storedId !in this && !stale) put(storedId, request)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Whether each chat has a turn running, by stored session id. A chat neither source knows is left out:
     * the list's `is_active` can't stand in, since the gateway keeps it set while a session stays loaded,
     * long after its turn ends. A turn event heard after the last ask beats the gateway's answer, and an
     * answer newer than the event beats the event (a `message.complete` missed while offline). The open chat
     * speaks for itself, turns started elsewhere included.
     */
    val running: StateFlow<Map<String, Boolean>> = combine(tracker, open, live) { state, open, live ->
        buildMap {
            live.statuses.forEach { (storedId, status) -> put(storedId, status.running) }
            state.turns.forEach { (runtimeId, turn) ->
                val storedId = state.links[runtimeId] ?: return@forEach
                if (turn.atMillis >= live.askedAtMillis) put(storedId, turn.running)
                // The gateway was asked after this event and doesn't list the session: it isn't live, so idle.
                else if (storedId !in live.statuses) put(storedId, false)
            }
            open?.storedSessionId?.let { put(it, open.running) }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    /**
     * A chat this phone attached to without opening it ([SessionWatcher]): its requests and turns now count
     * for [storedId]. [running] and [openRequests] come from the attach reply, which also returns the
     * requests asked before this socket was attached.
     */
    fun watched(runtimeId: String, storedId: String, running: Boolean, openRequests: List<InputRequest>) {
        val client = connectedClient() ?: return
        val now = clock()
        tracker.update { current ->
            val base = current.forClient(client)
            val withLinkAndTurn = base.copy(
                links = base.links + (runtimeId to storedId),
                turns = base.turns + (runtimeId to Turn(running, now)),
            )
            openRequests.fold(withLinkAndTurn) { acc, req -> acc.withRequest(req.id, OpenRequest(runtimeId, req, now)) }
        }
    }

    /** [requestId] was answered from outside the open chat (a notification): it no longer waits. */
    fun answered(requestId: String) = tracker.update { it.copy(requests = it.requests - requestId) }

    private fun connectedClient(): JsonRpcClient? = (connection.state.value as? ConnectionState.Connected)?.client

    private fun pruneStaleRequests(live: LiveSessions) {
        if (live.askedAtMillis <= 0L) return
        tracker.update { state ->
            val kept = state.requests.filterValues { (runtimeId, _, at) ->
                val storedId = state.links[runtimeId] ?: return@filterValues true
                !(live.askedAtMillis > at && live.statuses[storedId] != LiveStatus.Waiting)
            }
            if (kept.size == state.requests.size) state else state.copy(requests = kept)
        }
    }

    init {
        scope.launch {
            connection.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> tracker.update { it.forClient(state.client) }
                    is ConnectionState.Idle,
                    is ConnectionState.SessionExpired,
                    is ConnectionState.Failed -> tracker.value = TrackerState()
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> Unit
                }
            }
        }
        scope.launch {
            connection.serverRequests.collect { request ->
                val client = connectedClient() ?: return@collect
                val runtimeId = request.sessionId ?: return@collect
                val parsed = InputRequest.parse(request.id, request.method, request.params) ?: return@collect
                val now = clock()
                tracker.update { it.forClient(client).withRequest(parsed.id, OpenRequest(runtimeId, parsed, now)) }
            }
        }
        scope.launch {
            connection.events.collect { event ->
                val client = connectedClient() ?: return@collect
                when (event.type) {
                    "clarify.request", "approval.request" -> {
                        val runtimeId = event.sessionId ?: return@collect
                        val parsed = InputRequest.parseEvent(event.type, event.payload as? JsonObject) ?: return@collect
                        val now = clock()
                        tracker.update { it.forClient(client).withRequest(parsed.id, OpenRequest(runtimeId, parsed, now)) }
                    }
                    "request.cancel", "approval.cancelled", "clarify.settled", "approval.settled", "approval.responded" -> {
                        val id = InputRequest.settledId(event.type, event.payload as? JsonObject)
                        if (id != null) tracker.update { it.copy(requests = it.requests - id) }
                    }
                    "message.start" -> event.sessionId?.let { runtimeId ->
                        val now = clock()
                        tracker.update { current ->
                            val base = current.forClient(client)
                            base.copy(turns = base.turns + (runtimeId to Turn(true, now)))
                        }
                    }
                    // Nothing can still wait once the turn is over.
                    "message.complete" -> event.sessionId?.let { runtimeId ->
                        val now = clock()
                        tracker.update { current ->
                            val base = current.forClient(client)
                            base.copy(
                                turns = base.turns + (runtimeId to Turn(false, now)),
                                requests = base.requests.filterValues { it.runtimeId != runtimeId },
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            var shownIn: String? = null
            var shown = emptySet<String>()
            open.collect { state ->
                val runtimeId = state?.runtimeSessionId
                val storedId = state?.storedSessionId
                val client = connectedClient()
                if (client != null && runtimeId != null) {
                    tracker.update { current ->
                        val base = current.forClient(client)
                        val nextLinks = if (storedId != null && base.links[runtimeId] != storedId) {
                            base.links + (runtimeId to storedId)
                        } else {
                            base.links
                        }
                        // Kept for after the user leaves it: the open chat knows, from its resume, of turns begun elsewhere.
                        val nextTurns = if (state.running != base.turns[runtimeId]?.running) {
                            base.turns + (runtimeId to Turn(state.running, clock()))
                        } else {
                            base.turns
                        }
                        if (nextLinks === base.links && nextTurns === base.turns) base
                        else base.copy(links = nextLinks, turns = nextTurns)
                    }
                }
                // Gone from the chat still open means answered here (or elsewhere): it no longer waits.
                // Switching chats isn't an answer, so only the same chat's requests are compared.
                val now = state?.inputRequests.orEmpty().mapTo(HashSet()) { it.id }
                if (storedId != null && storedId == shownIn) {
                    val answered = shown - now
                    if (answered.isNotEmpty()) tracker.update { it.copy(requests = it.requests - answered) }
                }
                shownIn = storedId
                shown = now
            }
        }
    }
}
