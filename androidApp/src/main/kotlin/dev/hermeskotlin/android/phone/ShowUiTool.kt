package dev.hermeskotlin.android.phone

import android.content.Context
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.ui.chat.agentScreenProblem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A screen the agent draws in the chat (experiment). The chat draws it from the call's own arguments; this checks
 * Kyllene can draw it and, when the agent waits, hands back what the user tapped there, in the chat that called.
 */
class ShowUiTool(private val context: Context, private val screens: AgentScreens, private val chats: ChatTargets) {

    suspend fun run(args: JsonObject, session: String): JsonElement {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(
                "messages must be a list of 1 to ${AgentScreen.MAX_MESSAGES} A2UI messages (JSON objects), each naming its surfaceId.",
            )
        agentScreenProblem(context, screen)?.let { throw PhoneToolException(it) }
        if (!screen.wait) return buildJsonObject { put("shown", true) }
        // The chat's stored id, as the chat screen knows it; the gateway may name it by its live id.
        val chat = chats.of(session)?.storedSessionId ?: session.takeIf { it.isNotBlank() }
        return screens.await(screen, chat)
    }
}
