package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.phone.AgentScreens
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * What the app lends the agent's screens: the screens a `phone_show_ui` call waits on now, and where it's said
 * that one is on screen ([onShown]), a tap ([onAnswer]) and a screen that failed or ended ([onFail]) go. Each
 * takes the chat's stored id first.
 */
internal class AgentScreenContext(
    val live: List<AgentScreens.Live>,
    val onShown: (chat: String?, surfaceIds: Set<String>) -> Unit,
    val onAnswer: (chat: String?, surfaceId: String, result: JsonElement) -> Unit,
    val onFail: (chat: String?, surfaceId: String, message: String) -> Unit,
) {
    companion object {
        val None = AgentScreenContext(emptyList(), { _, _ -> }, { _, _, _ -> }, { _, _, _ -> })
    }
}

internal val LocalAgentScreens = compositionLocalOf { AgentScreenContext.None }

/** The stored id of the chat the screens are drawn in, so a tap answers that chat's call only. */
internal val LocalAgentScreenChat = compositionLocalOf<String?> { null }

/**
 * Whether this platform draws agent screens. Off, a `phone_show_ui` call is only its line among the tools.
 */
internal expect val drawsAgentScreens: Boolean

/**
 * Draws [screen], the call [key]'s. Taps go to [onAnswer] only while [live]; at other times it's a picture of what
 * the agent showed. A drawing error goes to [onFail] once it's live, even if it came before.
 */
@Composable
internal expect fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier = Modifier,
)

/** A screen the agent drew with `phone_show_ui`, under the reply that made it: a card, with an accent edge while it waits. */
@Composable
internal fun AgentScreenCard(call: ToolActivity) {
    val screen = call.agentScreen ?: return
    if (!drawsAgentScreens) return
    val context = LocalAgentScreens.current
    val chat = LocalAgentScreenChat.current
    val waits = screen.wait && call.running
    val live = waits && context.live.any { waiting -> screen.surfaceIds.any { waiting.matches(chat, it) } }
    if (waits) {
        // Said again and again while the call runs: the phone may hear of the call after the card is drawn.
        LaunchedEffect(chat, screen.surfaceIds) {
            while (true) {
                context.onShown(chat, screen.surfaceIds)
                delay(SHOWN_EVERY_MS)
            }
        }
    }
    // The call ended unanswered here (stopped, or the agent gave up): the phone stops waiting too. Only a call
    // seen running: an old one in the history must not end a new call that reuses its surface id.
    var wasRunning by remember { mutableStateOf(call.running) }
    LaunchedEffect(call.running) {
        if (wasRunning && !call.running) screen.surfaceIds.forEach { context.onFail(chat, it, ENDED) }
        wasRunning = call.running
    }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentScreenSurface(
            key = call.id,
            screen = screen,
            live = live,
            onAnswer = { surface, result -> context.onAnswer(chat, surface, result) },
            onFail = { surface, message -> context.onFail(chat, surface, message) },
            modifier = Modifier
                .fillMaxWidth()
                // Bounded: the chat is a lazy list, and a screen's own List is one too.
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(Theme[colors][surface])
                .border(1.dp, if (live) Theme[colors][accentSoft] else Theme[colors][stroke], shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        val note = when {
            live -> "Waiting for your answer"
            waits -> "Opening…"
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = Theme[typography][caption],
                color = if (live) Theme[colors][accentText] else Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private const val SHOWN_EVERY_MS = 2_000L
private const val ENDED = "The call ended before the user answered."
