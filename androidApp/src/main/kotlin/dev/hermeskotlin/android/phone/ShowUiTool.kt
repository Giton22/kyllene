package dev.hermeskotlin.android.phone

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
 * they're A2UI and, when the agent waits, hands back what the user tapped there.
 */
class ShowUiTool(private val screens: AgentScreens) {

    suspend fun run(args: JsonObject): JsonElement {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(
                "messages must be a list of 1 to ${AgentScreen.MAX_MESSAGES} A2UI messages (JSON objects), each naming its surfaceId.",
            )
        agentScreenProblem(screen)?.let { throw PhoneToolException(it) }
        if (!screen.wait) return buildJsonObject { put("shown", true) }
        return screens.await(screen)
    }
}
