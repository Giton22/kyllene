package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMessageTest {

    @Test
    fun readsTheCurrentForm() {
        assertEquals(
            AgentMessage("Research Buddy", "research-buddy", "Here are the findings:\n- one"),
            AgentMessage.parse("Message from 🤖 Research Buddy (@research-buddy): Here are the findings:\n- one"),
        )
        // Cross-connection handles carry the machine.
        assertEquals("hermes", AgentMessage.parse("Message from 🤖 hermes (@hermes@mac-mini): hi")?.handle)
    }

    @Test
    fun readsTheEmojilessAndLegacyForms() {
        assertEquals(AgentMessage("scribe", null, "done"), AgentMessage.parse("Message from scribe: done"))
        assertEquals(AgentMessage("scribe", null, "done"), AgentMessage.parse("[Message from agent 'scribe'] done"))
    }

    @Test
    fun dropsTheNoReplyMarkerMeantForTheModel() {
        assertEquals(
            AgentMessage("Scribe", "scribe", "Draft is merged, thanks.", noReplyNeeded = true),
            AgentMessage.parse("Message from 🤖 Scribe (@scribe): [no reply needed] Draft is merged, thanks."),
        )
        // Only where the sender puts it: the same words later in the text are the bot's own.
        assertEquals(
            AgentMessage("Scribe", "scribe", "Say [no reply needed] if you agree."),
            AgentMessage.parse("Message from 🤖 Scribe (@scribe): Say [no reply needed] if you agree."),
        )
        // Hermes only marks a signed message; text that merely starts "Message from X:" keeps what was typed.
        assertEquals(
            AgentMessage("scribe", null, "[no reply needed] testing"),
            AgentMessage.parse("Message from scribe: [no reply needed] testing"),
        )
    }

    @Test
    fun leavesWhatTheUserTypedAlone() {
        assertNull(AgentMessage.parse("Can you check the Message from scribe: earlier?"))
        assertNull(AgentMessage.parse("hello"))
        assertNull(AgentMessage.parse(""))
    }
}
