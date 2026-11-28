package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
    fun aGatewayCardOnlyAnswersItsOwnRequestAfterDrawing() = runTest {
        val screens = AgentScreens()
        var ownsChat = true
        val card = screen("form")
        val call = async { screens.awaitGatewayCard(card, card, "gateway-chat", "request-1") { ownsChat } }
        runCurrent()
        val result = JsonPrimitive("submitted")
        assertEquals(card, screens.gatewayCards.value.single().screen)
        assertFalse(screens.answer("gateway-chat", "form", result))
        assertFalse(screens.answer("gateway-chat", "form", result, "request-1"))
        screens.validated("gateway-chat", setOf("form"), "request-1")
        screens.shown("gateway-chat", setOf("form"), "request-1")
        assertFalse(screens.answer("another-chat", "form", result, "request-1"))
        assertFalse(screens.answer("gateway-chat", "form", result, "older-request"))
        ownsChat = false
        assertFalse(screens.answer("gateway-chat", "form", result, "request-1"))
        ownsChat = true
        assertTrue(screens.answer("gateway-chat", "form", result, "request-1"))
        assertEquals(result, call.await())
        assertFalse(screens.gatewayCards.value.single().call.takesTaps)
        assertFalse(screens.answer("gateway-chat", "form", result, "request-1"))
    }

    @Test
    fun aGatewayUpdatePreservesUntouchedSurfacesAndUsesOnlyCurrentHistory() = runTest {
        val screens = AgentScreens()
        var owned = true
        val original = AgentScreen(
            listOf("""{"createSurface":{"surfaceId":"a"}}""", """{"createSurface":{"surfaceId":"b"}}"""),
            setOf("a", "b"),
            wait = false,
        )
        val first = async { screens.awaitGatewayCard(original, original, "chat", "first") { owned } }
        runCurrent()
        screens.validated("chat", original.surfaceIds, "first")
        screens.shown("chat", original.surfaceIds, "first")
        first.await()

        val update = AgentScreen(listOf("""{"updateDataModel":{"surfaceId":"a"}}"""), setOf("a"), wait = false)
        val drawing = screens.gatewayDrawing(update, "chat", null)
        assertEquals(listOf(original.messages.first(), update.messages.single()), drawing.messages)
        assertEquals(emptyMap(), screens.got.value)
        val second = async { screens.awaitGatewayCard(update, drawing, "chat", "second") { owned } }
        runCurrent()
        assertEquals(listOf(setOf("b"), setOf("a")), screens.gatewayCards.value.map { it.screen.surfaceIds })
        screens.validated("chat", update.surfaceIds, "second")
        screens.shown("chat", update.surfaceIds, "second")
        second.await()
        assertEquals(listOf(setOf("b"), setOf("a")), screens.gatewayCards.value.map { it.screen.surfaceIds })
        val tooMany = update.copy(messages = List(AgentScreen.MAX_MESSAGES) { update.messages.single() })
        assertFailsWith<PhoneToolException> { screens.gatewayDrawing(tooMany, "chat", null) }
        owned = false
        assertFailsWith<PhoneToolException> { screens.gatewayDrawing(update, "chat", null) }
    }

    @Test
    fun aShownGatewayRequestEndsAsSoonAsItsToolStops() = runTest {
        val screens = AgentScreens()
        val active = MutableStateFlow(true)
        val form = screen("form")
        val request = async {
            runCatching {
                screens.awaitGatewayCard(form, form, "chat", "request", changes = active.map { }, isWaiting = { active.value }) { true }
            }
        }
        runCurrent()
        screens.validated("chat", form.surfaceIds, "request")
        screens.shown("chat", form.surfaceIds, "request")
        active.value = false
        runCurrent()
        assertTrue(request.isCompleted)
        assertEquals(AgentScreens.NOT_SHOWN, request.await().exceptionOrNull()?.message)
        assertFalse(screens.gatewayCards.value.single().call.takesTaps)
    }

    @Test
    fun aGatewayUpdateKeepsItsCardIdStableWhenReplacingTheSameSurface() = runTest {
        val screens = AgentScreens()
        val initial = AgentScreen(
            listOf("""{"createSurface":{"surfaceId":"tasks"}}""", """{"updateDataModel":{"surfaceId":"tasks","path":"/step","value":"1"}}"""),
            setOf("tasks"),
            wait = true,
        )
        val first = async { screens.awaitGatewayCard(initial, initial, "chat", "req-1") { true } }
        runCurrent()
        assertEquals("req-1", screens.gatewayCards.value.single().cardId)
        screens.validated("chat", initial.surfaceIds, "req-1")
        screens.shown("chat", initial.surfaceIds, "req-1")
        assertTrue(screens.ownsGatewaySurface(initial, "chat", null))
        assertTrue(screens.answer("chat", "tasks", JsonPrimitive("next"), "req-1"))
        first.await()

        val update = AgentScreen(
            listOf("""{"updateDataModel":{"surfaceId":"tasks","path":"/step","value":"2"}}"""),
            setOf("tasks"),
            wait = true,
        )
        val drawing = screens.gatewayDrawing(update, "chat", null)
        val second = async { screens.awaitGatewayCard(update, drawing, "chat", "req-2") { true } }
        runCurrent()
        val pendingCard = screens.gatewayCards.value.single()
        assertEquals("req-2", pendingCard.call.id)
        assertEquals("req-1", pendingCard.cardId)
        screens.validated("chat", update.surfaceIds, "req-2")
        screens.shown("chat", update.surfaceIds, "req-2")
        assertTrue(screens.answer("chat", "tasks", JsonPrimitive("done"), "req-2"))
        second.await()
        assertEquals("req-1", screens.gatewayCards.value.single().cardId)
    }

    @Test
    fun aGatewayUpdateCompactsLongHistoryWithoutHittingTheMessageLimit() = runTest {
        val screens = AgentScreens()
        val initial = AgentScreen(
            listOf(
                """{"version":"v0.9","createSurface":{"surfaceId":"tasks"}}""",
                """{"version":"v0.9","updateComponents":{"surfaceId":"tasks","components":[{"id":"root","component":"Text","text":"0"}]}}""",
                """{"version":"v0.9","updateDataModel":{"surfaceId":"tasks","path":"/","value":{"step":0}}}""",
            ),
            setOf("tasks"),
            wait = false,
        )
        val start = async { screens.awaitGatewayCard(initial, initial, "chat", "req-0") { true } }
        runCurrent()
        screens.validated("chat", initial.surfaceIds, "req-0")
        screens.shown("chat", initial.surfaceIds, "req-0")
        start.await()

        repeat(60) { step ->
            val update = AgentScreen(
                listOf(
                    """{"version":"v0.9","updateComponents":{"surfaceId":"tasks","components":[{"id":"root","component":"Text","text":"${step + 1}"}]}}""",
                    """{"version":"v0.9","updateDataModel":{"surfaceId":"tasks","path":"/","value":{"step":${step + 1}}}}""",
                ),
                setOf("tasks"),
                wait = false,
            )
            val drawing = screens.gatewayDrawing(update, "chat", null)
            assertTrue(drawing.messages.size <= AgentScreen.MAX_MESSAGES)
            val id = "req-${step + 1}"
            val req = async { screens.awaitGatewayCard(update, drawing, "chat", id) { true } }
            runCurrent()
            screens.validated("chat", update.surfaceIds, id)
            screens.shown("chat", update.surfaceIds, id)
            req.await()
        }

        val finalCard = screens.gatewayCards.value.single()
        assertEquals("req-0", finalCard.cardId)
        assertTrue(finalCard.screen.messages.size <= AgentScreen.MAX_MESSAGES)
        assertTrue(finalCard.screen.messages.any { "\"createSurface\"" in it })
        assertTrue(finalCard.screen.messages.any { "\"text\":\"60\"" in it })
        assertTrue(finalCard.screen.messages.any { "\"step\":60" in it })
    }

    @Test
    fun aMatchedAndroidRequestOnlyAcceptsItsTranscriptCall() = runTest {
        val screens = AgentScreens()
        val card = screen("form")
        val request = async { screens.await(card, "chat", transcriptId = "latest") }
        runCurrent()
        val result = JsonPrimitive("submitted")
        assertFalse(screens.answer("chat", "form", result, transcriptId = "older"))
        screens.shown("chat", card.surfaceIds, transcriptId = "latest")
        assertTrue(screens.answer("chat", "form", result, transcriptId = "latest"))
        assertEquals(result, request.await())
    }

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
