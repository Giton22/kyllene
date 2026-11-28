package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ScreenView
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.screenViews
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.AgentScreens.DesktopStage
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/**
 * What the app lends the agent's screens: the screens a `phone_show_ui` call waits on now, and where it's said
 * that one is on screen ([onShown]), a tap ([onAnswer]), a screen that failed or ended ([onFail]) and what a card
 * draws, for a later call that updates it ([onDrawn]) go. Each takes the chat's stored id first.
 */
internal class AgentScreenContext(
    val live: List<AgentScreens.Live>,
    val onShown: (chat: String?, surfaceIds: Set<String>, transcriptId: String) -> Unit,
    /** True when a call waiting on the screen took the tap. */
    val onAnswer: (chat: String?, surfaceId: String, result: JsonElement, transcriptId: String) -> Boolean,
    val onFail: (chat: String?, surfaceId: String, message: String, transcriptId: String) -> Unit,
    val onDrawn: (chat: String?, screen: AgentScreen) -> Unit = { _, _ -> },
    val took: (chat: String?, screen: AgentScreen) -> Boolean = { _, _ -> true },
    val registry: AgentScreens? = null,
    val gatewayCards: List<AgentScreens.GatewayCard> = emptyList(),
    private val desktopCalls: List<AgentScreens.DesktopCall> = emptyList(),
) {
    fun desktopCall(chat: String?, id: String) = desktopCalls.find { it.chat == chat && it.id == id }

    companion object {
        val None = AgentScreenContext(emptyList(), { _, _, _ -> }, { _, _, _, _ -> false }, { _, _, _, _ -> })
    }
}

internal val LocalAgentScreens = compositionLocalOf { AgentScreenContext.None }

/** A standalone gateway card does not fetch images merely because the request arrived. */
internal val LocalAgentScreenLoadsRemoteImages = compositionLocalOf { true }
internal val LocalAgentScreenAllowsInput = compositionLocalOf { true }

@Composable
internal fun rememberAgentScreenContext(): AgentScreenContext {
    val koin = getKoin()
    val agentScreens = remember(koin) { koin.getOrNull<AgentScreens>() } ?: return LocalAgentScreens.current
    val liveScreens by agentScreens.live.collectAsStateWithLifecycle()
    val screensGot by agentScreens.got.collectAsStateWithLifecycle()
    val desktopCalls by agentScreens.desktop.collectAsStateWithLifecycle()
    val gatewayCards by agentScreens.gatewayCards.collectAsStateWithLifecycle()
    return remember(liveScreens, screensGot, desktopCalls, gatewayCards) {
        AgentScreenContext(
            liveScreens,
            onShown = { chat, ids, call -> agentScreens.shown(chat, ids, transcriptId = call) },
            onAnswer = { chat, id, result, call -> agentScreens.answer(chat, id, result, transcriptId = call) },
            onFail = { chat, id, message, call -> agentScreens.fail(chat, id, message, transcriptId = call) },
            // Only a chat Kyllene knows the id of: the phone's calls are kept by it.
            onDrawn = { chat, screen -> if (chat != null) agentScreens.drawn(screen, chat) },
            took = { chat, screen -> AgentScreens.took(screen, chat, screensGot) },
            registry = agentScreens,
            desktopCalls = desktopCalls,
            gatewayCards = gatewayCards,
        )
    }
}

/** The stored id of the chat the screens are drawn in, so a tap answers that chat's call only. */
internal val LocalAgentScreenChat = compositionLocalOf<String?> { null }

@Composable
internal expect fun agentScreenRendererAvailable(): Boolean

/**
 * Where a tap on a screen goes when no call waits on it: the chat's next message, from the user. Null where
 * taps can't be sent (a preview, a chat that isn't open).
 */
internal val LocalAgentScreenSend = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Draws [screen], the call [key]'s. Taps go to [onAnswer] only while [takesTaps]; at other times it's a picture of
 * what the agent showed. A drawing error goes to [onFail] once [live] (a call waits on it), even if it came before.
 */
@Composable
internal expect fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    takesTaps: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier = Modifier,
    onRendered: (Set<String>) -> Unit = {},
    draftKey: String = key,
    revision: String = key,
    validateOnly: Boolean = false,
)

/**
 * The chat's screens ([byCall], from [screenViews]) and the calls in its last message, once finished ([newest]):
 * a tap on a screen that one of them made or changed, and no call waits on, is sent as the next message. A
 * message after it (the user's own, or a new turn) means the conversation moved on.
 */
