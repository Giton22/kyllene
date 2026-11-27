package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.ui.rememberDesktopWeb
import kotlinx.serialization.json.JsonElement

@Composable
internal actual fun agentScreenRendererAvailable(): Boolean = rememberDesktopWeb() != null

@Composable
internal actual fun AgentScreenSurface(
    key: String,
    screen: AgentScreen,
    live: Boolean,
    takesTaps: Boolean,
    onAnswer: (surfaceId: String, result: JsonElement) -> Unit,
    onFail: (surfaceId: String, message: String) -> Unit,
    modifier: Modifier,
    onRendered: (Set<String>) -> Unit,
    draftKey: String,
    revision: String,
    validateOnly: Boolean,
) {
    val renderer = rememberDesktopWeb() ?: return
    key(key) {
        renderer.AgentCard(draftKey, revision, screen, takesTaps, validateOnly, modifier, onRendered, onAnswer) {
            screen.surfaceIds.firstOrNull()?.let { onFail(it, NOT_DRAWN + "The desktop renderer could not display the A2UI card.") }
        }
    }
}
