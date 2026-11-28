package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.A2uiEnvelope
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.NOT_DRAWN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The agent's screens in the chat that a `phone_show_ui` call waits on. The tool [await]s the user's answer; the
 * chat draws the screen from the call's arguments, says it's on screen with [shown], and hands a tap to [answer].
 * A screen is matched by its chat (the stored session id, when the gateway said which) and its surface ids, so the
 * same screen in another chat, or in this chat's history, takes no taps.
 */
class AgentScreens(private val shownWithinMs: Long = SHOWN_WITHIN_MS) {

    /** A screen a call waits on: [chat] is null when the gateway didn't say which chat called. */
    data class Live(val chat: String?, val surfaceIds: Set<String>, val transcriptId: String? = null) {
        fun matches(chat: String?, surfaceId: String, transcriptId: String? = null) =
            surfaceId in surfaceIds && (this.transcriptId == null || this.transcriptId == transcriptId) &&
                (this.chat == null || chat == null || this.chat == chat)
    }

    enum class DesktopStage { Validating, Showing, Ready }

    data class DesktopCall(
        val chat: String,
        val id: String,
        val surfaceIds: Set<String>,
        val stage: DesktopStage,
        val takesTaps: Boolean,
    )

    data class GatewayCard(
        val call: DesktopCall,
        val screen: AgentScreen,
        val owner: ChatSession?,
        val anchor: GatewayCardAnchor?,
        val isCurrent: () -> Boolean,
        val cardId: String = call.id,
    )

    private class Waiting(
        val live: Live,
        val screen: AgentScreen,
        val accepts: () -> Boolean,
        val callId: String?,
        val gatewayScreen: AgentScreen?,
        val ownsChat: () -> Boolean,
        val owner: ChatSession?,
        val anchor: GatewayCardAnchor?,
        val cardId: String = callId ?: "",
    ) {
        val shown = CompletableDeferred<Unit>()
        private val answer = CompletableDeferred<Result<JsonElement>>()
        var stage = DesktopStage.Validating
        val confirmed = mutableSetOf<String>()

        /** Ends the wait with [result]; an answer, or a failure, needs no more showing. */
        fun complete(result: Result<JsonElement>): Boolean = answer.complete(result).also { shown.complete(Unit) }

        fun matches(chat: String?, surface: String, callId: String?, transcriptId: String? = null): Boolean =
            !answer.isCompleted && this.callId == callId && accepts() &&
                if (callId == null) live.matches(chat, surface, transcriptId) else live.chat == chat && surface in live.surfaceIds

        fun desktopCall(): DesktopCall? = callId?.let {
            DesktopCall(checkNotNull(live.chat), it, live.surfaceIds, stage, screen.wait && stage == DesktopStage.Ready)
        }

        suspend fun await(): JsonElement = answer.await().getOrThrow()
    }

    private val waiting = MutableStateFlow<List<Waiting>>(emptyList())

    /** The messages each surface was sent, by chat and surface id, since Kyllene started: what a later call updates. */
    private val sent = MutableStateFlow<Map<Pair<String?, String>, List<String>>>(emptyMap())

    /**
     * What [chat]'s surfaces got before [screen], for the surfaces [screen] changes without making them (an
     * update), in the order they came: [screen] is checked on top of them. Null when one of them isn't known here
     * (made before Kyllene started, or never).
     */
    fun earlier(screen: AgentScreen, chat: String?): List<String>? {
        val parsed = screen.messages.mapNotNull(A2uiEnvelope::of)
        val made = parsed.filter { it.creates }.mapTo(HashSet()) { it.surfaceId }
        val known = sent.value
        return parsed.map { it.surfaceId }.filterNot { it in made }.distinct().flatMap { known[chat to it] ?: return null }
    }

    /**
     * A card in [chat] draws [screen], every message its surfaces got. Kept only for a surface not known here (made
     * before Kyllene started): for the others [record] knows better, as a card may show a call the phone is still
     * checking, or one drawn before a later call.
     */
    fun drawn(screen: AgentScreen, chat: String) = sent.update { known ->
        val bySurface = screen.messages.groupBy { A2uiEnvelope.of(it)?.surfaceId }
        bySurface.entries.fold(known) { map, (id, messages) ->
            if (id == null || (chat to id) in map) map else map + ((chat to id) to messages)
        }
    }

