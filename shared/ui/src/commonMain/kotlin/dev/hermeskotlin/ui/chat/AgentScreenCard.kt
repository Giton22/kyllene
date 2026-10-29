package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ToolActivity
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
import kotlinx.serialization.json.JsonElement

/**
 * What the chat lends the agent's screens: the surfaces whose `phone_show_ui` call waits for a tap now, and where a
 * tap ([onAnswer]) or a screen that failed to draw ([onFail]) goes.
 */
internal class AgentScreenContext(
    val live: Set<String>,
    val onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    val onFail: (surfaceId: String, message: String) -> Unit,
)

internal val LocalAgentScreens = compositionLocalOf { AgentScreenContext(emptySet(), { _, _ -> }, { _, _ -> }) }

/**
 * Whether this platform draws agent screens. Off, a `phone_show_ui` call is only its line among the tools.
 */
internal expect val drawsAgentScreens: Boolean

/**
 * Draws [screen]. Taps and form changes go to [onAnswer] and drawing errors to [onFail], only while [live]; at
 * other times it's a picture of what the agent showed.
 */
@Composable
internal expect fun AgentScreenSurface(
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
    val live = screen.wait && call.running && screen.surfaceIds.any(context.live::contains)
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentScreenSurface(
            screen,
            live = live,
            onAnswer = context.onAnswer,
            onFail = context.onFail,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme[colors][surface])
                .border(1.dp, if (live) Theme[colors][accentSoft] else Theme[colors][stroke], shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        val note = when {
            live -> "Waiting for your answer"
            screen.wait && call.running -> "Opening…"
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
