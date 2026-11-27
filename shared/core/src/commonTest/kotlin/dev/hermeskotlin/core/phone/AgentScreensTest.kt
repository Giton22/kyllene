package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val call = async { screens.await(screen("pick"), "chat-a") }
        runCurrent()
        assertEquals(listOf(AgentScreens.Live("chat-a", setOf("pick"))), screens.live.value)
        screens.shown("chat-a", setOf("pick"))
        assertFalse(screens.answer("chat-a", "other", JsonPrimitive("x")))
        assertTrue(screens.answer("chat-a", "pick", JsonPrimitive("tapped")))
        assertEquals(JsonPrimitive("tapped"), call.await())
        // Answered once: the screen takes no more taps.
        assertEquals(emptyList(), screens.live.value)
        assertFalse(screens.answer("chat-a", "pick", JsonPrimitive("again")))
    }

    @Test
    fun theSameSurfaceInAnotherChatIsAnotherScreen() = runTest {
        val screens = AgentScreens()
        val a = async { screens.await(screen("pick-1"), "chat-a") }
        val b = async { screens.await(screen("pick-1"), "chat-b") }
        runCurrent()
        screens.shown("chat-a", setOf("pick-1"))
        screens.shown("chat-b", setOf("pick-1"))
        assertTrue(screens.answer("chat-b", "pick-1", JsonPrimitive("b")))
        assertEquals(JsonPrimitive("b"), b.await())
        assertTrue(a.isActive)
        screens.answer("chat-a", "pick-1", JsonPrimitive("a"))
        assertEquals(JsonPrimitive("a"), a.await())
    }

    @Test
    fun aNonBlockingCardReportsSuccessOnlyAfterItDraws() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick").copy(wait = false), "chat-a") }
        runCurrent()
        assertEquals(emptyList(), screens.live.value)
        assertFalse(screens.answer("chat-a", "pick", JsonPrimitive("x")))
        assertTrue(call.isActive)
        screens.shown("chat-a", setOf("pick"))
        assertEquals(buildJsonObject { put("shown", true) }, call.await())
    }

    @Test
    fun desktopTapsBelongToTheOriginalGatewayProfileAndChatOwner() = runTest {
        val screens = AgentScreens()
        val chat = desktopAgentScreenChat("https://a.example", "work", "chat-1")
        var ownsChat = true
        val call = async { screens.await(screen("pick"), chat) { ownsChat } }
        runCurrent()
        screens.shown(chat, setOf("pick"))
        val differentGateway = desktopAgentScreenChat("https://b.example", "work", "chat-1")
        val differentProfile = desktopAgentScreenChat("https://a.example", "personal", "chat-1")
        val result = JsonPrimitive("tapped")
        assertFalse(screens.answer(differentGateway, "pick", result))
        assertFalse(screens.answer(differentProfile, "pick", result))
        ownsChat = false
        assertFalse(screens.answer(chat, "pick", result))
        assertTrue(call.isActive)
        call.cancel()
    }

    @Test
    fun aScreenNoChatShowsSoonIsNotWaitedOn() = runTest {
        supervisorScope {
            val screens = AgentScreens(shownWithinMs = 1_000)
            val call = async { screens.await(screen("pick"), "chat-a") }
            runCurrent()
            // Shown in another chat: still not this one.
            screens.shown("chat-b", setOf("pick"))
            advanceTimeBy(1_001)
            assertEquals(AgentScreens.NOT_SHOWN, assertFailsWith<PhoneToolException> { call.await() }.message)
            assertEquals(emptyList(), screens.live.value)
        }
    }

    @Test
    fun aFailureReachesTheAgent() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val call = async { screens.await(screen("pick"), null) }
            runCurrent()
            // The gateway didn't say which chat: any chat showing the surface is it.
            screens.shown("chat-a", setOf("pick"))
            screens.fail("chat-a", "pick", "Unknown component Slidr")
            assertEquals("Unknown component Slidr", assertFailsWith<PhoneToolException> { call.await() }.message)
        }
    }

    @Test
    fun aNewerCallOnTheSameSurfaceTakesOver() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val first = async { screens.await(screen("pick"), "chat-a") }
            runCurrent()
            val second = async { screens.await(screen("pick", "more"), "chat-a") }
            runCurrent()
            assertEquals(AgentScreens.REPLACED, assertFailsWith<PhoneToolException> { first.await() }.message)
            assertEquals(listOf(AgentScreens.Live("chat-a", setOf("pick", "more"))), screens.live.value)
            screens.shown("chat-a", setOf("more"))
            screens.answer("chat-a", "more", JsonPrimitive("ok"))
            assertEquals(JsonPrimitive("ok"), second.await())
        }
    }

    @Test
    fun olderDesktopCallbacksCannotCompleteTheReplacement() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val older = async { screens.await(screen("pick"), "chat-a", callId = "older") }
            runCurrent()
            val newer = async { screens.await(screen("pick"), "chat-a", callId = "newer") }
            runCurrent()
            assertFailsWith<PhoneToolException> { older.await() }
            screens.shown("chat-a", setOf("pick"), callId = "older")
            assertFalse(screens.answer("chat-a", "pick", JsonPrimitive("stale"), callId = "older"))
            assertFalse(screens.fail("chat-a", "pick", "Stale failure", callId = "older"))
            assertFalse(screens.answer("chat-a", "pick", JsonPrimitive("too soon"), callId = "newer"))
            screens.validated("chat-a", setOf("pick"), callId = "newer")
            screens.shown("chat-a", setOf("pick"), callId = "newer")
            assertTrue(screens.answer("chat-a", "pick", JsonPrimitive("current"), callId = "newer"))
            assertEquals(JsonPrimitive("current"), newer.await())
        }
    }

    @Test
    fun desktopWaitsForEverySurfaceBeforeRecordingAndAcknowledging() = runTest {
        val screens = AgentScreens()
        val batch = AgentScreen(
            listOf(
                """{"createSurface":{"surfaceId":"one"}}""",
                """{"createSurface":{"surfaceId":"two"}}""",
            ),
            setOf("one", "two"),
            wait = false,
        )
        val call = async { screens.await(batch, "chat-a", callId = "both") }
        runCurrent()
        screens.validated("chat-a", setOf("one"), callId = "both")
        assertEquals(AgentScreens.DesktopStage.Validating, screens.desktop.value.single().stage)
        screens.validated("chat-a", setOf("two"), callId = "both")
        assertEquals(AgentScreens.DesktopStage.Showing, screens.desktop.value.single().stage)
        screens.shown("chat-a", setOf("one"), callId = "both")
        runCurrent()
        assertTrue(call.isActive)
        assertTrue(screens.got.value.isEmpty())
        screens.shown("chat-a", setOf("two"), callId = "both")
        assertEquals(buildJsonObject { put("shown", true) }, call.await())
        assertEquals(batch.messages.toSet(), screens.got.value.values.flatten().toSet())
    }

    @Test
    fun failedDesktopUpdatesDoNotReplaceRecordedMessages() = runTest {
        supervisorScope {
            val screens = AgentScreens()
            val original = """{"createSurface":{"surfaceId":"pick"}}"""
            val update = """{"updateDataModel":{"surfaceId":"pick","path":"/name","value":"new"}}"""
            val other = """{"createSurface":{"surfaceId":"other"}}"""
            screens.record(AgentScreen(listOf(original), setOf("pick"), true), "chat-a")
            val batch = AgentScreen(listOf(update, other), setOf("pick", "other"), true)
            val call = async { screens.await(batch, "chat-a", callId = "update") }
            runCurrent()
            screens.validated("chat-a", setOf("pick"), callId = "update")
            assertEquals(AgentScreens.DesktopStage.Validating, screens.desktop.value.single().stage)
            assertTrue(screens.fail("chat-a", "other", "Cannot draw", callId = "update"))
            assertFailsWith<PhoneToolException> { call.await() }
            assertEquals(1, screens.got.value.size)
            assertEquals(listOf(original), screens.got.value["chat-a" to "pick"])
        }
    }

    @Test
    fun aCancelledCallLeavesNothingLive() = runTest {
        val screens = AgentScreens()
        val call = async { screens.await(screen("pick"), "chat-a") }
        runCurrent()
        call.cancel()
        runCurrent()
        assertEquals(emptyList(), screens.live.value)
    }
}
