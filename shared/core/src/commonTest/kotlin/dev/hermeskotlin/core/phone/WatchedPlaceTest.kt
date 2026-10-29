package dev.hermeskotlin.core.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedPlaceTest {

    private val home = WatchedPlace(
        id = "a1",
        name = "Home",
        latitude = 52.52,
        longitude = 13.405,
        radiusMeters = 200,
        trigger = WatchedPlace.ENTER,
        message = "I'm home: what's for dinner?",
        gateway = "https://gateway.example",
        storedSessionId = "s1",
    )

    @Test
    fun placesAreKeptAndReadBack() {
        val places = listOf(home, home.copy(id = "a2", trigger = WatchedPlace.EXIT, profile = "work", chatTitle = "Plans"))
        assertEquals(places, WatchedPlace.decode(WatchedPlace.encode(places)))
    }

    @Test
    fun nothingSavedOrSomethingBrokenWatchesNothing() {
        assertEquals(emptyList(), WatchedPlace.decode(null))
        assertEquals(emptyList(), WatchedPlace.decode("{not a list"))
    }

    @Test
    fun beingThereAlreadyIsNoArrival() {
        // Android re-arms with no memory and reports "entered" for a phone that never left.
        assertFalse(home.copy(inside = true).changes(entering = true))
        assertTrue(home.copy(inside = false).changes(entering = true))
        // Unknown: Herald can't tell, so it's taken as news.
        assertTrue(home.changes(entering = true))
        assertFalse(home.copy(inside = false).changes(entering = false))
    }

    @Test
    fun theTriggerSaysWhichWayItFires() {
        assertTrue(home.arriving)
        assertFalse(home.copy(trigger = WatchedPlace.EXIT).arriving)
    }
}
