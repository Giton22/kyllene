package dev.hermeskotlin.core.phone

/**
 * Adds up screen time from the phone's usage events, oldest first, the way the phone's own screen time counts: an
 * app is on screen from when one of its activities shows until the last one goes or the screen turns off. Picture in
 * picture is a paused activity, so it isn't counted, as there.
 *
 * [starts] are each day's start and then the next day's (a day can be 23 or 25 hours); only time from the first is
 * counted, though events can begin earlier, to know what was on screen then. [skip] are apps never counted (the home
 * screen). [oneAtATime]: before Android 10 a single activity is on screen, so one showing means the rest went.
 */
class ScreenTimeCounter(private val starts: LongArray, private val skip: Set<String>, private val oneAtATime: Boolean) {
    private val from = starts.first()

    /** Milliseconds on screen per app. Two apps side by side each count in full. */
    val apps = mutableMapOf<String, Long>()

    /** Milliseconds in any app per day: two apps side by side count once. */
    val byDay = LongArray(starts.size - 1)
    var screenOn = 0L
        private set
    var unlocks = 0
        private set

    /** Whether the phone has a screen lock: it showed or hid one in the events. */
    var locks = false
        private set

    /**
     * The activities of each app on screen now, by class, and since when the app is. Two copies of one class side by
     * side count as one, so the app goes when the first does: Android's events don't tell copies apart.
     */
    private val showing = mutableMapOf<String, MutableSet<String>>()
    private val since = mutableMapOf<String, Long>()

    /**
     * Pauses not yet followed by their stop, per app and class. A stop that has one is that paused copy going (maybe
     * after a new copy of the class showed over it); one without was never paused: killed on screen, and gone.
     */
    private val toStop = mutableMapOf<String, Int>()

    /** What was on screen when it last turned off, and hasn't gone since: back if the screen comes straight on. */
    private val dimmed = mutableMapOf<String, MutableSet<String>>()
    private var offAt: Long? = null
    private val seen = mutableSetOf<String>()
    private var screenSince: Long? = null
    private var screenSeen = false
    private var last = 0L

    /** Since when any app is on screen, and until when that's counted in [byDay]. */
    private var anySince: Long? = null
    private var anyCounted = 0L

    /** One event: its type (Android's UsageEvents.Event values), app, activity class and time. */
    fun add(type: Int, app: String, activity: String, at: Long) {
        when (type) {
            ACTIVITY_RESUMED -> {
                // Another showing means the rest went, even when an event for one is missing (a restart on 8 and 9,
                // which don't log one).
                if (oneAtATime) for (other in showing.keys.toList()) if (other != app) close(other, at)
                // Something new on screen: what the screen turning off hid isn't coming back.
                if (oneAtATime || offAt != null) dimmed.clear() else dimmed.remove(app)
                val open = showing.getOrPut(app) { mutableSetOf() }
                if (open.isEmpty()) opened(app, at)
                open += activity
                seen += app
            }
            ACTIVITY_PAUSED -> {
                val key = "$app/$activity"
                toStop[key] = (toStop[key] ?: 0) + 1
                gone(app, activity, at)
            }
            ACTIVITY_STOPPED -> {
                val key = "$app/$activity"
                val paused = toStop[key] ?: 0
                if (paused > 0) {
                    if (paused == 1) toStop.remove(key) else toStop[key] = paused - 1
                    goneBeforeEvents(app, showing[app], at)
                    seen += app
                } else {
                    gone(app, activity, at)
                }
            }
            SCREEN_INTERACTIVE -> {
                // On again without going off: the phone crashed and started again, and nothing logged that. What was
                // on went at the last event before.
                if (screenSince != null) off(last)
                screenSeen = true
                screenSince = at
                // Back on at once (a quick press of the power button), before they were paused: still on screen.
                val wasOff = offAt
                if (wasOff != null && at - wasOff <= QUICK_MS) {
                    for ((other, open) in dimmed) {
                        showing[other] = open
                        opened(other, at)
                    }
                }
                dimmed.clear()
                offAt = null
            }
            SCREEN_NON_INTERACTIVE -> {
                screenOn += since(screenSince ?: if (screenSeen) at else 0L, at)
                screenSeen = true
                screenSince = null
                // Nothing is on screen with the screen off, whenever its own events say so.
                dimmed.clear()
                showing.forEach { (other, open) -> dimmed[other] = open.toMutableSet() }
                offAt = at
                closeAll(at)
            }
            KEYGUARD_SHOWN -> locks = true
            KEYGUARD_HIDDEN -> {
                locks = true
                if (at >= from) unlocks++
            }
            DEVICE_SHUTDOWN -> off(at)
            // Logged at the first unlock, after what the phone did as it started (the screen on, the lock screen).
            DEVICE_STARTUP -> if (screenSince == null) {
                screenSeen = true
                screenSince = at
            }
        }
        last = maxOf(last, at)
    }

