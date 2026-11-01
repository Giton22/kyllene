package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.RpcException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the app knows about a bot's screen. */
data class ScreenState(
    /** The gateway's own snapshot, or null when it has no screen for this bot. */
    val status: DisplayStatus? = null,
    /** The last painted frame, or null before the first one arrives. */
    val frame: RfbFrame? = null,
    /** Who holds the screen: the agent, or a human. */
    val lease: DisplayLease? = null,
    /** The id the gateway minted for this client; the lease is held in its name. */
    val viewerId: String? = null,
    /** True while the stream to the gateway's screen is open. */
    val connected: Boolean = false,
    /** False when the gateway is too old to have a screen at all. */
    val supported: Boolean = true,
    /** Why something didn't work, in the user's words. */
    val error: String? = null,
)

/**
 * A bot's screen, and this app's hold on it.
 *
 * The gateway keeps the screen on its own host, so the app watches it over the display stream and asks
 * the gateway for control when the user takes over. The gateway decides what reaches the screen: it
 * drops the keyboard and pointer of a viewer that does not hold the lease, so this class sends what the
 * user does and lets the gateway answer.
 *
 * A lost link does not hand control back. The human may be half-way through a sign-in, so the gateway
 * keeps them as the holder and this client comes back with the same viewer id to find the lease waiting.
 */
class ScreenSession(
    private val profile: String,
    private val connection: GatewayConnection,
    private val gateway: GatewayUrl,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {

    private val api = DisplayApi(connection)

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private var viewerId: String? = null
    private var stream: RfbTransport? = null
    private var pump: Job? = null
    private var rfb: RfbClient? = null

    init {
        scope.launch {
            connection.events.collect { event ->
                when (event.type) {
                    "display.lease" -> refreshLease()
                    "display.status" -> refresh()
                    else -> Unit
                }
            }
        }
    }

    /** Reads the gateway's state of this bot's screen. */
    suspend fun refresh() {
        val status = ask { api.status(profile) }.getOrElse { return }
        if (status == null) {
            _state.update { it.copy(supported = false, status = null, lease = null) }
            return
        }
        _state.update { it.copy(supported = true, status = status, lease = status.lease, error = null) }
    }

    /** Starts the screen and opens the stream to it. */
    suspend fun start() {
        val status = ask { api.start(profile) }.getOrElse { return }
        if (status == null) {
            _state.update { it.copy(supported = false, error = "This gateway has no Bot Screen.") }
            return
        }
        _state.update { it.copy(supported = true, status = status, lease = status.lease, error = null) }
        open()
    }

    /**
     * Opens the stream to the screen. The gateway mints a ticket for it and a viewer id to go with it;
     * that id is what the lease is held in, so it is kept for the life of the session.
     */
    suspend fun open() {
        if (pump?.isActive == true) return
        val ticket = ask { api.observe(profile, viewerId) }.getOrElse { return }
        if (ticket == null) {
            _state.update { it.copy(error = "The gateway has no Bot Screen.") }
            return
        }
        viewerId = ticket.viewerId
        _state.update { it.copy(viewerId = ticket.viewerId, error = null) }

        val url = gateway.siblingSocketUrl(ticket.path) + "?display_ticket=" + ticket.ticket
        val transport = ask { httpClient.openDisplayStream(url) }.getOrElse { return }
        val rfb = RfbClient(transport)
        stream = transport
        this.rfb = rfb

        pump = scope.launch {
            try {
                rfb.connect()
                _state.update { it.copy(connected = true, error = null) }
                while (currentCoroutineContext().isActive) {
                    val frame = rfb.nextFrame() ?: break
                    _state.update { it.copy(frame = frame) }
                    rfb.requestFrame(incremental = true)
                }
                _state.update { it.copy(connected = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connected = false, error = e.message ?: "The screen stopped.") }
            }
            // The stream ended: the gateway may have given the screen to someone else, so read the lease.
            refresh()
        }
    }

    /** Stops the stream and lets go of the screen. */
    fun close() {
        pump?.cancel()
        pump = null
        val transport = stream
        stream = null
        rfb = null
        scope.launch { runCatching { transport?.close() } }
        _state.update { it.copy(connected = false) }
    }

    /** Takes over: the user drives the bot's screen until they hand back. */
    suspend fun takeOver() {
        val id = viewerId ?: run {
            open()
            viewerId
        } ?: return
        val lease = ask { api.acquire(profile, id) }.getOrElse { return }
        if (lease == null) {
            _state.update { it.copy(error = "The gateway refused the takeover.") }
            return
        }
        _state.update { it.copy(lease = lease, error = null) }
    }

    /** Hands the screen back to the agent. */
    suspend fun handBack() {
        val lease = ask { api.release(profile, viewerId) }.getOrElse { return }
        if (lease == null) {
            _state.update { it.copy(error = "The gateway refused to hand the screen back.") }
            return
        }
        _state.update { it.copy(lease = lease, error = null) }
    }

    /** A key press on the bot's screen. */
    suspend fun pressKey(keysym: Int, down: Boolean) {
        val rfb = rfb ?: return
        send { rfb.keyEvent(keysym, down) }
    }

    /** A pointer move or click on the bot's screen. */
    suspend fun movePointer(x: Int, y: Int, mask: Int) {
        val rfb = rfb ?: return
        send { rfb.pointerEvent(x, y, mask) }
    }

    /** Text into the bot's clipboard. */
    suspend fun copyToClipboard(text: String) {
        val rfb = rfb ?: return
        send { rfb.clientCutText(text) }
    }

    private suspend fun refreshLease() {
        val status = ask { api.status(profile) }.getOrElse { return } ?: return
        _state.update { it.copy(lease = status.lease, status = status) }
    }

    /**
     * Asks the gateway something, and keeps the answer.
     *
     * The link to the gateway drops now and then — the phone is on Wi-Fi, and the gateway may restart — and a
     * call that never got there says nothing about the screen. The pane keeps what it last knew and says why
     * it stopped; the app does not fall over.
     */
    private suspend fun <T> ask(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(error = failureMessage(e)) }
        Result.failure(e)
    }

    /** What the pane says when a call to the gateway didn't work. */
    private fun failureMessage(e: Throwable): String = when (e) {
        // The call never got there: the link is down, not the screen.
        is GatewayUnreachable -> e.message
        // The gateway's own words: it answered, and it said no.
        is RpcException -> e.message
        else -> e.message ?: "The gateway didn't answer."
    }

    /** Sends something to the screen, and lets it go if the stream has gone. */
    private suspend fun send(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The stream is gone; the pump that reads it is the one that says so.
        }
    }
}