    /** What each surface was sent, by chat and surface id: changes when the phone takes a call. */
    val got: StateFlow<Map<Pair<String?, String>, List<String>>> get() = sent.asStateFlow()

    /** [screen] was drawn in [chat]: its messages join what its surfaces got, and a `createSurface` starts over. */
    fun record(screen: AgentScreen, chat: String?) = sent.update { known ->
        screen.messages.fold(known) { map, message ->
            val envelope = A2uiEnvelope.of(message) ?: return@fold map
            val key = chat to envelope.surfaceId
            map + (key to if (envelope.creates) listOf(message) else map[key].orEmpty() + message)
        }
    }

    /** The screens that take a tap now. */
    val live: StateFlow<List<Live>> get() = _live.asStateFlow()
    private val _live = MutableStateFlow<List<Live>>(emptyList())

    // Desktop registration and browser callbacks both run on the UI dispatcher. Keep a short record of
    // completed displays while the tool's completion event is still making its way back to the transcript.
    private val shownDesktop = ArrayDeque<DesktopCall>()
    private val _desktop = MutableStateFlow<List<DesktopCall>>(emptyList())
    val desktop: StateFlow<List<DesktopCall>> = _desktop.asStateFlow()
    private val _gatewayCards = MutableStateFlow<List<GatewayCard>>(emptyList())
    val gatewayCards: StateFlow<List<GatewayCard>> = _gatewayCards.asStateFlow()
    private val shownGatewayCards = ArrayDeque<GatewayCard>()

    /** True when every surface in [screen] already belongs to a live standalone card for [owner] in [chat]. */
    fun ownsGatewaySurface(screen: AgentScreen, chat: String, owner: ChatSession?): Boolean {
        if (screen.surfaceIds.isEmpty()) return false
        val owned = shownGatewayCards.filter { it.call.chat == chat && it.owner === owner && it.isCurrent() }
            .flatMapTo(HashSet()) { it.screen.surfaceIds }
        return screen.surfaceIds.all { it in owned }
    }

    /** Standalone updates can replay only the bounded, still-owned surfaces shown in this window. */
    fun gatewayDrawing(screen: AgentScreen, chat: String, owner: ChatSession?): AgentScreen {
        val creates = screen.envelopes.mapNotNullTo(HashSet()) { it?.takeIf { message -> message.creates }?.surfaceId }
        val bases = (screen.surfaceIds - creates).flatMap { surface ->
            val card = shownGatewayCards.lastOrNull {
                it.call.chat == chat && it.owner === owner && it.isCurrent() && surface in it.screen.surfaceIds
            } ?: throw PhoneToolException(NOT_DRAWN + "The screen to update is no longer available. Create a new surface.")
            card.screen.messages.zip(card.screen.envelopes).filter { (_, envelope) -> envelope?.surfaceId == surface }
                .map { (message, _) -> message }
        }
        val combined = bases + screen.messages
        val messages = if (combined.size <= AgentScreen.MAX_MESSAGES) {
            combined
        } else {
            val compacted = compactGatewayHistory(bases) + screen.messages
            if (compacted.size > AgentScreen.MAX_MESSAGES) {
                throw PhoneToolException(NOT_DRAWN + "The screen update history is full. Create a new surface.")
            }
            compacted
        }
        return screen.copy(messages = messages)
    }

    private fun gatewayCardId(screen: AgentScreen, chat: String, owner: ChatSession?, fallback: String): String {
        val matching = shownGatewayCards.filter {
            it.call.chat == chat && it.owner === owner && it.isCurrent() &&
                it.call.surfaceIds.all(screen.surfaceIds::contains)
        }.mapTo(LinkedHashSet()) { it.cardId }
        return matching.singleOrNull() ?: fallback
    }

