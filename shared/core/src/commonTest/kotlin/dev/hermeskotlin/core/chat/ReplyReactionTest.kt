package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplyReactionTest {
    private fun reactionCompleteEvent(payload: String) = dev.hermeskotlin.core.rpc.GatewayEvent(
        "message.complete", HermesJson.parseToJsonElement(payload), "runtime", null,
    )

    @Test
    fun aMergedReplyTargetsTheAnswerRowAndRestoresItsUserReaction() {
        val step = HermesJson.decodeFromString<SessionMessage>("""{"id":41,"role":"assistant","reasoning":"Thinking"}""")
        val answer = HermesJson.decodeFromString<SessionMessage>("""{"id":42,"role":"assistant","content":"Done",
            "display_metadata":{"reactions":[{"author":"agent","emoji":"🚀"},{"author":"user","emoji":"👍"}]}}""")
        val reply = assertIs<ChatMessage.Assistant>(historyToMessages(listOf(step, answer)).single())
        assertEquals(42L, reply.reactionRowId)
        assertEquals("👍", reply.userReaction)
        assertEquals("Done", reply.text)
    }

    @Test
    fun aLiveReplyUsesTheGatewayCompletionReceiptForItsAddress() {
        val completed = ChatState().reduce(reactionCompleteEvent(
            """{"text":"Done","status":"complete","persisted_turn":{"row_ids":[41,42],"complete":true,"final_assistant_row_id":42}}"""))
        assertEquals(42L, assertIs<ChatMessage.Assistant>(completed.messages.single()).reactionRowId)
    }

    @Test
    fun reactionPromptsAddressTheReply() {
        for (reaction in ReplyReaction.entries) {
            val prompt = checkNotNull(reaction.prompt(42, "Done"))
            assertTrue(prompt.contains("message #42") && prompt.contains("\"Done\""))
        }
    }
}
