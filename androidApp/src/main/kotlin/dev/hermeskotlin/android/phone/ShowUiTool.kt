package dev.hermeskotlin.android.phone

import android.content.Context
import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.runScreenPlacement
import dev.hermeskotlin.core.phone.desktopAgentScreenChat
import dev.hermeskotlin.core.phone.gatewayAgentScreenChat
import dev.hermeskotlin.core.phone.screenRequestChanges
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
internal class ShowUiTool(
    private val context: Context,
    private val screens: AgentScreens,
    private val chats: ChatTargets,
    private val host: ChatHost,
    private val connection: GatewayConnection,
) {

    private val claimed = LinkedHashSet<Pair<String, String>>()

    suspend fun run(args: JsonObject, session: String, origin: JsonRpcClient?): JsonElement {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(
                "messages must be a list of 1 to ${AgentScreen.MAX_MESSAGES} A2UI messages (JSON objects), each naming its surfaceId.",
            )
        val socket = connection.state.value as? ConnectionState.Connected
        if (origin == null || socket?.client !== origin) throw PhoneToolException(AgentScreens.NOT_SHOWN)
        val connected = { (connection.state.value as? ConnectionState.Connected)?.client === origin }
        val open = host.session.value
        val state = open?.state?.value
        val stored = state?.storedSessionId
        if (open != null && stored != null && session.isNotBlank() && (session == stored || session == state.runtimeSessionId)) {
            val ownsChat = {
                connected() && socket.gateway == open.gateway &&
                    host.session.value === open && host.ownerVersion.value == open.ownerVersion
            }
            if (!ownsChat()) throw PhoneToolException(AgentScreens.NOT_SHOWN)
            val transcriptKey = desktopAgentScreenChat(open.gateway.value, open.profile, stored)
            val gatewayKey = gatewayAgentScreenChat(open.gateway.value, open.profile, stored)
            val changes = screenRequestChanges(host, open, connection)
            return runScreenPlacement(
                open.state, screen, ownsChat, claimed = { transcriptKey to it in claimed },
                preferStandalone = screens.ownsGatewaySurface(screen, gatewayKey, open),
                direct = { callId ->
                    claimed += transcriptKey to callId
                    if (claimed.size > 128) claimed.remove(claimed.first())
                    val accepts = { ownsChat() && open.state.value.messages.any { message ->
                        message is ChatMessage.Assistant && message.tools.any { it.running && it.id == callId }
                    } }
                    prepareTranscript(screen, transcriptKey, accepts)
                    if (screen.wait) screens.await(screen, transcriptKey, transcriptId = callId, changes = changes, accepts = accepts)
                    else buildJsonObject { put("shown", true) }
                },
                standalone = { placement ->
                    val drawing = checkedScreen { screens.gatewayDrawing(screen, gatewayKey, open) }
                    screens.awaitGatewayCard(
                        screen, drawing, gatewayKey, Uuid.random().toString(), owner = open, changes = changes,
                        anchor = placement.anchor(open.state.value),
                        isWaiting = { placement.canShowStandalone(open.state.value) },
                        ownsChat = { ownsChat() && !placement.conflictsWithDirect(open.state.value, screen) },
                    )
                },
            )
        }
        // Preserve compatibility for requests whose session isn't yet known to the currently open chat.
        val chat = chats.of(session)?.let { desktopAgentScreenChat(it.gateway, it.profile, it.storedSessionId) }
            ?: session.takeIf { it.isNotBlank() }
        prepareTranscript(screen, chat, connected)
        if (!screen.wait) return buildJsonObject { put("shown", true) }
        return screens.await(screen, chat, accepts = connected)
    }

    private suspend fun prepareTranscript(screen: AgentScreen, chat: String?, accepts: () -> Boolean) {
        checkedScreen {
            val earlier = screens.earlier(screen, chat) ?: throw PhoneToolException(NOT_DRAWN + UNKNOWN_SURFACE)
            screen.copy(messages = earlier + screen.messages)
        }
        if (!accepts()) throw PhoneToolException(AgentScreens.NOT_SHOWN)
        screens.record(screen, chat)
    }

    private suspend fun checkedScreen(drawing: () -> AgentScreen): AgentScreen {
        val candidate = drawing()
        val problem = withContext(Dispatchers.IO) { agentScreenProblem(context, candidate) }
        if (problem != null) throw PhoneToolException(NOT_DRAWN + problem)
        if (candidate != drawing()) throw PhoneToolException(NOT_DRAWN + "The screen changed while this update was being checked.")
        return candidate
    }

    private companion object {
        const val UNKNOWN_SURFACE = "A message updates a surface this chat doesn't show: start a new screen with " +
            "createSurface, or use the surfaceId of a screen you showed in this chat."
    }
}
