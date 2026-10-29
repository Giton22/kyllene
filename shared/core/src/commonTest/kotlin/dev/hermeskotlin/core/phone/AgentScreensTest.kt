package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentScreensTest {

    private fun screen(vararg surfaces: String) = AgentScreen(listOf("{}"), surfaces.toSet(), wait = true)

    @Test
    fun aTapAnswersTheCallWaitingOnItsSurface() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick")) }
        runCurrent()
        assertEquals(setOf("pick"), screens.live.value)
        assertFalse(screens.answer("other", JsonPrimitive("x")))
        assertTrue(screens.answer("pick", JsonPrimitive("tapped")))
        assertEquals(JsonPrimitive("tapped"), call.await())
        // Answered once: the screen takes no more taps.
        assertEquals(emptySet(), screens.live.value)
        assertFalse(screens.answer("pick", JsonPrimitive("again")))
    }

    @Test
    fun aFailureReachesTheAgent() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val call = async { screens.await(screen("pick")) }
            runCurrent()
            screens.fail("pick", "Unknown component Slidr")
            assertEquals("Unknown component Slidr", assertFailsWith<PhoneToolException> { call.await() }.message)
        }
    }

    @Test
    fun aNewerCallOnTheSameSurfaceTakesOver() = runTest { supervisorScope {
        val screens = AgentScreens()
        val first = async { screens.await(screen("pick")) }
        runCurrent()
        val second = async { screens.await(screen("pick", "more")) }
        runCurrent()
        assertFailsWith<PhoneToolException> { first.await() }
        assertEquals(setOf("pick", "more"), screens.live.value)
        screens.answer("more", JsonPrimitive("ok"))
        assertEquals(JsonPrimitive("ok"), second.await())
    } }

    @Test
    fun aCancelledCallLeavesNothingLive() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick")) }
        runCurrent()
        call.cancel()
        runCurrent()
        assertEquals(emptySet(), screens.live.value)
    }
}
