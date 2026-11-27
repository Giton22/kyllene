package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.desktop.resources.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal data class StartupGrant(val background: Boolean, val autostart: Boolean)
internal enum class StartupChoice(val hidden: Boolean = false) { Off, Window, Background(true) }
internal enum class StartupMessage(val text: StringResource) {
    NotApproved(Res.string.startup_not_approved),
    NotApplied(Res.string.startup_not_applied),
    ShowsWindow(Res.string.startup_shows_window),
    NotSaved(Res.string.startup_not_saved),
    Incomplete(Res.string.startup_incomplete),
    NoAnswer(Res.string.startup_no_answer),
}
internal data class StartupState(val choice: StartupChoice, val busy: Boolean = false, val message: StartupMessage? = null)

/** The desktop owns autostart; this stores only its last confirmed answer, never a pending wish. */
internal class BackgroundStartup private constructor(
    private val store: KeyValueStore,
    private val request: suspend (Boolean) -> StartupGrant?,
    initial: StartupChoice,
) {
    private val mutable = MutableStateFlow(StartupState(initial))
    val state = mutable.asStateFlow()
    private val pending = Mutex()

    suspend fun set(enabled: Boolean) {
        if (!pending.tryLock()) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        try {
            val result = request(enabled)
            if (result == null) {
                mutable.value = mutable.value.copy(message = StartupMessage.NotApproved)
                return
            }
            val choice = when {
                !result.autostart -> StartupChoice.Off
                result.background -> StartupChoice.Background
                else -> StartupChoice.Window
            }
            mutable.value = StartupState(choice, busy = true, message = when {
                enabled != result.autostart -> StartupMessage.NotApplied
                choice == StartupChoice.Window -> StartupMessage.ShowsWindow
                else -> null
            })
            try {
                // The desktop has already changed its entry, so a Quit now mustn't leave the old answer stored.
                withContext(NonCancellable + Dispatchers.IO) { store.put(KEY, choice.name) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = StartupMessage.NotSaved)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StartupNoAnswer) {
            mutable.value = mutable.value.copy(message = StartupMessage.NoAnswer)
        } catch (_: StartupNotApplied) {
            mutable.value = mutable.value.copy(message = StartupMessage.NotApplied)
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(message = StartupMessage.Incomplete)
        } finally {
            mutable.value = mutable.value.copy(busy = false)
            pending.unlock()
        }
    }

    companion object {
        private const val KEY = "desktop.startAtLogin"
        suspend fun load(store: KeyValueStore, request: suspend (Boolean) -> StartupGrant?): BackgroundStartup {
            val initial = try {
                withContext(Dispatchers.IO) { store.get(KEY) }?.let { value -> StartupChoice.entries.find { it.name == value } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            return BackgroundStartup(store, request, initial ?: StartupChoice.Off)
        }
    }
}

/** Waits between asks: at login the portal can take a while to start, and a later launch only reaches this one. */
internal val PORTAL_RETRIES = listOf(0L, 5_000L, 15_000L, 30_000L, 60_000L)

internal suspend fun awaitPortal(available: suspend () -> Boolean, waits: List<Long> = PORTAL_RETRIES): Boolean {
    for (wait in waits) {
        delay(wait)
        if (available()) return true
    }
    return false
}

internal fun backgroundLaunch(args: Array<String>, choice: StartupChoice, tray: Boolean): Boolean =
    args.contentEquals(arrayOf("--background")) && choice.hidden && tray

internal fun backgroundInvocation(args: Array<String>, flatpak: Boolean): Boolean =
    flatpak && args.contentEquals(arrayOf("--background"))
