package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.AgentScreen
import dev.hermeskotlin.core.chat.SHOW_UI_TOOL
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.phone.PhoneToolException
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