    suspend fun awaitGatewayCard(
        update: AgentScreen,
        screen: AgentScreen,
        chat: String,
        id: String,
        owner: ChatSession? = null,
        anchor: GatewayCardAnchor? = null,
        notShown: String = NOT_SHOWN,
        changes: Flow<Unit> = emptyFlow(),
        isWaiting: () -> Boolean = { true },
        ownsChat: () -> Boolean,
    ): JsonElement = await(
        update, chat, notShown, id, { ownsChat() && isWaiting() }, screen, ownsChat, owner, anchor, changes,
        cardId = gatewayCardId(screen, chat, owner, id),
    )

    /**
     * Waits until the user answers [screen] in [chat], and returns what they did, for the agent. Throws
     * [PhoneToolException] when the chat doesn't show it soon (Kyllene is closed, or on another chat), when it
     * failed to draw, when the call ended, or when a newer call took over its surface.
     */
    suspend fun await(
        screen: AgentScreen,
        chat: String?,
        notShown: String = NOT_SHOWN,
        callId: String? = null,
        transcriptId: String? = null,
        changes: Flow<Unit> = emptyFlow(),
        accepts: () -> Boolean = { true },
    ): JsonElement = await(screen, chat, notShown, callId, accepts, null, accepts, null, null, changes, transcriptId)

    private suspend fun await(
        screen: AgentScreen,
        chat: String?,
        notShown: String,
        callId: String?,
        accepts: () -> Boolean,
        gatewayScreen: AgentScreen?,
        ownsChat: () -> Boolean,
        owner: ChatSession?,
        anchor: GatewayCardAnchor?,
        changes: Flow<Unit>,
        transcriptId: String? = null,
        cardId: String = callId ?: "",
    ): JsonElement = coroutineScope {
        require(callId == null || chat != null)
        if (!accepts()) throw PhoneToolException(notShown)
        val entry = Waiting(Live(chat, screen.surfaceIds, transcriptId), screen, accepts, callId, gatewayScreen, ownsChat, owner, anchor, cardId)
        var replaced = emptyList<Waiting>()
        waiting.update { list ->
            val (old, kept) = list.partition { it.live.chat == chat && it.live.surfaceIds.any(screen.surfaceIds::contains) }
            replaced = old
            kept + entry
        }
        replaced.forEach { it.complete(Result.failure(PhoneToolException(REPLACED))) }
        publish()
        val invalidate = launch {
            if (changes.firstOrNull { !accepts() } != null) {
                entry.complete(Result.failure(PhoneToolException(notShown)))
            }
        }
        try {
            withTimeoutOrNull(shownWithinMs) { entry.shown.await() } ?: throw PhoneToolException(notShown)
            entry.await()
        } finally {
            invalidate.cancel()
            waiting.update { list -> list - entry }
            publish()
        }
    }

    /** Stages changes for desktop until all cards affected by the call have independently validated them. */
    fun validated(chat: String?, surfaceIds: Set<String>, callId: String) {
        val entry = desktopConfirmation(chat, surfaceIds, callId, DesktopStage.Validating) ?: return
        entry.stage = DesktopStage.Showing
        publishDesktop()
    }

    /** The chat shows these surfaces. Desktop confirms all affected surfaces before accepting the batch. */
    fun shown(chat: String?, surfaceIds: Set<String>, callId: String? = null, transcriptId: String? = null) {
        if (callId == null) {
            waiting.value.filter { w -> surfaceIds.any { w.matches(chat, it, null, transcriptId) } }.forEach(::acknowledge)
            return
        }
        val entry = desktopConfirmation(chat, surfaceIds, callId, DesktopStage.Showing) ?: return
        entry.stage = DesktopStage.Ready
        val gatewayScreen = entry.gatewayScreen
        if (gatewayScreen == null) {
            record(entry.screen, chat)
            shownDesktop.addLast(checkNotNull(entry.desktopCall()).copy(takesTaps = false))
            if (shownDesktop.size > RECENT_DESKTOP_CALLS) shownDesktop.removeFirst()
        } else {
            val call = checkNotNull(entry.desktopCall()).copy(takesTaps = false)
            val kept = shownGatewayCards.mapNotNull { it.without(call, entry.owner) }.filter { it.isCurrent() }
            shownGatewayCards.clear()
            shownGatewayCards.addAll(kept)
            shownGatewayCards.addLast(GatewayCard(call, gatewayScreen, entry.owner, entry.anchor, entry.ownsChat, entry.cardId))
            if (shownGatewayCards.size > RECENT_GATEWAY_CARDS) shownGatewayCards.removeFirst()
        }
        publishDesktop()
        acknowledge(entry)
    }