internal class ScreenViews(val byCall: Map<String, ScreenView>, val newest: Set<String>) {
    companion object {
        fun of(messages: List<ChatMessage>, took: (AgentScreen) -> Boolean = { true }) =
            ScreenViews(screenViews(calls(messages), took), newest(messages))

        /** Every tool call in the chat, in order: what the screens are made from. */
        fun calls(messages: List<ChatMessage>): List<ToolActivity> =
            messages.flatMap { (it as? ChatMessage.Assistant)?.tools.orEmpty() }

        /** The calls of the newest reply, once it finished. */
        fun newest(messages: List<ChatMessage>): Set<String> =
            (messages.lastOrNull() as? ChatMessage.Assistant)?.takeUnless { it.streaming }?.tools.orEmpty().mapTo(HashSet()) { it.id }
    }
}

internal val LocalScreenViews = compositionLocalOf { ScreenViews(emptyMap(), emptySet()) }

/**
 * A screen the agent drew with `phone_show_ui`, under the reply that made it, with what later calls changed on
 * it: a card, with an accent edge while it waits. The newest of those calls is the one that waits or ended.
 */
@Composable
internal fun AgentScreenCard(view: ScreenView, newest: Boolean) {
    val screen = view.screen
    val call = view.latest
    if (!agentScreenRendererAvailable()) return
    val context = LocalAgentScreens.current
    val chat = LocalAgentScreenChat.current
    val desktopCall = context.desktopCall(chat, call.id)
    val stage = desktopCall?.stage
    val waits = screen.wait && call.running
    val live = waits && if (isDesktop) {
        desktopCall?.takesTaps == true
    } else {
        context.live.any { waiting -> screen.surfaceIds.any { waiting.matches(chat, it, call.id) } }
    }
    var wasLive by rememberSaveable(call.id) { mutableStateOf(false) }
    var tappedLive by rememberSaveable(call.id) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    // Ready and Showing use the same browser result. Validation is a separate callback.
    val validating = stage == DesktopStage.Validating
    var rendered by remember(call.id, screen.messages, validating) { mutableStateOf<Set<String>>(emptySet()) }
    var drawError by remember(call.id, screen.messages, validating) { mutableStateOf<String?>(null) }
    if (live) wasLive = true
    if (isDesktop) {
        LaunchedEffect(chat, call.id, stage, rendered, drawError, started) {
            if (stage != null && stage != DesktopStage.Ready && call.running && started) {
                val registry = context.registry ?: return@LaunchedEffect
                val error = drawError
                if (error != null) {
                    screen.surfaceIds.forEach { registry.fail(chat, it, error, call.id) }
                } else if (validating) {
                    registry.validated(chat, rendered, call.id)
                } else {
                    registry.shown(chat, rendered, call.id)
                }
            } else if (!call.running && !call.failed && rendered.containsAll(screen.surfaceIds)) {
                context.onDrawn(chat, screen)
            }
        }
    } else {
        LaunchedEffect(chat, screen.messages) { context.onDrawn(chat, screen) }
        if (waits && (live || !wasLive) && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            LaunchedEffect(chat, screen.surfaceIds) {
                while (true) {
                    context.onShown(chat, screen.surfaceIds, call.id)
                    delay(SHOWN_EVERY_MS)
                }
            }
        }
    }
    // The call ended unanswered here (stopped, or the agent gave up): the phone stops waiting too. Only a call
    // seen running: an old one in the history must not end a new call that reuses its surface id.
    var wasRunning by rememberSaveable(call.id) { mutableStateOf(call.running) }
    LaunchedEffect(call.running) {
        if (wasRunning && !call.running) screen.surfaceIds.forEach {
            if (isDesktop) context.registry?.fail(chat, it, ENDED, call.id)
            else context.onFail(chat, it, ENDED, call.id)
        }
        wasRunning = call.running
    }
    // No call waits on it (the agent didn't wait, or the call ended without an answer): on the newest reply, a tap
    // still reaches the agent, as the user's next message. Once only, and never for a screen already answered.
    val send = LocalAgentScreenSend.current
    // Read when the tap comes, not as composed: taps come one after another, faster than the card recomposes.
    val sent = rememberSaveable(call.id) { mutableStateOf(false) }
    // Answered: the call returned a tap, or one was tapped here while it waited (its result may not be in yet). A
    // call that failed wasn't, even after a tap (the answer was too long, say): the user can tap again.
    val answered = !call.failed && (tappedLive || call.output.orEmpty().contains("\"event\""))
    val replies = newest && send != null && !call.running && !answered && !sent.value
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentScreenSurface(
            // The card's own call: a later update is drawn on the same screen, keeping what the user typed.
            key = view.owner.id,
            screen = screen,
            live = live,
            takesTaps = live || replies,
            onAnswer = { surface, result ->
                if (live) {
                    // Only a tap a call took: one just after the turn stopped went nowhere, and may be tapped again.
                    val answeredHere = if (isDesktop) context.registry?.answer(chat, surface, result, call.id) == true
                    else context.onAnswer(chat, surface, result, call.id)
                    if (answeredHere) tappedLive = true
                } else if (replies && send != null && !sent.value) {
                    sent.value = true
                    send(screenTapMessage(result))
                }
            },
            onFail = { surface, message ->
                if (isDesktop) drawError = message else context.onFail(chat, surface, message, call.id)
            },
            onRendered = { rendered = it },
            draftKey = JsonArray(listOf(JsonPrimitive(chat), JsonPrimitive(view.owner.id))).toString(),
            revision = call.id,
            validateOnly = validating,
            modifier = Modifier
                .fillMaxWidth()
                // Bounded, since the chat is a lazy list; a taller screen scrolls inside the card.
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Theme[colors][surface])
                .border(1.dp, if (live || replies) Theme[colors][accentSoft] else Theme[colors][stroke], shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        val note = when {
            live -> stringResource(Res.string.chat_screen_waiting)
            waits && !wasLive -> stringResource(Res.string.chat_screen_opening)
            replies -> stringResource(if (isDesktop) Res.string.chat_screen_click_answers else Res.string.chat_screen_tap_answers)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = Theme[typography][caption],
                color = if (live || replies) Theme[colors][accentText] else Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** A live, request-scoped card whose provenance is displayed by Kyllene outside the remote surface. */
@Composable
internal fun GatewayAgentScreenCard(card: AgentScreens.GatewayCard) {
    val registry = LocalAgentScreens.current.registry ?: return
    if (!agentScreenRendererAvailable()) return
    val (call, screen) = card
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val minimum = if (isDesktop) Lifecycle.State.STARTED else Lifecycle.State.RESUMED
    val validating = call.stage == DesktopStage.Validating
    var rendered by remember(call.id, validating) { mutableStateOf<Set<String>>(emptySet()) }
    var visible by remember(card.cardId) { mutableStateOf(false) }
    LaunchedEffect(call.stage, rendered, state, visible) {
        if (!state.isAtLeast(minimum)) return@LaunchedEffect
        when (call.stage) {
            DesktopStage.Validating -> registry.validated(call.chat, rendered, call.id)
            DesktopStage.Showing -> if (visible) registry.shown(call.chat, rendered, call.id)
            DesktopStage.Ready -> Unit
        }
    }

    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val takesTaps = visible && call.takesTaps && state.isAtLeast(minimum)
    val active = call.takesTaps || call.stage != DesktopStage.Ready
    Column(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            visible = !bounds.isEmpty
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(
                Lucide.ShieldAlert,
                contentDescription = null,
                tint = if (active) Theme[colors][warning] else Theme[colors][textTertiary],
                modifier = Modifier.size(13.dp),
            )
            Text(
                stringResource(Res.string.chat_gateway_screen_title),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
        }
        CompositionLocalProvider(
            LocalAgentScreenLoadsRemoteImages provides false,
            LocalAgentScreenAllowsInput provides takesTaps,
        ) {
            AgentScreenSurface(
                key = card.cardId,
                screen = screen,
                live = true,
                takesTaps = takesTaps,
                onAnswer = { surface, result ->
                    if (visible && lifecycle.currentState.isAtLeast(minimum)) registry.answer(call.chat, surface, result, call.id)
                },
                onFail = { surface, message -> registry.fail(call.chat, surface, message, call.id) },
                onRendered = { rendered = it },
                draftKey = JsonArray(listOf(JsonPrimitive(call.chat), JsonPrimitive(card.cardId))).toString(),
                revision = call.id,
                validateOnly = validating,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(shape)
                    .background(Theme[colors][surface])
                    .border(1.dp, if (takesTaps) Theme[colors][accentSoft] else Theme[colors][stroke], shape)
                    .padding(12.dp),
            )
        }
        val note = when {
            takesTaps -> stringResource(Res.string.chat_screen_waiting)
            call.stage != DesktopStage.Ready -> stringResource(Res.string.chat_screen_opening)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = Theme[typography][caption],
                color = if (takesTaps) Theme[colors][accentText] else Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * A tap on a screen no call waits on, as the user's message to the agent: the action's name, and what it and
 * the screen's form carried, so the agent reads the same as a waiting call would have returned.
 */
internal fun screenTapMessage(result: JsonElement): String {
    val tap = result as? JsonObject ?: return "Tapped a button on the screen you showed."
    val event = (tap["event"] as? JsonPrimitive)?.contentOrNull ?: "a button"
    val more = buildJsonObject {
        (tap["context"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("context", it) }
        (tap["data"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("data", it) }
    }
    val said = "Tapped “$event” on the screen you showed"
    return if (more.isEmpty()) "$said." else "$said: $more"
}

private const val SHOWN_EVERY_MS = 2_000L
private const val ENDED = "The call ended before the user answered."
