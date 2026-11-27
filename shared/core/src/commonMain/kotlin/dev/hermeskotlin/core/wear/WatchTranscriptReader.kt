package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.wearlink.HistoryMessage
import dev.hermeskotlin.wearlink.HistoryPage
import dev.hermeskotlin.wearlink.HistoryRequest
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.clipForWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Reads stored history without opening a runtime session or creating a bot conversation. */
class WatchTranscriptReader(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val profiles: ProfileStore,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val bots: BotsApi,
) {
    suspend fun read(request: HistoryRequest): HistoryPage {
        fun failed(message: String) = HistoryPage(request, error = message)
        val saved = gateways.current() ?: return failed("Sign in to Kyllene on your phone first.")
        val gateway = saved.gatewayUrl
        val signIn = "Sign in to Kyllene on your phone first."
        if (!auth.hasStoredSession(gateway) || connection.state.value is ConnectionState.SessionExpired) return failed(signIn)
        suspend fun current(): Boolean = gateways.current()?.gatewayUrl == gateway &&
            WatchScope(gateway.value, profiles.get(gateway)) == request.browserScope &&
            auth.hasStoredSession(gateway) && connection.state.value !is ConnectionState.SessionExpired
        if (request.scope.gateway != gateway.value || request.browserScope.gateway != gateway.value ||
            request.botProfile != null && request.scope.profile != request.botProfile || request.offset < 0 || !current()) {
            return failed("Conversation changed. Choose it again on your watch.")
        }
        var connecting = false
        return try {
            withTimeoutOrNull(25_000) {
                val stored = if (request.botProfile != null) {
                    connecting = true
                    connection.start(gateway)
                    val ready = connection.state.first {
                        it is ConnectionState.Connected || it is ConnectionState.SessionExpired || it is ConnectionState.Failed
                    }
                    connecting = false
                    when (ready) {
                        is ConnectionState.SessionExpired -> return@withTimeoutOrNull failed(signIn)
                        is ConnectionState.Failed -> return@withTimeoutOrNull failed("Kyllene can't reach your gateway right now.")
                        else -> Unit
                    }
                    if (!current()) return@withTimeoutOrNull failed("Phone profile changed. Choose the conversation again.")
                    val bot = bots.roster().bots.firstOrNull { it.name == request.botProfile }
                        ?: return@withTimeoutOrNull failed("This bot is no longer available.")
                    bot.canonicalSession?.openId ?: return@withTimeoutOrNull failed("This bot has no saved conversation yet.")
                } else request.chatId?.takeIf { it.isNotBlank() }
                    ?: return@withTimeoutOrNull failed("Choose a saved conversation first.")
                if (!current()) return@withTimeoutOrNull failed("Phone profile changed. Choose the conversation again.")
                val result = sessions.messages(gateway, stored, limit = 20, profile = request.scope.profile, offset = request.offset)
                if (!current()) return@withTimeoutOrNull failed("Phone profile changed. Choose the conversation again.")
                if (result !is ApiResult.Success) return@withTimeoutOrNull failed(result.errorMessage ?: "Couldn't read the conversation.")
                val page = result.value
                val rows = page.messages.mapIndexedNotNull { index, row ->
                    if (row.isHidden || row.role !in listOf("user", "assistant") || row.text.isBlank()) null
                    else {
                        // A timestamped legacy row can recur at a shifted offset after a new turn arrives.
                        val legacy = row.timestamp?.let { "legacy:$it:${row.role}:${row.text.hashCode()}" }
                            ?: "legacy:${request.offset + index}"
                        HistoryMessage("${row.sessionId ?: page.sessionId}:${row.id?.toString() ?: legacy}",
                            row.role, clipForWatch(row.text))
                    }
                }
                // Gateway offsets count every raw row, including the hidden/tool rows omitted above.
                HistoryPage(request, page.sessionId, rows,
                    (request.offset + page.messages.size).takeIf { page.messages.size >= 20 })
            } ?: if (connecting && current()) {
                connection.retry()
                failed("Kyllene can't reach your gateway right now.")
            } else failed("The gateway didn't answer. Try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: "Couldn't read the conversation. Try again.")
        }
    }
}