    private fun desktopConfirmation(
        chat: String?,
        surfaceIds: Set<String>,
        callId: String,
        stage: DesktopStage,
    ): Waiting? {
        val entry = waiting.value.lastOrNull { w -> w.stage == stage && surfaceIds.any { w.matches(chat, it, callId) } }
            ?: return null
        entry.confirmed += surfaceIds.intersect(entry.live.surfaceIds)
        if (!entry.confirmed.containsAll(entry.live.surfaceIds)) return null
        entry.confirmed.clear()
        return entry
    }

    private fun acknowledge(entry: Waiting) {
        if (entry.screen.wait) entry.shown.complete(Unit)
        else entry.complete(Result.success(buildJsonObject { put("shown", true) }))
    }

    /** Hands the user's result to the call waiting on the card. Desktop additionally requires its own call ID. */
    fun answer(chat: String?, surfaceId: String, result: JsonElement, callId: String? = null, transcriptId: String? = null): Boolean =
        waiting.value.lastOrNull {
            it.screen.wait && (callId == null || it.stage == DesktopStage.Ready) && it.matches(chat, surfaceId, callId, transcriptId)
        }?.complete(Result.success(result)) == true

    fun fail(chat: String?, surfaceId: String, message: String, callId: String? = null, transcriptId: String? = null): Boolean =
        waiting.value.lastOrNull { it.matches(chat, surfaceId, callId, transcriptId) }
            ?.complete(Result.failure(PhoneToolException(message))) == true

    private fun publish() {
        _live.update { waiting.value.filter { it.callId == null && it.screen.wait }.map { it.live } }
        publishDesktop()
    }

    private fun publishDesktop() {
        val entries = waiting.value
        _desktop.value = (shownDesktop + entries.filter { it.gatewayScreen == null }.mapNotNull { it.desktopCall() })
            .associateBy { it.chat to it.id }.values.toList()
        val pendingCards = entries.mapNotNull { entry ->
            entry.gatewayScreen?.let { GatewayCard(checkNotNull(entry.desktopCall()), it, entry.owner, entry.anchor, entry.accepts, entry.cardId) }
        }
        shownGatewayCards.removeAll { !it.isCurrent() }
        _gatewayCards.value = shownGatewayCards.mapNotNull { shown ->
            pendingCards.fold<GatewayCard, GatewayCard?>(shown) { remaining, pending ->
                remaining?.without(pending.call, pending.owner)
            }
        } + pendingCards
    }

    private fun GatewayCard.without(replacement: DesktopCall, owner: ChatSession?): GatewayCard? {
        if (this.owner !== owner || call.chat != replacement.chat) return this
        val remaining = call.surfaceIds - replacement.surfaceIds
        if (remaining.size == call.surfaceIds.size) return this
        if (remaining.isEmpty()) return null
        val messages = screen.messages.zip(screen.envelopes).filter { (_, envelope) -> envelope?.surfaceId in remaining }
            .map { (message, _) -> message }
        return copy(call = call.copy(surfaceIds = remaining), screen = screen.copy(messages = messages, surfaceIds = remaining))
    }

    companion object {
        /**
         * Whether the phone took [screen]'s updates in [chat], with [got] what its surfaces got: each surface it
         * changes without making it ends with them. A screen with no updates was always taken.
         */
        fun took(screen: AgentScreen, chat: String?, got: Map<Pair<String?, String>, List<String>>): Boolean {
            val parsed = screen.messages.mapNotNull { message -> A2uiEnvelope.of(message)?.let { message to it } }
            val made = parsed.filter { it.second.creates }.mapTo(HashSet()) { it.second.surfaceId }
            return parsed.filterNot { it.second.surfaceId in made }.groupBy({ it.second.surfaceId }, { it.first }).all { (id, messages) ->
                val known = got[chat to id] ?: got[null to id] ?: return@all false
                known.size >= messages.size && known.subList(known.size - messages.size, known.size) == messages
            }
        }

        /** A screen the chat doesn't show by then isn't waited on: nobody would see it. */
        const val SHOWN_WITHIN_MS = 15_000L
        private const val RECENT_DESKTOP_CALLS = 128
        private const val RECENT_GATEWAY_CARDS = 8

        const val REPLACED = "A newer screen with the same surface id took its place."
        const val NOT_SHOWN = "Kyllene isn't showing this chat on the phone right now, so the user can't see the " +
            "screen. Ask in words instead, or try again when they're in the chat."
    }
}

