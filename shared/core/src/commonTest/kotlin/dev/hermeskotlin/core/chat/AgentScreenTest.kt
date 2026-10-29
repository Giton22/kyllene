package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentScreenTest {

    private val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"pick","catalogId":"basic"}}"""
    private val components = """{"version":"v0.9.1","updateComponents":{"surfaceId":"pick","components":[{"id":"root","component":"Text","text":"Hi"}]}}"""

    private fun args(json: String) = HermesJson.parseToJsonElement(json)

    @Test
    fun theMessagesAreKeptInOrderWithTheirSurface() {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$create,$components]}"""))!!
        assertEquals(listOf(create, components).map { HermesJson.parseToJsonElement(it).toString() }, screen.messages)
        assertEquals(setOf("pick"), screen.surfaceIds)
        // Waiting for a tap is what the tool does unless told otherwise.
        assertEquals(true, screen.wait)
    }

    @Test
    fun messagesWrittenAsOneStringAreRead() {
        val list = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":${JsonPrimitive("[$create,$components]")},"wait":false}"""))!!
        assertEquals(2, list.messages.size)
        assertFalse(list.wait)
        val lines = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":${JsonPrimitive("$create\n$components\n")}}"""))!!
        assertEquals(2, lines.messages.size)
        // A stored call's arguments are JSON text.
        assertEquals(setOf("pick"), AgentScreen.from(SHOW_UI_TOOL, JsonPrimitive("""{"messages":[$create]}"""))?.surfaceIds)
    }

    @Test
    fun aCallThroughTheToolBridgeIsRead() {
        val bridged = args("""{"calls":[{"name":"$SHOW_UI_TOOL","arguments":{"messages":[$create]}}]}""")
        assertEquals(setOf("pick"), AgentScreen.from("tool_call", bridged)?.surfaceIds)
        assertNull(AgentScreen.from("tool_call", args("""{"calls":[{"name":"terminal","arguments":{"messages":[$create]}}]}""")))
    }

    @Test
    fun otherToolsAndOddMessagesAreNoScreen() {
        assertNull(AgentScreen.from("terminal", args("""{"messages":[$create]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$create, "text"]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":"not json"}""")))
        // Without a surface there's nothing to draw.
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[{"version":"v0.9.1"}]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, null))
        val tooMany = (0..AgentScreen.MAX_MESSAGES).joinToString(",") { create }
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$tooMany]}""")))
    }

    @Test
    fun aLiveCallCarriesItsScreen() {
        val state = ChatState().reduce(
            GatewayEvent(
                "tool.start",
                HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"$SHOW_UI_TOOL","args":{"messages":[$create]}}"""),
                sessionId = "rt",
                seq = null,
            ),
        )
        val tool = assertIs<ChatMessage.Assistant>(state.messages.last()).tools.single()
        assertEquals(setOf("pick"), tool.agentScreen?.surfaceIds)
    }
}
