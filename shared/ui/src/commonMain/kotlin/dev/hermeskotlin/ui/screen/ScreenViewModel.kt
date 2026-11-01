package dev.hermeskotlin.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.RfbFrame
import dev.hermeskotlin.core.screen.ScreenSession
import dev.hermeskotlin.core.screen.ScreenState
import dev.hermeskotlin.core.screen.keysymForCharacter
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the pane shows: the gateway's own snapshot of a bot's screen, and this app's hold on it. */
data class ScreenUiState(
    /** The gateway's snapshot of the screen, or null before it answers. */
    val status: DisplayStatus? = null,
    /** The last picture the gateway sent, or null before the first one arrives. */
    val frame: RfbFrame? = null,
    /** Who holds the screen, as the gateway last said. */
    val lease: DisplayLease? = null,
    /** True while the stream to the screen is open. */
    val connected: Boolean = false,
    /** Whether the stream has ever been open: a screen that was never reached isn't a dropped one. */
    val everConnected: Boolean = false,
    /** False when the gateway is too old to have a screen at all. */
    val supported: Boolean = true,
    /** Why something didn't work, in the user's words. */
    val error: String? = null,
    /** Whether this app holds the screen, rather than the agent or another viewer. */
    val holding: Boolean = false,
)

/**
 * A bot's screen, as this app watches it.
 *
 * The view model owns the [ScreenSession] and nothing else. The gateway decides what reaches the screen, so
 * the app sends what the user does and lets the gateway drop it when the agent holds the screen; the app
 * only says who holds it. The session is made for the gateway and the profile in hand and put away when
 * either changes, so an answer about the last bot never shows for this one.
 */
class ScreenViewModel(
    private val connection: GatewayConnection,
    private val httpClient: HttpClient,
) : ViewModel() {

    private val sessions = MutableStateFlow<ScreenSession?>(null)
    private var boundGateway: SavedGateway? = null
    private var boundProfile: String? = null
    private var core = ScreenState()

    /**
     * The lease this app took: the gateway's hash of its viewer, so the app can tell its own lease from
     * another viewer's. Empty when the gateway doesn't say whose human it is.
     */
    private var myHash: String? = null

    /** Whether the stream has ever been open, so a screen that was never reached isn't a dropped one. */
    private var everConnected = false

    private val _state = MutableStateFlow(ScreenUiState())
    val state: StateFlow<ScreenUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.collectLatest { session ->
                session?.state?.collect { core = it; publish() }
            }
        }
    }

    /**
     * Points the pane at a bot's screen on [gateway], and watches it.
     *
     * A screen that is already running is watched at once; one that is stopped is left alone until the user
     * asks for it.
     */
    fun bind(gateway: SavedGateway, profile: String) {
        if (boundGateway != gateway || boundProfile != profile) {
            sessions.value?.close()
            sessions.value = null
            myHash = null
            everConnected = false
            core = ScreenState()
            boundGateway = gateway
            boundProfile = profile
            publish()
        }
        val session = sessions.value ?: newSession(gateway, profile) ?: return
        viewModelScope.launch {
            session.refresh()
            val state = session.state.value
            if (state.status?.running == true && !state.connected) session.open()
        }
    }

    /** Puts the stream away when the pane goes. The session stays, so coming back reopens it. */
    fun close() {
        sessions.value?.close()
    }

    /** Starts the bot's screen on the gateway and opens the stream to it. */
    fun start() {
        viewModelScope.launch { sessions.value?.start() }
    }

    /** Opens the stream to a screen that is already running. */
    fun reopen() {
        viewModelScope.launch { sessions.value?.open() }
    }

    /** Asks the gateway again about the screen, e.g. after the user installed it on the host. */
    fun refresh() {
        viewModelScope.launch { sessions.value?.refresh() }
    }

    /** Takes the screen over from the agent, so the user's taps and keys reach it. */
    fun takeOver() {
        val session = sessions.value ?: return
        viewModelScope.launch {
            session.takeOver()
            // The gateway answered with the lease; a refusal leaves an error in its place.
            if (session.state.value.error == null) {
                myHash = session.state.value.lease?.viewerHash ?: ""
                publish()
            }
        }
    }

    /** Gives the screen back to the agent. */
    fun handBack() {
        val session = sessions.value ?: return
        viewModelScope.launch {
            session.handBack()
            myHash = null
            publish()
        }
    }

    /** A pointer on the bot's screen: a move with [mask], or a click when the mask is 1 then 0. */
    fun pointer(x: Int, y: Int, mask: Int) {
        viewModelScope.launch { sessions.value?.movePointer(x, y, mask) }
    }

    /** A key on the bot's screen: pressed and released, which is what a keystroke is. */
    fun key(keysym: Int) {
        val session = sessions.value ?: return
        viewModelScope.launch {
            session.pressKey(keysym, true)
            session.pressKey(keysym, false)
        }
    }

    /** A character typed on the bot's screen. Characters the keysyms don't cover are dropped. */
    fun character(ch: Char) {
        val keysym = keysymForCharacter(ch)
        if (keysym != 0) key(keysym)
    }

    private fun newSession(gateway: SavedGateway, profile: String): ScreenSession? {
        val url = runCatching { gateway.gatewayUrl }.getOrNull() ?: run {
            core = core.copy(error = "This gateway's address isn't one the app can read.")
            publish()
            return null
        }
        val session = ScreenSession(profile, connection, url, httpClient, viewModelScope)
        sessions.value = session
        return session
    }

    private fun publish() {
        if (core.connected) everConnected = true
        _state.value = ScreenUiState(
            status = core.status,
            frame = core.frame,
            lease = core.lease,
            connected = core.connected,
            everConnected = everConnected,
            supported = core.supported,
            error = core.error,
            holding = holdsTheScreen(core.lease, myHash),
        )
    }

    override fun onCleared() {
        sessions.value?.close()
        sessions.value = null
        super.onCleared()
    }
}

/**
 * Whether this app is the one holding the screen.
 *
 * The gateway says a human holds it, but not always which human: [myHash] is the hash of the lease this app
 * took, and an empty one means the gateway didn't say, so the app goes on holding it.
 */
internal fun holdsTheScreen(lease: DisplayLease?, myHash: String?): Boolean = when {
    lease == null -> false
    lease.heldByAgent -> false
    myHash == null -> false
    myHash.isEmpty() -> true
    lease.viewerHash == null -> true
    else -> lease.viewerHash == myHash
}