/** The desktop binds a request to its gateway and profile as well as its stored chat id. */
fun desktopAgentScreenChat(gateway: String, profile: String?, chat: String): String =
    buildJsonArray {
        add(JsonPrimitive(gateway))
        add(profile?.let { JsonPrimitive(it) } ?: JsonNull)
        add(JsonPrimitive(chat))
    }.toString()

/** Standalone surfaces have their own namespace, separate from transcript cards. */
fun gatewayAgentScreenChat(gateway: String, profile: String?, chat: String): String =
    "gateway:${desktopAgentScreenChat(gateway, profile, chat)}"

/**
 * Compacts historical standalone updates so multi-tap plugin loops don't hit [AgentScreen.MAX_MESSAGES]:
 * keeps only messages from the latest createSurface/deleteSurface per surface, merges updateComponents by
 * component id, and drops superseded updateDataModel writes on the same or child paths.
 */
internal fun compactGatewayHistory(messages: List<String>): List<String> {
    data class DataUpdate(val path: String, val raw: String)
    class SurfaceHistory {
        var created: String? = null
        var deleted: String? = null
        var componentsEnvelope: JsonObject? = null
        val components = LinkedHashMap<String, JsonElement>()
        val dataUpdates = mutableListOf<DataUpdate>()
        val other = mutableListOf<String>()

        fun reset() {
            created = null
            deleted = null
            componentsEnvelope = null
            components.clear()
            dataUpdates.clear()
            other.clear()
        }

        fun emit(): List<String> = buildList {
            created?.let(::add)
            deleted?.let(::add)
            componentsEnvelope?.let { envelope ->
                val body = envelope["updateComponents"] as? JsonObject
                if (body != null && components.isNotEmpty()) {
                    val mergedBody = JsonObject(body + ("components" to JsonArray(components.values.toList())))
                    add(JsonObject(envelope + ("updateComponents" to mergedBody)).toString())
                }
            }
            dataUpdates.forEach { add(it.raw) }
            addAll(other)
        }
    }

    val bySurface = LinkedHashMap<String, SurfaceHistory>()
    val ungrouped = mutableListOf<String>()
    for (raw in messages) {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val surface = A2uiEnvelope.of(raw)?.surfaceId
        if (obj == null || surface == null) {
            ungrouped += raw
            continue
        }
        val history = bySurface.getOrPut(surface) { SurfaceHistory() }
        when {
            obj["createSurface"] is JsonObject -> {
                history.reset()
                history.created = raw
            }
            obj["deleteSurface"] is JsonObject -> {
                history.reset()
                history.deleted = raw
            }
            obj["updateComponents"] is JsonObject -> {
                val body = obj["updateComponents"] as JsonObject
                val items = body["components"] as? JsonArray
                if (items != null) {
                    history.componentsEnvelope = obj
                    for (item in items) {
                        val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                        if (id != null) history.components[id] = item else history.other += raw
                    }
                } else {
                    history.other += raw
                }
            }
            obj["updateDataModel"] is JsonObject -> {
                val body = obj["updateDataModel"] as JsonObject
                val rawPath = (body["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val path = if (rawPath.isEmpty() || rawPath == "/") "/" else rawPath.trimEnd('/')
                if (path == "/") {
                    history.dataUpdates.clear()
                } else {
                    val childPrefix = "$path/"
                    history.dataUpdates.removeAll { it.path == path || it.path.startsWith(childPrefix) }
                }
                history.dataUpdates += DataUpdate(path, raw)
            }
            else -> history.other += raw
        }
    }
    return buildList {
        bySurface.values.forEach { addAll(it.emit()) }
        addAll(ungrouped)
    }
}
