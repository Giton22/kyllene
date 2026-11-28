package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.connection.GatewayConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Waits briefly for the transcript's separately delivered call before showing a standalone card. */
private const val SCREEN_TRANSCRIPT_GRACE_MS = 1_000L

/** The reply keeps its live key until the transcript is reloaded from the gateway. */
data class GatewayCardAnchor(val liveKey: String, val toolId: String) {
    fun replyKey(messages: List<ChatMessage>): String? = messages.firstOrNull { it.key == liveKey }?.key
        ?: messages.asReversed().filterIsInstance<ChatMessage.Assistant>()
            .firstOrNull { message -> message.tools.any { it.id == toolId } }?.key
}

data class ScreenPlacement(val transcriptCallId: String?, private val outerToolIds: Set<String> = emptySet()) {
    fun canShowStandalone(state: ChatState): Boolean = state.runningScreenTools().any { it.id in outerToolIds }

    fun anchor(state: ChatState): GatewayCardAnchor? =
        state.messages.asReversed().filterIsInstance<ChatMessage.Assistant>().firstNotNullOfOrNull { message ->
            message.tools.firstOrNull { it.id in outerToolIds }?.let { GatewayCardAnchor(message.key, it.id) }
        }

    fun conflictsWithDirect(state: ChatState, screen: AgentScreen): Boolean =
        state.runningScreenTools().any { it.conflictsWith(screen) }
}

/**
 * The phone request and transcript travel separately. A standalone card requires a live outer tool in the
 * chat. The outer tool's name is not proof of provenance, so the card only identifies the gateway.
 */
suspend fun awaitScreenPlacement(
    states: Flow<ChatState>,
    screen: AgentScreen,
    accepts: () -> Boolean,
    notShown: String = AgentScreens.NOT_SHOWN,
    preferStandalone: Boolean = false,
    claimed: (String) -> Boolean,
): ScreenPlacement {
    val remainingMs = if (preferStandalone) {
        AgentScreens.SHOWN_WITHIN_MS
    } else {
        val direct = withTimeoutOrNull(SCREEN_TRANSCRIPT_GRACE_MS) {
            awaitTranscriptScreenCall(states, screen, accepts, notShown, claimed)
        }
        if (direct != null) return ScreenPlacement(direct)
        AgentScreens.SHOWN_WITHIN_MS - SCREEN_TRANSCRIPT_GRACE_MS
    }
    return withTimeoutOrNull(remainingMs) {
        runningTools(states, accepts, notShown).mapNotNull { calls ->
            val match = matchTranscriptScreenCall(calls.asSequence(), screen, claimed)
            when {
                match != null -> ScreenPlacement(match)
                calls.none { it.conflictsWith(screen) } -> calls.filter { it.agentScreen == null && it.name != SHOW_UI_TOOL }
                    .mapTo(HashSet()) { it.id }.takeIf { it.isNotEmpty() }?.let { ScreenPlacement(null, it) }
                else -> null
            }
        }.first()
    } ?: throw PhoneToolException(notShown)
}

/** Keep the original request alive if the transcript catches up before the standalone card is answered. */
suspend fun <T> runScreenPlacement(
    states: StateFlow<ChatState>,
    screen: AgentScreen,
    accepts: () -> Boolean,
    notShown: String = AgentScreens.NOT_SHOWN,
    claimed: (String) -> Boolean,
    preferStandalone: Boolean = false,
    direct: suspend (String) -> T,
    standalone: suspend (ScreenPlacement) -> T,
): T {
    val placement = awaitScreenPlacement(states, screen, accepts, notShown, preferStandalone, claimed)
    placement.transcriptCallId?.let { return direct(it) }
    return try {
        standalone(placement)
    } catch (failure: PhoneToolException) {
        if (!accepts() || !placement.conflictsWithDirect(states.value, screen)) throw failure
        val callId = withTimeoutOrNull(AgentScreens.SHOWN_WITHIN_MS) {
            awaitTranscriptScreenCall(states, screen, accepts, notShown, claimed)
        } ?: throw failure
        direct(callId)
    }
}

fun screenRequestChanges(host: ChatHost, chat: ChatSession, connection: GatewayConnection): Flow<Unit> =
    merge(host.session, host.ownerVersion, chat.state, connection.state).map { }

private fun ToolActivity.conflictsWith(screen: AgentScreen): Boolean =
    agentScreen?.let { same -> same.surfaceIds.any(screen.surfaceIds::contains) } ?: (name == SHOW_UI_TOOL)

internal suspend fun awaitTranscriptScreenCall(
    states: Flow<ChatState>,
    screen: AgentScreen,
    accepts: () -> Boolean,
    notShown: String = AgentScreens.NOT_SHOWN,
    claimed: (String) -> Boolean,
): String = runningTools(states, accepts, notShown).mapNotNull { calls ->
    matchTranscriptScreenCall(calls.asSequence(), screen, claimed)
}.first()

private fun runningTools(states: Flow<ChatState>, accepts: () -> Boolean, notShown: String): Flow<List<ToolActivity>> =
    states.map { state ->
        if (!accepts()) throw PhoneToolException(notShown)
        state.runningScreenTools().toList()
    }.distinctUntilChanged()

private fun ChatState.runningScreenTools(): Sequence<ToolActivity> =
    messages.asSequence().filterIsInstance<ChatMessage.Assistant>().flatMap { it.tools }.filter { it.running }

internal fun matchTranscriptScreenCall(
    calls: Sequence<ToolActivity>,
    screen: AgentScreen,
    claimed: (String) -> Boolean,
): String? {
    val messages = screen.messages.map(Json::parseToJsonElement)
    val candidates = calls.filter {
        val candidate = it.agentScreen
        it.running && candidate != null && !claimed(it.id) &&
            candidate.wait == screen.wait && candidate.surfaceIds == screen.surfaceIds &&
            candidate.messages.map(Json::parseToJsonElement) == messages
    }.take(2).toList()
    if (candidates.size > 1) throw PhoneToolException(
        NOT_DRAWN + "More than one pending tool call has these screen arguments. Give each screen its own surface ID.",
    )
    return candidates.singleOrNull()?.id
}
