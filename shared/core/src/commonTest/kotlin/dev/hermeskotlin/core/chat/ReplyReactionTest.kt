package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aLaterStepWithoutAReactionKeepsTheReplyOnTheRowThatHasOne() {
        // A goal's continuation adds an assistant row with no prompt between: one reply, still showing its 👍.
        val first = HermesJson.decodeFromString<SessionMessage>("""{"id":10,"role":"assistant","content":"Step one done",
            "display_metadata":{"reactions":[{"author":"user","emoji":"👍"}]}}""")
        val next = SessionMessage(id = 12, role = "assistant", content = JsonPrimitive("Step two done"))
        val reply = assertIs<ChatMessage.Assistant>(historyToMessages(listOf(first, next)).single())
        assertEquals(10L, reply.reactionRowId)
        assertEquals("👍", reply.userReaction)
    }

    @Test
    fun aReactionInMetadataHeldAsAStringIsRead() {
        // Older backends send display_metadata as a string holding the object.
        val answer = HermesJson.decodeFromString<SessionMessage>("""{"id":42,"role":"assistant","content":"Done",
            "display_metadata":"{\"reactions\":[{\"author\":\"user\",\"emoji\":\"🔍\"}]}"}""")
        assertEquals("🔍", assertIs<ChatMessage.Assistant>(historyToMessages(listOf(answer)).single()).userReaction)
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
        val prompt = checkNotNull(ReplyReaction.Explain.prompt(reply))
        val excerpt = HermesJson.decodeFromString<String>(prompt.substringAfter("The reply ends: "))

        assertEquals("a".repeat(599), excerpt)
    }

    @Test
    fun aQuotedReferenceIsNotExpandedByTheGateway() {
        // The gateway reads `@diff` or `@file:` anywhere in a prompt and pulls the diff or the file in.
        val prompt = checkNotNull(ReplyReaction.Verify.prompt("Run @diff, then attach @file:notes.md or mail me@example.com"))
        assertFalse(Regex("""(?<![\w/])@(diff|staged|file:|folder:|git:|url:|[a-zA-Z])""").containsMatchIn(prompt), prompt)
        assertTrue(prompt.contains("@\u2060diff"))
    }

    @Test
    fun reactionPromptsQuoteTheReplyWithoutItsRowId() {
        for (reaction in ReplyReaction.entries) {
            // The user sees this as their prompt; a stored row's id means nothing to them or the agent.
            val prompt = reaction.prompt("Done") ?: continue
            assertTrue(prompt.endsWith("The reply ends: \"Done\""), prompt)
            assertFalse(prompt.contains("#"), prompt)
        }
    }
}