    /** [interactive]: whether the screen is on now, for a screen that didn't turn on or off in all the events. */
    fun end(now: Long, interactive: Boolean) {
        closeAll(now)
        val on = screenSince ?: if (!screenSeen && interactive) 0L else null
        on?.let { screenOn += since(it, now) }
        screenSince = null
    }

    /** [activity] of [app] is off the screen at [at]. */
    private fun gone(app: String, activity: String, at: Long) {
        dimmed[app]?.let { if (it.remove(activity) && it.isEmpty()) dimmed.remove(app) }
        val open = showing[app]
        if (open != null && open.remove(activity) && open.isEmpty()) close(app, at) else goneBeforeEvents(app, open, at)
        seen += app
    }

    /** [app]'s first activity event, and it goes: it was on screen since before the events begin. */
    private fun goneBeforeEvents(app: String, open: Set<String>?, at: Long) {
        if (app in seen || open != null || app in skip) return
        count(app, 0L, at)
        countAny(0L, at)
    }

    /** The phone went off at [at]: nothing on screen, and the screen off, until it starts again. */
    private fun off(at: Long) {
        screenSince?.let { screenOn += since(it, at) }
        screenSince = null
        dimmed.clear()
        offAt = null
        toStop.clear()
        closeAll(at)
    }

    private fun opened(app: String, at: Long) {
        since[app] = at
        if (app !in skip && anySince == null) anySince = at
    }

    private fun close(app: String, at: Long) {
        since.remove(app)?.let { count(app, it, at) }
        showing.remove(app)
        val any = anySince
        if (any != null && showing.keys.none { it !in skip }) {
            countAny(any, at)
            anySince = null
        }
    }

    private fun closeAll(at: Long) {
        for (app in showing.keys.toList()) close(app, at)
    }

    /** The part of [start] to [end] from the start of the range. */
    private fun since(start: Long, end: Long) = (end - maxOf(start, from)).coerceAtLeast(0L)

    private fun count(app: String, start: Long, end: Long) {
        if (app in skip || since(start, end) == 0L) return
        apps[app] = (apps[app] ?: 0L) + since(start, end)
    }

    /** Adds [start] to [end] to the days, but only the part not counted yet: time in two apps counts once. */
    private fun countAny(start: Long, end: Long) {
        val s = maxOf(start, anyCounted, from)
        if (end <= s) return
        anyCounted = end
        for (i in byDay.indices) {
            val overlap = minOf(end, starts[i + 1]) - maxOf(s, starts[i])
            if (overlap > 0) byDay[i] += overlap
        }
    }

    companion object {
        /** A screen back on within this of going off was a quick press: what was on it still is. */
        private const val QUICK_MS = 10_000L

        // Android's UsageEvents.Event types by value: some constants came in later versions than the events.
        const val ACTIVITY_RESUMED = 1
        const val ACTIVITY_PAUSED = 2
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16
        const val KEYGUARD_SHOWN = 17
        const val KEYGUARD_HIDDEN = 18
        const val ACTIVITY_STOPPED = 23
        const val DEVICE_SHUTDOWN = 26
        const val DEVICE_STARTUP = 27
    }
}
