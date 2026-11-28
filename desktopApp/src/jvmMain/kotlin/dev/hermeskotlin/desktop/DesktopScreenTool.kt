package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.NOT_DRAWN
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.phone.AgentScreens
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.PhoneToolRunner
import dev.hermeskotlin.core.phone.runScreenPlacement
import dev.hermeskotlin.core.phone.desktopAgentScreenChat
import dev.hermeskotlin.core.phone.gatewayAgentScreenChat
import dev.hermeskotlin.core.phone.screenRequestChanges
import dev.hermeskotlin.core.rpc.JsonRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Runs the screen capability for a desktop chat while its originating connection is open. */
internal class DesktopScreenTool(
    private val mainHost: ChatHost,
    private val connection: GatewayConnection,
    private val screens: AgentScreens,
) : PhoneToolRunner {
    private val windows = MutableStateFlow<Set<ChatHost>>(emptySet())
    private val claimed = LinkedHashSet<Pair<String, String>>()

    fun open(host: ChatHost) = windows.update { it + host }

    fun close(host: ChatHost) = windows.update { it - host }

    private fun ChatSession.storedIdFor(session: String): String? {
        val state = state.value
        return state.storedSessionId?.takeIf { session == it || session == state.runtimeSessionId }
    }

    override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement =
        throw PhoneToolException(NOT_SHOWN)

    override suspend fun run(tool: PhoneTool, args: JsonObject, session: String, origin: JsonRpcClient?): JsonElement =
        withContext(Dispatchers.Main.immediate) { show(tool, args, session, origin) }

    private suspend fun show(tool: PhoneTool, args: JsonObject, session: String, origin: JsonRpcClient?): JsonElement {
        if (tool != PhoneTool.ShowUi || origin == null) throw PhoneToolException(NOT_SHOWN)
        val (host, chat) = (windows.value + mainHost).firstNotNullOfOrNull { candidate ->
            candidate.session.value?.takeIf {
                session.isNotBlank() && it.storedIdFor(session) != null && connection.isConnectedTo(it.gateway.value)
            }?.let { candidate to it }
        } ?: throw PhoneToolException(NOT_SHOWN)
        val stored = chat.storedIdFor(session) ?: throw PhoneToolException(NOT_SHOWN)
        val owner = chat.ownerVersion
        val accepts = {
            host.ownerVersion.value == owner && host.session.value === chat &&
                (connection.state.value as? ConnectionState.Connected)?.client === origin &&
                connection.isConnectedTo(chat.gateway.value)
        }
        if (!accepts()) throw PhoneToolException(NOT_SHOWN)

        val screen = AgentScreen.from(SHOW_UI_TOOL, args)
            ?: throw PhoneToolException(NOT_DRAWN + "The screen has invalid A2UI messages.")
        val transcriptKey = desktopAgentScreenChat(chat.gateway.value, chat.profile, stored)
        val gatewayKey = gatewayAgentScreenChat(chat.gateway.value, chat.profile, stored)
        // The plugin generates its own request UUID. The transcript carries the model's tool-call ID.
        // Match a unique, still-running call by its arguments before letting the browser act on it.
        return runScreenPlacement(
            chat.state, screen, accepts, NOT_SHOWN, claimed = { transcriptKey to it in claimed },
            preferStandalone = screens.ownsGatewaySurface(screen, gatewayKey, chat),
            direct = { callId ->
                claimed += transcriptKey to callId
                if (claimed.size > RECENT_CLAIMS) claimed.remove(claimed.first())
                val earlier = screens.earlier(screen, transcriptKey)
                    ?: throw PhoneToolException(NOT_DRAWN + "The screen to update hasn't been shown in this chat.")
                screen.copy(messages = earlier + screen.messages).layoutProblem?.let {
                    throw PhoneToolException(NOT_DRAWN + it)
                }
                screens.await(screen, transcriptKey, NOT_SHOWN, callId, accepts = accepts)
            },
            standalone = { placement ->
                val drawing = screens.gatewayDrawing(screen, gatewayKey, chat)
                drawing.layoutProblem?.let { throw PhoneToolException(NOT_DRAWN + it) }
                screens.awaitGatewayCard(
                    screen, drawing, gatewayKey, Uuid.random().toString(), owner = chat, notShown = NOT_SHOWN,
                    anchor = placement.anchor(chat.state.value),
                    changes = screenRequestChanges(host, chat, connection),
                    isWaiting = { placement.canShowStandalone(chat.state.value) },
                    ownsChat = { accepts() && !placement.conflictsWithDirect(chat.state.value, screen) },
                )
            },
        )
    }
}

private const val RECENT_CLAIMS = 128
private const val NOT_SHOWN = NOT_DRAWN + "Kyllene did not show the screen in the open desktop chat. Ask in words instead."
