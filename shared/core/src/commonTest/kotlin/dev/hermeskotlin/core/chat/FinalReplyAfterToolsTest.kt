package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The response after a turn's last tool (#546). `message.complete` `text` is the last model response
 * (`_turn_outcome` in tui_gateway/prompt_turn.py, v0.22.0 to v0.23.4; the authority's settled `response`), so when
 * that response didn't stream here (a non-streaming reply, deltas missed) it still shows, after the earlier segments.
 */
class FinalReplyAfterToolsTest {

    private val before = "I'll confirm with buttons before I save anything."
    private val middle = "The save says it was staged but not written. I'll check the file isn't there yet."
    private val final = "Done: the file is saved."

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun ChatState.apply(vararg events: GatewayEvent) = events.fold(this) { state, e -> state.reduce(e) }

    private fun tool(id: String, name: String) = arrayOf(
        event("tool.start", """{"tool_id":"$id","name":"$name","context":"x"}"""),
        event("tool.complete", """{"tool_id":"$id","name":"$name","summary":"ok","duration_s":0.1}"""),
    )

    private fun ChatState.reply() = assertIs<ChatMessage.Assistant>(messages.last { it is ChatMessage.Assistant })

    private fun ChatState.replyText() = reply().text

    private fun String.count(part: String) = windowed(part.length).count { it == part }

    private fun assertInOrder(text: String, vararg parts: String) {
        val at = parts.map { text.indexOf(it) }
        assertTrue(at.none { it < 0 } && at == at.sorted(), "want ${parts.toList()} in order: <$text>")
        parts.forEach { assertEquals(1, text.count(it), "<$it> shows ${text.count(it)} times: <$text>") }
    }

    private val start = ChatState(messages = listOf(ChatMessage.User("u", "save it")))

    /** Two segments streamed, each before a tool; [last] is what follows the second tool before the turn ends. */
    private fun twoSegments(vararg last: GatewayEvent) = start.apply(
        event("message.start", """{"turn_id":"t"}"""),
        event("message.delta", """{"text":"$before","turn_id":"t","segment":0,"offset":0}"""),
        event("message.interim", """{"text":"$before","already_streamed":true,"turn_id":"t","segment":0}"""),
        *tool("c1", "clarify"),
        event("message.delta", """{"text":"\n\n$middle","turn_id":"t","segment":1,"offset":0}"""),
        event("message.interim", """{"text":"$middle","already_streamed":true,"turn_id":"t","segment":1}"""),
        *tool("c2", "terminal"),
        *last,
    )

    @Test
    fun streamedSegmentsThenFinalOnlyInComplete() {
        val state = twoSegments(event("message.complete", """{"text":"$final","status":"complete"}"""))

        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
        assertEquals(listOf("c1", "c2"), state.reply().tools.map { it.id })
        assertTrue(state.responseStart == null, "the boundary outlived its turn")
    }

    @Test
    fun interimSegmentsThenFinalOnlyInComplete() {
        // A non-streaming provider: commentary beside tool calls comes as message.interim (already_streamed false).
        val state = start.apply(
            event("message.start", "{}"),
            event("message.interim", """{"text":"$before","already_streamed":false}"""),
            *tool("c1", "clarify"),
            event("message.interim", """{"text":"$middle","already_streamed":false}"""),
            *tool("c2", "terminal"),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun aFinalThatStreamedInPartShowsOnceInFull() {
        val state = twoSegments(
            event("message.delta", """{"text":"\n\nDone: the fi","segment":2,"offset":0}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun aReusedFinalThatAlreadyShowedIsNotAddedAgain() {
        // The agent's reuse site sent an earlier segment again as the final (`response_reused`).
        val state = twoSegments(event("message.complete", """{"text":"$middle","status":"complete","response_reused":true}"""))
        assertEquals("$before\n\n$middle", state.replyText())
    }

    @Test
    fun anUnflaggedFinalWithAnEarlierSegmentsWordsStillShows() {
        // Hermes never infers reuse from equal text: a model may say the same words twice.
        val state = twoSegments(event("message.complete", """{"text":"$before","status":"complete"}"""))
        assertEquals("$before\n\n$middle\n\n$before", state.replyText())
    }

    @Test
    fun aPreviewedFinalShowsOnce() {
        // The Codex app-server shape: the final comes as an interim after the last tool, then flagged in the completion.
        val state = twoSegments(
            event("message.interim", """{"text":"$final","already_streamed":false}"""),
            event("message.complete", """{"text":"$final","status":"complete","response_previewed":true}"""),
        )
        assertInOrder(state.replyText(), before, middle, final)
    }

    @Test
    fun aFinalThatStreamedStaysAsItStreamed() {
        val state = twoSegments(
            event("message.delta", """{"text":"\n\n$final","segment":2,"offset":0}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun aTransformedFinalReplacesWhatStreamedOfIt() {
        val state = twoSegments(
            event("message.delta", """{"text":"\n\nA draft answer.","segment":2,"offset":0}"""),
            event("message.complete", """{"text":"$final","status":"complete","response_transformed":true}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun aSilenceMarkerTheDeliveryDroppedLeavesTheStreamedText() {
        // Bot Mode strips a trailing silence marker from the delivered copy only; what streamed stays.
        val state = twoSegments(
            event("message.delta", """{"text":"\n\n$final [SILENT]","segment":2,"offset":0}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final [SILENT]", state.replyText())
    }

    @Test
    fun aCumulativeFinalAddsOnlyWhatFollowsTheSegments() {
        val state = twoSegments(event("message.complete", """{"text":"$before\n\n$middle\n\n$final","status":"complete"}"""))
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun theV0220ShapeWithoutSegmentStampsShowsTheFinal() {
        // v0.22.0's deltas carry only text.
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"$before"}"""),
            *tool("c1", "clarify"),
            event("message.delta", """{"text":"\n\n$middle"}"""),
            *tool("c2", "terminal"),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\n$final", state.replyText())
    }

    @Test
    fun theAuthorityShapeWithAnEmptyCompletionKeepsWhatStreamed() {
        // The session authority parts segments with a null delta and may send no text once the stream delivered it.
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"$before"}"""),
            event("message.delta", """{"text":null}"""),
            *tool("c1", "clarify"),
            event("message.delta", """{"text":"\n\n$final"}"""),
            event("message.complete", """{"text":"","status":"complete"}"""),
        )
        assertEquals("$before\n\n$final", state.replyText())
    }

    @Test
    fun theAuthorityShapeShowsAFinalThatDidNotStream() {
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"$before"}"""),
            event("message.delta", """{"text":null}"""),
            *tool("c1", "clarify"),
            event("message.delta", """{"text":null}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals("$before\n\n$final", state.replyText())
    }

    @Test
    fun aStoppedOrFailedTurnKeepsWhatStreamed() {
        // Their text can be the whole reply kept so far, which already showed.
        for (status in listOf("interrupted", "error")) {
            val state = twoSegments(event("message.complete", """{"text":"$before $middle","status":"$status"}"""))
            assertEquals("$before\n\n$middle", state.replyText(), status)
        }
    }

    @Test
    fun aTurnWithoutToolsThatStreamedNothingTakesTheFinal() {
        val state = start.apply(event("message.start", "{}"), event("message.complete", """{"text":"$final","status":"complete"}"""))
        assertEquals(final, state.replyText())
    }

    @Test
    fun aReplyRebuiltWithoutABoundaryPutsTheFinalLast() {
        // A reply opened from the gateway's copy (`inflight.assistant`) holds every segment so far, and no boundary.
        assertEquals("$before\n\n$middle\n\n$final", withFinalResponse("$before\n\n$middle", final, start = null, shown = false, transformed = false))
        // Its streamed start gives way to the whole of it.
        assertEquals("$before\n\n$final", withFinalResponse("$before\n\nDone: the", final, start = null, shown = false, transformed = false))
        // Already in it: left alone.
        assertEquals("$final\n\n$before", withFinalResponse("$final\n\n$before", final, start = null, shown = false, transformed = false))
    }

    @Test
    fun aBoundaryPastTheTextIsClamped() {
        assertEquals("$before\n\n$final", withFinalResponse(before, final, start = 999, shown = false, transformed = false))
    }

    @Test
    fun capturedV0234BotChatFramesShowTheFinalReply() {
        // Frames a v0.23.4 dashboard (legacy) sent for a Bot Chat on a named profile, fake model with two todo calls.
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"BEFORE-TOOL ","segment":0,"offset":0}"""),
            event("message.delta", """{"text":"text.","segment":0,"offset":12}"""),
            event("tool.generating", """{"name":"todo"}"""),
            event("message.interim", """{"text":"BEFORE-TOOL text.","already_streamed":true,"segment":0}"""),
            *tool("call_0", "todo_list"),
            event("message.delta", """{"text":"\n\nMIDDLE ","segment":1,"offset":0}"""),
            event("message.delta", """{"text":"text.","segment":1,"offset":9}"""),
            event("tool.generating", """{"name":"todo"}"""),
            event("message.interim", """{"text":"MIDDLE text.","already_streamed":true,"segment":1}"""),
            *tool("call_1", "todo_list"),
            event("message.delta", """{"text":"\n\nFINAL answer after the last tool.","segment":2,"offset":0}"""),
            event("message.complete", """{"text":"FINAL answer after the last tool.","status":"complete"}"""),
        )
        assertEquals("BEFORE-TOOL text.\n\nMIDDLE text.\n\nFINAL answer after the last tool.", state.replyText())
    }

    @Test
    fun capturedV0234BotChatRowsShowTheFinalReply() {
        val rows = HermesJson.decodeFromString<List<SessionMessage>>("""[
            {"id":1,"role":"user","content":"please save it"},
            {"id":2,"role":"assistant","content":"BEFORE-TOOL text.","finish_reason":"tool_calls","tool_calls":[{"id":"call_0","call_id":"call_0","type":"function","function":{"name":"tool_call","arguments":"{\"calls\": [{\"name\": \"todo_list\", \"arguments\": {}}]}"}}]},
            {"id":3,"role":"tool","content":"{\"todos\": []}","tool_call_id":"call_0","tool_name":"todo_list"},
            {"id":4,"role":"assistant","content":"MIDDLE text.","finish_reason":"tool_calls","tool_calls":[{"id":"call_1","call_id":"call_1","type":"function","function":{"name":"tool_call","arguments":"{\"calls\": [{\"name\": \"todo_list\", \"arguments\": {}}]}"}}]},
            {"id":5,"role":"tool","content":"{\"todos\": []}","tool_call_id":"call_1","tool_name":"todo_list"},
            {"id":6,"role":"assistant","content":"FINAL answer after the last tool.","finish_reason":"stop"}
        ]""")
        val text = historyToMessages(rows).filterIsInstance<ChatMessage.Assistant>().joinToString("|") { it.text }
        assertTrue("FINAL answer after the last tool." in text, text)
    }

    /** A steer sent while the final response streams: the first [split] chars of it went into the reply sealed above. */
    private fun steeredMidFinal(split: Int, vararg rest: GatewayEvent): List<ChatMessage.Assistant> =
        twoSegments(event("message.delta", """{"text":"\n\n${final.take(split)}","segment":2,"offset":0}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s", "and log it")) }
            .sealReplyBefore("s")
            .apply(*rest, event("message.complete", """{"text":"$final","status":"complete"}"""))
            .messages.filterIsInstance<ChatMessage.Assistant>()

    @Test
    fun aSteerMidFinalShowsTheFinalOnceAcrossBothParts() {
        val parts = steeredMidFinal(12, event("message.delta", """{"text":"${final.drop(12)}","segment":2,"offset":12}"""))
        assertEquals("$before\n\n$middle\n\n${final.take(12)}", parts.first().text)
        assertEquals(final.drop(12), parts.last().text)
    }

    @Test
    fun aSteerMidFinalWhoseRestDidntStreamShowsOnlyTheRestBelow() {
        val parts = steeredMidFinal(12)
        assertEquals("$before\n\n$middle\n\n${final.take(12)}", parts.first().text)
        assertEquals(final.drop(12).trim(), parts.last().text)
    }

    @Test
    fun aSteerAfterTheFinalStreamedOpensNoSecondCopy() {
        val parts = steeredMidFinal(final.length)
        assertEquals(1, parts.size, "parts: ${parts.map { it.text }}")
        assertEquals("$before\n\n$middle\n\n$final", parts.single().text)
    }

    @Test
    fun twoSteersMidFinalShowTheFinalOnce() {
        val parts = twoSegments(event("message.delta", """{"text":"\n\nDone: the "}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s1", "one")) }.sealReplyBefore("s1")
            .apply(event("message.delta", """{"text":"file "}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s2", "two")) }.sealReplyBefore("s2")
            .apply(
                event("message.delta", """{"text":"is saved."}"""),
                event("message.complete", """{"text":"$final","status":"complete"}"""),
            ).messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals(listOf("$before\n\n$middle\n\nDone: the ", "file ", "is saved."), parts.map { it.text })
    }

    @Test
    fun aRedirectMidFinalShowsTheNewResponseWhole() {
        // session.redirect interrupts the response and the model answers again, so the new final is all new.
        val parts = twoSegments(event("message.delta", """{"text":"\n\nDone: the fi"}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("r", "log it too")) }
            .sealReplyBefore("r", sameResponse = false)
            .apply(event("message.complete", """{"text":"Done: the file is saved and logged.","status":"complete"}"""))
            .messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals("Done: the file is saved and logged.", parts.last().text)
    }

    @Test
    fun aGapInsideTheFinalIsFilledFromTheFinal() {
        val state = twoSegments(
            event("message.delta", """{"text":"\n\nFound "}"""),
            event("message.delta", """{"text":"saved."}"""),
            event("message.complete", """{"text":"Found it. File saved.","status":"complete"}"""),
        )
        assertEquals("$before\n\n$middle\n\nFound it. File saved.", state.replyText())
    }

    @Test
    fun aFinalThatRepeatsTheEndOfAnEarlierSegmentStillShows() {
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"Checking the file is saved."}"""),
            *tool("c1", "terminal"),
            event("message.complete", """{"text":"file is saved.","status":"complete"}"""),
        )
        assertEquals("Checking the file is saved.\n\nfile is saved.", state.replyText())
    }

    @Test
    fun aFinalAlreadyAddedAsTextBesideTheCallsShowsOnce() {
        // An interim that didn't stream, then no tool: the final is that text, and an older gateway sets no flag.
        val state = start.apply(
            event("message.start", "{}"),
            *tool("c1", "terminal"),
            event("message.interim", """{"text":"$final","already_streamed":false}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals(final, state.replyText().trim())
    }

    @Test
    fun aCatchUpThatAddsStepsForgetsTheBoundary() {
        // The boundary is at c1; while away, a middle segment, c2 and part of the final came, read back whole.
        val away = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"$before"}"""),
            *tool("c1", "clarify"),
        ).withInflight("$before\n\n$middle\n\nDone: the fi")
        assertEquals(null, away.responseStart)
        val state = away.apply(event("message.complete", """{"text":"$final","status":"complete","response_transformed":true}"""))
        assertInOrder(state.replyText(), before, middle, final)
    }

    @Test
    fun aSteeredTurnWhoseEndWasLostCarriesNothingIntoTheNext() {
        val state = twoSegments(event("message.delta", """{"text":"\n\nSure, "}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s", "and log it")) }.sealReplyBefore("s")
            .apply(
                event("message.start", "{}"),
                event("message.complete", """{"text":"Sure, here is the list.","status":"complete"}"""),
            )
        assertEquals("Sure, here is the list.", state.replyText())
    }

    @Test
    fun aStaleReplyDropsTheCarriedStart() {
        val stale = twoSegments(event("message.delta", """{"text":"\n\nSure, "}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s", "and log it")) }.sealReplyBefore("s")
            .apply(event("message.delta", """{"text":"here"}"""))
            .withoutStaleReply()
        assertEquals(null, stale.responseStart)
    }

    @Test
    fun anInterimAfterTheFinalBeganDoesntMoveTheBoundary() {
        val state = twoSegments(
            event("message.delta", """{"text":"\n\n$final"}"""),
            event("message.interim", """{"text":"Note: logged too.","already_streamed":false}"""),
            event("message.complete", """{"text":"$final","status":"complete"}"""),
        )
        assertEquals(1, state.replyText().count(final), state.replyText())
    }

    @Test
    fun crlfLineEndsMatchTheFinal() {
        val state = start.apply(
            event("message.start", "{}"),
            *tool("c1", "terminal"),
            event("message.delta", """{"text":"Line one\r\nLine tw"}"""),
            event("message.complete", """{"text":"Line one\nLine two","status":"complete"}"""),
        )
        assertEquals("Line one\nLine two", state.replyText())
    }

    @Test
    fun aCumulativeFinalWithCrlfDropsTheEarlierSegments() {
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"Step one.\r\nChecked."}"""),
            *tool("c1", "terminal"),
            event("message.complete", """{"text":"Step one.\nChecked.\n\nAll done.","status":"complete"}"""),
        )
        assertEquals("Step one.\r\nChecked.\n\nAll done.", state.replyText())
    }

    @Test
    fun anInterimAfterAStreamedFinalWithNoKnownStartDoesntDoubleIt() {
        val state = start.apply(event("message.start", "{}"), *tool("c1", "terminal"))
            .withInflight("$before\n\n")
            .apply(
                event("message.delta", """{"text":"$final"}"""),
                event("message.interim", """{"text":"Note: logged too.","already_streamed":false}"""),
                event("message.complete", """{"text":"$final","status":"complete"}"""),
            )
        assertEquals(1, state.replyText().count(final), state.replyText())
    }

    @Test
    fun aPreviewedFinalSealedByASteerOpensNoSecondCopy() {
        val parts = start.apply(
            event("message.start", "{}"),
            *tool("c2", "terminal"),
            event("message.interim", """{"text":"$final","already_streamed":false}"""),
        ).let { it.copy(messages = it.messages + ChatMessage.User("s", "and log it")) }.sealReplyBefore("s")
            .apply(event("message.complete", """{"text":"$final","status":"complete","response_previewed":true}"""))
            .messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals(1, parts.size, parts.map { it.text }.toString())
    }

    @Test
    fun aPartlyStreamedFinalOfSeveralParagraphsWithNoKnownStartShowsOnce() {
        val state = ChatState(messages = listOf(ChatMessage.User("u", "go")))
            .withInflight("Earlier.\n\nP1 para.\n\nP2 para.")
            .apply(event("message.complete", """{"text":"P1 para.\n\nP2 para.\n\nP3 para.","status":"complete"}"""))
        assertEquals("Earlier.\n\nP1 para.\n\nP2 para.\n\nP3 para.", state.replyText())
    }

    @Test
    fun aSteerThenAnInterimKeepsTheCarriedStart() {
        val parts = twoSegments(event("message.delta", """{"text":"\n\nDone: the fi"}"""))
            .let { it.copy(messages = it.messages + ChatMessage.User("s", "and log it")) }.sealReplyBefore("s")
            .apply(
                event("message.interim", """{"text":"Logging it too.","already_streamed":false}"""),
                event("message.complete", """{"text":"$final","status":"complete"}"""),
            ).messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals(0, parts.last().text.count("Done: the fi"), parts.last().text)
        assertTrue("le is saved." in parts.last().text, parts.last().text)
    }

    @Test
    fun anUnflaggedFinalEqualToTheSegmentBeforeTheLastToolStillShows() {
        val state = twoSegments(event("message.complete", """{"text":"$middle","status":"complete"}"""))
        assertEquals("$before\n\n$middle\n\n$middle", state.replyText())
    }

    @Test
    fun aFilledInFinalIsItsOwnParagraphAfterAListItem() {
        val state = start.apply(
            event("message.start", "{}"),
            event("message.delta", """{"text":"- checked the file\n"}"""),
            *tool("c1", "terminal"),
            event("message.complete", """{"text":"Done.","status":"complete"}"""),
        )
        assertEquals("- checked the file\n\nDone.", state.replyText())
    }

    @Test
    fun aSteerBeforeTheFinalShowsTheWholeFinalBelow() {
        val parts = steeredMidFinal(0)
        assertEquals(2, parts.size)
        assertEquals(final, parts.last().text)
    }
}
