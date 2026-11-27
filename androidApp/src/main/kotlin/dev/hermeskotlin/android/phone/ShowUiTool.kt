package dev.hermeskotlin.android.phone

import android.content.Context
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.awaitScreenPlacement
import dev.hermeskotlin.core.phone.gatewayAgentScreenChat
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.ui.chat.agentScreenProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * A screen the agent draws in the chat (experiment). The chat draws it from the call's own arguments; this checks
 * Kyllene can draw it and, when the agent waits, hands back what the user tapped there, in the chat that called.
 */
class ShowUiTool(
    private val context: Context,
    private val screens: AgentScreens,
    private val chats: ChatTargets,
    private val host: ChatHost,
    private val connection: GatewayConnection,
) {

    suspend fun run(args: JsonObject, session: String, origin: JsonRpcClient?): JsonElement {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(
                "messages must be a list of 1 to ${AgentScreen.MAX_MESSAGES} A2UI messages (JSON objects), each naming its surfaceId.",
            )
        val open = host.session.value
        val state = open?.state?.value
        val stored = state?.storedSessionId
        if (open != null && stored != null && session.isNotBlank() && (session == stored || session == state.runtimeSessionId)) {
            val accepts = {
                origin != null && (connection.state.value as? ConnectionState.Connected)?.let {
                    it.client === origin && it.gateway == open.gateway
                } == true && host.session.value === open && host.ownerVersion.value == open.ownerVersion
            }
            if (!accepts()) throw PhoneToolException(AgentScreens.NOT_SHOWN)
            val placement = awaitScreenPlacement(open.state, screen, accepts) { false }
            if (placement.transcriptCallId == null) {
                val key = gatewayAgentScreenChat(open.gateway.value, open.profile, stored)
                val drawing = checkedScreen(screen, key)
                if (!accepts()) throw PhoneToolException(AgentScreens.NOT_SHOWN)
                return screens.awaitGatewayCard(
                    screen, drawing, key, Uuid.random().toString(),
                    isWaiting = { placement.canShowStandalone(open.state.value) },
                    ownsChat = accepts,
                )
            }
        }
        // The chat's stored id, as the chat screen knows it; the gateway may name it by its live id.
        val chat = chats.of(session)?.storedSessionId ?: session.takeIf { it.isNotBlank() }
        checkedScreen(screen, chat)
        screens.record(screen, chat)
        if (!screen.wait) return buildJsonObject { put("shown", true) }
        return screens.await(screen, chat)
    }

    private suspend fun checkedScreen(screen: AgentScreen, chat: String?): AgentScreen {
        val earlier = screens.earlier(screen, chat) ?: throw PhoneToolException(NOT_DRAWN + UNKNOWN_SURFACE)
        val drawing = screen.copy(messages = earlier + screen.messages)
        val problem = withContext(Dispatchers.IO) { agentScreenProblem(context, drawing) }
        if (problem != null) throw PhoneToolException(NOT_DRAWN + problem)
        return drawing
    }

    private companion object {
        const val UNKNOWN_SURFACE = "A message updates a surface this chat doesn't show: start a new screen with " +
            "createSurface, or use the surfaceId of a screen you showed in this chat."
    }
}
