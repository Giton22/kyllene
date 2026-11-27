package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopScreenToolTest {
    private fun screen(text: String) = AgentScreen(
        listOf("""{"updateDataModel":{"surfaceId":"pick","path":"/value","value":"$text"}}"""),
        setOf("pick"),
        wait = true,
    )

    private fun call(id: String, screen: AgentScreen) =
        ToolActivity(id, SHOW_UI_TOOL, running = true, agentScreen = screen)

    @Test
    fun overlappingCallsOnTheSameSurfaceMatchTheirOwnArguments() {
        val older = call("older", screen("first"))
        val newer = call("newer", screen("second"))
        assertEquals("newer", matchDesktopScreenCall(sequenceOf(older, newer), screen("second")) { false })
    }

    @Test
    fun textStreamingDoesNotRematchUnchangedToolCalls() = runTest {
        val first = call("first", screen("other"))
        val wanted = call("wanted", screen("pick"))
        fun state(text: String, vararg calls: ToolActivity) =
            ChatState(messages = listOf(ChatMessage.Assistant("reply", text = text, tools = calls.toList())))

        val considered = mutableListOf<String>()
        val actual = awaitDesktopScreenCall(
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
            matchDesktopScreenCall(sequenceOf(older, newer), screen) { false }
        }
        assertEquals("newer", matchDesktopScreenCall(sequenceOf(older, newer), screen) { it == "older" })
    }
}
