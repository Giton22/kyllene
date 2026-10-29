package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.ACTIVITY_PAUSED
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.ACTIVITY_RESUMED
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.ACTIVITY_STOPPED
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.DEVICE_SHUTDOWN
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.DEVICE_STARTUP
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.KEYGUARD_HIDDEN
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.KEYGUARD_SHOWN
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.SCREEN_INTERACTIVE
import dev.hermeskotlin.core.phone.ScreenTimeCounter.Companion.SCREEN_NON_INTERACTIVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTimeCounterTest {

    private fun min(m: Int) = m * 60_000L

    /** One day from minute 0 to 1440, or [days] of them; events can come before minute 0. */
    private fun counter(days: Int = 1, skip: Set<String> = emptySet(), oneAtATime: Boolean = false) =
        ScreenTimeCounter(LongArray(days + 1) { min(it * 1440) }, skip, oneAtATime)

    private fun ScreenTimeCounter.at(m: Int, type: Int, app: String = "android", activity: String = "") =
        add(type, app, activity, min(m))

    private fun ScreenTimeCounter.minutes(app: String) = apps[app]?.let { (it / 60_000).toInt() }

    @Test
    fun anAppCountsFromShownToGone() {
        val c = counter()
        c.at(10, ACTIVITY_RESUMED, "maps", "Main")
        c.at(25, ACTIVITY_PAUSED, "maps", "Main")
        c.at(26, ACTIVITY_STOPPED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(15, c.minutes("maps"))
        assertEquals(min(15), c.byDay[0])
    }

    @Test
    fun anAppOnSinceBeforeTheRangeCountsFromItsStart() {
        val c = counter()
        c.at(-30, ACTIVITY_RESUMED, "maps", "Main")
        c.at(20, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(20, c.minutes("maps"))
    }

    @Test
    fun anAppWhoseFirstEventIsGoingWasOnSinceTheStart() {
        val c = counter()
        c.at(20, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(20, c.minutes("maps"))
        assertEquals(min(20), c.byDay[0])
    }

    @Test
    fun anAppStillShowingCountsUntilNow() {
        val c = counter()
        c.at(50, ACTIVITY_RESUMED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(10, c.minutes("maps"))
    }

    @Test
    fun anotherActivityOfTheSameAppKeepsItOnScreen() {
        val c = counter()
        // Android's order: the old one pauses, the new one shows, then the old one stops.
        c.at(0, ACTIVITY_RESUMED, "mail", "Inbox")
        c.at(10, ACTIVITY_PAUSED, "mail", "Inbox")
        c.at(10, ACTIVITY_RESUMED, "mail", "Compose")
        c.at(11, ACTIVITY_STOPPED, "mail", "Inbox")
        c.at(30, ACTIVITY_PAUSED, "mail", "Compose")
        c.end(min(60), interactive = true)
        assertEquals(30, c.minutes("mail"))
    }

    @Test
    fun anActivityOpenedOverACopyOfItselfStaysWhenTheOldCopyStops() {
        val c = counter()
        c.at(0, ACTIVITY_RESUMED, "chat", "Main")
        c.at(10, ACTIVITY_PAUSED, "chat", "Main")
        c.at(10, ACTIVITY_RESUMED, "chat", "Main")
        c.at(11, ACTIVITY_STOPPED, "chat", "Main")
        c.at(30, ACTIVITY_PAUSED, "chat", "Main")
        c.end(min(60), interactive = true)
        assertEquals(30, c.minutes("chat"))
        assertEquals(min(30), c.byDay[0])
    }

    @Test
    fun anAppKilledOnScreenGoesWhenItStops() {
        val c = counter()
        // Reinstalled while showing: no pause, and it showing again is the same activity, not a second copy.
        c.at(0, ACTIVITY_RESUMED, "chat", "Main")
        c.at(5, ACTIVITY_RESUMED, "chat", "Main")
        c.at(10, ACTIVITY_STOPPED, "chat", "Main")
        c.at(20, ACTIVITY_RESUMED, "chat", "Main")
        c.at(30, ACTIVITY_PAUSED, "chat", "Main")
        c.end(min(60), interactive = true)
        assertEquals(20, c.minutes("chat"))
    }

    @Test
    fun theScreenTurningOffEndsWhatsOnIt() {
        val c = counter()
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        c.at(10, SCREEN_NON_INTERACTIVE)
        c.at(11, ACTIVITY_PAUSED, "maps", "Main")
        c.at(40, SCREEN_INTERACTIVE)
        c.at(41, ACTIVITY_RESUMED, "maps", "Main")
        c.at(50, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(19, c.minutes("maps"))
        // On from the start to 10, and from 40 to now.
        assertEquals(min(30), c.screenOn)
    }

    @Test
    fun aQuickPressOfThePowerButtonKeepsTheAppOnScreen() {
        val c = counter()
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        c.at(10, SCREEN_NON_INTERACTIVE)
        c.add(SCREEN_INTERACTIVE, "android", "", min(10) + 5_000)
        c.at(30, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(29, c.minutes("maps"))
    }

    @Test
    fun aScreenBackOnLaterDoesntBringBackWhatWasOnIt() {
        val c = counter()
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        // Its pause went missing.
        c.at(10, SCREEN_NON_INTERACTIVE)
        c.at(40, SCREEN_INTERACTIVE)
        c.at(50, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertEquals(10, c.minutes("maps"))
    }

    @Test
    fun anAppShownWhileTheScreenWasOffDropsWhatWasOnBefore() {
        val c = counter(oneAtATime = true)
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        c.at(10, SCREEN_NON_INTERACTIVE)
        c.add(ACTIVITY_RESUMED, "call", "Incoming", min(10) + 2_000)
        c.add(SCREEN_INTERACTIVE, "android", "", min(10) + 4_000)
        c.at(30, ACTIVITY_PAUSED, "call", "Incoming")
        c.end(min(60), interactive = true)
        assertEquals(10, c.minutes("maps"))
    }

    @Test
    fun appsSideBySideCountInFullEachButOnceInTheDay() {
        val c = counter()
        c.at(0, ACTIVITY_RESUMED, "video", "Player")
        c.at(10, ACTIVITY_RESUMED, "chat", "Main")
        c.at(30, ACTIVITY_PAUSED, "video", "Player")
        c.at(40, ACTIVITY_PAUSED, "chat", "Main")
        c.end(min(60), interactive = true)
        assertEquals(30, c.minutes("video"))
        assertEquals(30, c.minutes("chat"))
        assertEquals(min(40), c.byDay[0])
    }

    @Test
    fun beforeAndroid10AnotherAppShowingEndsTheLast() {
        val c = counter(oneAtATime = true)
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        // Its pause was lost in a restart.
        c.at(20, ACTIVITY_RESUMED, "chat", "Main")
        c.at(30, ACTIVITY_PAUSED, "chat", "Main")
        c.end(min(60), interactive = true)
        assertEquals(20, c.minutes("maps"))
        assertEquals(10, c.minutes("chat"))
    }

    @Test
    fun theHomeScreenIsntCounted() {
        val c = counter(skip = setOf("launcher"))
        c.at(0, ACTIVITY_RESUMED, "launcher", "Home")
        c.at(10, ACTIVITY_PAUSED, "launcher", "Home")
        c.at(10, ACTIVITY_RESUMED, "maps", "Main")
        c.at(20, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(60), interactive = true)
        assertNull(c.minutes("launcher"))
        assertEquals(min(10), c.byDay[0])
    }

    @Test
    fun theHomeScreenOnSinceBeforeTheEventsIsntCountedEither() {
        val c = counter(skip = setOf("launcher"))
        c.at(20, ACTIVITY_PAUSED, "launcher", "Home")
        c.end(min(60), interactive = true)
        assertNull(c.minutes("launcher"))
        assertEquals(0L, c.byDay[0])
    }

    @Test
    fun aRestartEndsTheScreenAndTheAppsAndCountsTheLockScreenAfter() {
        val c = counter()
        c.at(-60, SCREEN_INTERACTIVE)
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        c.at(10, DEVICE_SHUTDOWN)
        // Off for an hour, then Android's order: the screen on, the lock screen, and the startup at the first unlock.
        c.at(70, SCREEN_INTERACTIVE)
        c.at(70, KEYGUARD_SHOWN)
        c.at(71, ACTIVITY_RESUMED, "camera", "Main")
        c.at(73, KEYGUARD_HIDDEN)
        c.at(73, DEVICE_STARTUP)
        c.at(75, ACTIVITY_PAUSED, "camera", "Main")
        c.at(80, SCREEN_NON_INTERACTIVE)
        c.end(min(120), interactive = false)
        assertEquals(10, c.minutes("maps"))
        assertEquals(4, c.minutes("camera"))
        assertEquals(min(20), c.screenOn)
    }

    @Test
    fun afterACrashWhatWasOnEndsAtTheLastEvent() {
        val c = counter()
        c.at(-60, SCREEN_INTERACTIVE)
        c.at(0, ACTIVITY_RESUMED, "maps", "Main")
        c.at(5, KEYGUARD_SHOWN)
        // A crash: no shutdown logged, then the screen comes on as the phone starts.
        c.at(70, SCREEN_INTERACTIVE)
        c.at(72, DEVICE_STARTUP)
        c.end(min(80), interactive = true)
        assertEquals(5, c.minutes("maps"))
        // To the crash, then from the start again.
        assertEquals(min(15), c.screenOn)
    }

    @Test
    fun aScreenThatDidntChangeIsOnOrOffAsNow() {
        val on = counter().apply { end(min(60), interactive = true) }
        assertEquals(min(60), on.screenOn)
        val off = counter().apply { end(min(60), interactive = false) }
        assertEquals(0L, off.screenOn)
    }

    @Test
    fun theScreenOnSinceBeforeTheRangeCountsFromItsStart() {
        val c = counter()
        c.at(-120, SCREEN_INTERACTIVE)
        c.at(30, SCREEN_NON_INTERACTIVE)
        c.end(min(60), interactive = false)
        assertEquals(min(30), c.screenOn)
    }

    @Test
    fun unlocksCountFromTheStartAndOnlyWithAScreenLock() {
        val c = counter()
        c.at(-10, KEYGUARD_HIDDEN)
        c.at(10, KEYGUARD_HIDDEN)
        c.at(20, KEYGUARD_HIDDEN)
        c.end(min(60), interactive = true)
        assertTrue(c.locks)
        assertEquals(2, c.unlocks)
        assertFalse(counter().apply { end(min(60), interactive = true) }.locks)
    }

    @Test
    fun timeAcrossMidnightIsSplitBetweenTheDays() {
        val c = counter(days = 2)
        c.at(1430, ACTIVITY_RESUMED, "maps", "Main")
        c.at(1460, ACTIVITY_PAUSED, "maps", "Main")
        c.end(min(1500), interactive = true)
        assertEquals(min(10), c.byDay[0])
        assertEquals(min(20), c.byDay[1])
        assertEquals(30, c.minutes("maps"))
    }
}
