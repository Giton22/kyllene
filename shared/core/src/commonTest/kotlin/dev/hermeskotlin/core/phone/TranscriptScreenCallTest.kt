package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.chat.ToolActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class TranscriptScreenCallTest {
    private fun screen(text: String) = AgentScreen(
        listOf("""{"updateDataModel":{"surfaceId":"pick","path":"/value","value":"$text"}}"""),
        setOf("pick"),
        wait = true,
    )

    private fun call(id: String, screen: AgentScreen) =
        ToolActivity(id, SHOW_UI_TOOL, running = true, agentScreen = screen)

    private fun state(vararg calls: ToolActivity) =
        ChatState(messages = listOf(ChatMessage.Assistant("reply", tools = calls.toList())))

    @Test
    fun aLivePluginToolCanUseAStandaloneCard() = runTest {
        val outer = ToolActivity("plugin", "plugin_tool", running = true)
        val placement = awaitScreenPlacement(MutableStateFlow(state(outer)), screen("pick"), { true }) { false }
        assertNull(placement.transcriptCallId)
        assertTrue(placement.canShowStandalone(state(outer)))
        assertFalse(placement.canShowStandalone(state(outer.copy(running = false), outer.copy(id = "another"))))
    }

    @Test
    fun preferStandaloneSkipsTheTranscriptGraceDelay() = runTest {
        val outer = ToolActivity("plugin", "plugin_tool", running = true)
        val start = currentTime
        val placement = awaitScreenPlacement(
            MutableStateFlow(state(outer)), screen("pick"), { true }, preferStandalone = true,
        ) { false }
        assertNull(placement.transcriptCallId)
        assertEquals(0L, currentTime - start)
    }

    @Test
    fun aDelayedDirectCallKeepsItsTranscriptCard() = runTest {
        val screen = screen("pick")
        val states = flow {
            emit(state())
            delay(3_000)
            emit(state(call("direct", screen)))
        }
        assertEquals("direct", awaitScreenPlacement(states, screen, { true }) { false }.transcriptCallId)
    }

    @Test
    fun anUnrelatedDirectScreenDoesNotBlockThePluginCard() = runTest {
        val outer = ToolActivity("plugin", "plugin_tool", running = true)
        val pending = screen("pick")
        val other = pending.copy(surfaceIds = setOf("other"))
        val direct = call("other-direct", other)
        val placement = awaitScreenPlacement(MutableStateFlow(state(outer, direct)), pending, { true }) { false }
        assertNull(placement.transcriptCallId)
        assertFalse(placement.conflictsWithDirect(state(outer, direct), pending))
        assertTrue(placement.conflictsWithDirect(state(outer, direct, call("late-direct", pending)), pending))
    }

    @Test
    fun aLateDirectCallTakesOverAnUnansweredStandaloneRequest() = runTest {
        val pending = screen("pick")
        val outer = ToolActivity("plugin", "plugin_tool", running = true)
        val states = MutableStateFlow(state(outer))
        launch {
            delay(2_500)
            states.value = state(outer, call("direct", pending))
        }
        val result = runScreenPlacement(states, pending, { true }, claimed = { false },
            direct = { it },
            standalone = { placement ->
                assertEquals(GatewayCardAnchor("reply", "plugin"), placement.anchor(states.value))
                states.first { placement.conflictsWithDirect(it, pending) }
                throw PhoneToolException(AgentScreens.NOT_SHOWN)
            },
        )
        assertEquals("direct", result)
    }

    @Test
    fun aGatewayCardStaysWithTheOriginalReplyAfterHistoryReloads() {
        val anchor = GatewayCardAnchor("live-1", "outer-tool")
        val stored = ChatMessage.Assistant("saved-2", tools = listOf(ToolActivity("outer-tool", "plugin_tool")))
        val newer = ChatMessage.Assistant("saved-3", text = "A newer reply.")
        assertEquals("saved-2", anchor.replyKey(listOf(stored, newer)))
        assertNull(anchor.replyKey(listOf(newer)))
    }

    @Test
    fun desktopPlacementUsesTheCallersNotShownMessage() = runTest {
        val message = "The open desktop chat could not show the screen."
        val failed = assertFailsWith<PhoneToolException> {
            awaitScreenPlacement(MutableStateFlow(state()), screen("pick"), { true }, message) { false }
        }
        assertEquals(message, failed.message)
    }

    @Test
    fun anIdleChatDoesNotShowAnUnmatchedGatewayRequest() = runTest {
        val idle = state(ToolActivity("old", "plugin_tool", running = false))
        assertFailsWith<PhoneToolException> {
            awaitScreenPlacement(MutableStateFlow(idle), screen("pick"), { true }) { false }
        }
    }

    @Test
    fun overlappingCallsOnTheSameSurfaceMatchTheirOwnArguments() {
        val older = call("older", screen("first"))
        val newer = call("newer", screen("second"))
        assertEquals("newer", matchTranscriptScreenCall(sequenceOf(older, newer), screen("second")) { false })
    }

    @Test
    fun textStreamingDoesNotRematchUnchangedToolCalls() = runTest {
        val first = call("first", screen("other"))
        val wanted = call("wanted", screen("pick"))
        fun state(text: String, vararg calls: ToolActivity) =
            ChatState(messages = listOf(ChatMessage.Assistant("reply", text = text, tools = calls.toList())))

        val considered = mutableListOf<String>()
        val actual = awaitTranscriptScreenCall(
            flowOf(state("A", first), state("AB", first), state("ABC", first), state("ABCD", first, wanted)),
            screen("pick"),
            accepts = { true },
        ) { id ->
            considered += id
            false
        }
        assertEquals("wanted", actual)
        assertEquals(listOf("first", "first", "wanted"), considered)
    }

    @Test
    fun identicalUnclaimedCallsFailClosed() {
        val screen = screen("same")
        val older = call("older", screen)
        val newer = call("newer", screen)
        assertFailsWith<PhoneToolException> {
            matchTranscriptScreenCall(sequenceOf(older, newer), screen) { false }
        }
        assertEquals("newer", matchTranscriptScreenCall(sequenceOf(older, newer), screen) { it == "older" })
    }
}
