package dev.hermeskotlin.core.connection

/**
 * Holds a foreground service up for as long as something [wanted] it, without stopping it before it reached the
 * foreground: Android treats a service started with startForegroundService and stopped before it calls
 * startForeground as a crash (ForegroundServiceDidNotStartInTimeException). One released while it is still starting
 * stops itself once it is in the foreground ([started] returns false). All on one thread, as the service's callbacks
 * and its callers are.
 */
class ForegroundHold(
    /** Asks for the service; false when Android refused. */
    private val startService: () -> Boolean,
    private val stopService: () -> Unit,
) {
    /** Something holds it: it was asked for and not released. */
    var wanted = false
        private set

    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false

    /** Holds it, asking for the service unless it's already held. False when Android refused to start it. */
    fun hold(): Boolean {
        if (wanted) return true
        wanted = startService()
        return wanted
    }

    fun release() {
        wanted = false
        // One still starting stops itself once it's in the foreground (started). One stopped is gone: a new one
        // asked for before its onDestroy isn't in the foreground until its own started.
        if (foreground) {
            foreground = false
            stopService()
        }
    }

    /** The service is in the foreground; false when it was released meanwhile, so it should stop itself. */
    fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    /** The service couldn't go into the foreground after all: the next [hold] asks again. */
    fun lost() {
        wanted = false
        foreground = false
    }

    /** The service is gone. */
    fun stopped() {
        foreground = false
    }
}
