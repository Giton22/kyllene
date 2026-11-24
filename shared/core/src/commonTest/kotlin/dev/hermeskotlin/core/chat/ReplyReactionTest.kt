package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
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
    fun silentFinalRowRetainsTheLiveReplyReactionWhenHistoryReloads() {
        val answer = SessionMessage(id = 41, role = "assistant", content = JsonPrimitive("Done"))
        val silent = HermesJson.decodeFromString<SessionMessage>("""{"id":42,"role":"assistant","content":"[SILENT]",
            "display_metadata":{"reactions":[{"author":"user","emoji":"👍"}]}}""")
        val complete = ChatState().reduce(reactionCompleteEvent(
            """{"text":"Done","status":"complete","persisted_turn":{"row_ids":[41,42],"complete":true,"final_assistant_row_id":42}}"""))
        val live = assertIs<ChatMessage.Assistant>(complete.messages.single())

        val stored = assertIs<ChatMessage.Assistant>(historyToMessages(listOf(answer, silent)).single())
        assertEquals(live.reactionRowId, stored.reactionRowId)
        assertEquals("👍", stored.userReaction)
        assertEquals("Done", stored.text)
    }

    @Test
    fun excerptDoesNotStartWithHalfAnEmoji() {
        val reply = "prefix 😀" + "a".repeat(599)
        val prompt = checkNotNull(ReplyReaction.Explain.prompt(42, reply))
        val excerpt = HermesJson.decodeFromString<String>(prompt.substringAfter("(excerpt): "))

        assertEquals("a".repeat(599), excerpt)
    }

    @Test
    fun reactionPromptsAddressTheReply() {
        for (reaction in ReplyReaction.entries) {
            val prompt = checkNotNull(reaction.prompt(42, "Done"))
            assertTrue(prompt.contains("message #42") && prompt.contains("\"Done\""))
        }
    }
}
