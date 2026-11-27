package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptEventsTest {

    // The row the test gateway stored in test1's Bot Chat after it messaged @hermes (trimmed).
    private val deliveryRow = """[IMPORTANT: Background process proc_acee4d1163fd completed normally (exit code 0).
Command: /opt/hermes/.venv/bin/python3 /opt/hermes/tools/bot_mode_dm.py --run-delivery --author '{"id":"bot:test1","name":"test1","is_bot":true}' query-file /opt/data/profiles/test1/cache/scratch/hermes-dm-568/dm-oxhqd2fp.txt --profile-home /opt/data /opt/hermes/.venv/bin/hermes -p default chat --in '~' -c 'Bot Chat' --create-if-missing -Q
Output:
{"reply": "Status from my end: idle.\n\nDelivery confirmed.", "status": "settled", "delivery_id": "2cc997"}
]"""

    @Test
    fun aDeliveryReceiptSaysWhoAnswered() {
        val event = TranscriptRows.classify(deliveryRow, displayKind = null)?.single()
        assertEquals(TranscriptEvent.Delivery("hermes", DeliveryOutcome.Replied("Status from my end: idle.\n\nDelivery confirmed.")), event)
    }

    @Test
    fun deliveryOutcomesInEachShape() {
        fun outcome(output: String, failed: Boolean = false) = TranscriptRows.deliveryOutcome(output, failed)
        assertIs<DeliveryOutcome.Waiting>(outcome("""{"status":"queued","delivery_id":"d","detail":"Delivery remains pending"}"""))
        assertEquals(
            DeliveryOutcome.Failed("target_busy", "Delivery failed: @x's Bot Chat is open on another surface"),
            outcome("""{"error":"Delivery failed: @x's Bot Chat is open on another surface","reason":"target_busy"}""", failed = true),
        )
        assertEquals("their chat is open elsewhere", (outcome("""{"error":"e","reason":"target_busy"}""") as DeliveryOutcome.Failed).label)
        assertEquals(DeliveryOutcome.Replied("hi"), outcome("Reply from Mac Mini:\nhi"))
        assertEquals("delivery_timeout", (outcome("Delivery to Mac Mini failed [reason: delivery_timeout]: slow") as DeliveryOutcome.Failed).reason)
        assertIs<DeliveryOutcome.Waiting>(outcome("No reply from Mac Mini within 300s. The message may still be processed; do not resend blindly."))
        // A plain CLI turn prints the reply, maybe with its session id.
        assertEquals(DeliveryOutcome.Replied("Done."), outcome("session_id: 2026_x\nDone."))
        assertEquals(DeliveryOutcome.Replied("A fact."), outcome("A fact.\n\n↻ Resumed session 20261005_005623 (Bot Chat)"))
        assertEquals(
            DeliveryOutcome.Replied("No further action needed."),
            outcome("No further action needed. Session 20261005023510 found but has no messages. Starting fresh."),
        )
        assertEquals(DeliveryOutcome.NoReply, outcome(""))
        assertEquals(DeliveryOutcome.NoReply, outcome("(no reply)"))
    }

    @Test
    fun aDeliveryIsNamedByItsAckWhenTheCommandDoesntSay() {
        val row = "[IMPORTANT: Background process proc_9 completed normally (exit code 0).\nCommand: python runner.py\nOutput:\nsure]"
        val event = TranscriptRows.classify(row, "process_complete", deliveries = mapOf("proc_9" to "scribe"))?.single()
        assertEquals(TranscriptEvent.Delivery("scribe", DeliveryOutcome.Replied("sure")), event)
        assertEquals("proc_9" to "scribe", TranscriptRows.deliveryAck("""{"status":"queued","delivery_id":"d","to":"@scribe","process_id":"proc_9"}"""))
    }

    @Test
    fun otherProcessesFoldToOneLine() {
        val row = "[IMPORTANT: Background process proc_1 exited (exit code 2).\nCommand: npm test\nOutput:\n2 failing]"
        assertEquals(TranscriptEvent.Process("Background process finished: npm test", "2 failing", failed = true), TranscriptRows.classify(row, null)?.single())
        val batch = "[IMPORTANT: 2 background processes completed. Review below.]\n\n" +
            "[IMPORTANT: Background process a completed normally (exit code 0).\nCommand: ls\nOutput:\nx]\n\n" +
            "[IMPORTANT: Background process b completed normally (exit code 0).\nCommand: pwd\nOutput:\ny]"
        assertEquals(2, TranscriptRows.classify(batch, "process_complete")?.size)
    }

    @Test
    fun agentMessagesRoutinesAndLabels() {
        assertEquals(
            TranscriptEvent.FromBot("hermes", "hermes", "Quick status check"),
            TranscriptRows.classify("Message from 🤖 hermes (@hermes): Quick status check", null)?.single(),
        )
        assertEquals(
            TranscriptEvent.Routine("Morning brief", "3 new emails"),
            TranscriptRows.classify("[Cronjob \"Morning brief\" output — scheduled job, not the user. Review it, act on anything that needs action, and summarize for the chat.]\n\n3 new emails", null)?.single(),
        )
        assertEquals(TranscriptEvent.Routine("Tickets", "found 2"), TranscriptRows.classify("[Cron delivery: Tickets]\nfound 2", null)?.single())
        assertEquals(TranscriptEvent.Label("Model changed"), TranscriptRows.classify("switched", "model_switch")?.single())
        assertNull(TranscriptRows.classify("What's the weather?", null))
        // A prompt that merely talks about these stays the user's.
        assertNull(TranscriptRows.classify("Why did the [IMPORTANT: Background process thing show up?", null))
    }

    @Test
    fun onlyKnownRoutineEnvelopesEscapeInternalNotificationFiltering() {
        listOf(
            "[Cronjob \"Backup Watch\" output — scheduled job, not the user. Review it, act on anything that needs action, and summarize for the chat.]\r\n\r\nScan complete.",
            "[Cron delivery: Вестник]\nResults\r\n```text\r\nready\r\n```",
            "[Cron delivery: Empty]",
        ).forEach { text ->
            assertFalse(TranscriptRows.isNoise(text, "internal_notification"), text)
            assertIs<TranscriptEvent.Routine>(TranscriptRows.classify(text, "internal_notification")?.single())
        }
        listOf(
            "A cron finished", "Please explain [Cron delivery: Backup]",
            "> [Cron delivery: Backup]\nQuoted output",
            "```text\n[Cron delivery: Backup]\n```",
            "[Cronjob Backup output — malformed]", "[Cron delivery: ]\nMissing name",
            "[System: cron finished]", "[Background process p1 heartbeat #3 — cron check]",
            "[IMPORTANT: Background process p1 completed normally (exit code 0).\nCommand: scan\nOutput:\nready]",
        ).forEach { assertTrue(TranscriptRows.isNoise(it, "internal_notification"), it) }
    }

    @Test
    fun noiseAndSilence() {
        assertTrue(TranscriptRows.isNoise("[System: compacted]", null))
        assertTrue(TranscriptRows.isNoise("[Background process p1 heartbeat #3 — still running", null))
        assertTrue(TranscriptRows.isNoise("wake", "internal_notification"))
        assertFalse(TranscriptRows.isNoise("hello", null))
        listOf("[SILENT]", "NO_REPLY", " no reply ", "*NO_REPLY*", ".NO_REPLY", "静默").forEach { assertTrue(TranscriptRows.isSilence(it), it) }
        listOf("", "I'll stay [SILENT] for now", "SILENTLY").forEach { assertFalse(TranscriptRows.isSilence(it), it) }
    }

    @Test
    fun historyFoldsRepliesToOtherBotsAndHidesSilence() {
        fun row(id: Long, role: String, text: String, toolCalls: String? = null, toolCallId: String? = null, kind: String? = null) = SessionMessage(
            id = id, role = role, content = JsonPrimitive(text), toolCalls = toolCalls?.let(HermesJson::parseToJsonElement),
            toolCallId = toolCallId, displayKind = kind,
        )
        val messages = historyToMessages(
            listOf(
                row(1, "user", "Message from 🤖 hermes (@hermes): how is it going?"),
                row(2, "assistant", "All idle here."),
                row(3, "user", "Message from 🤖 scribe (@scribe): ping"),
                row(4, "assistant", "NO_REPLY"),
                row(5, "user", "Ask scribe for the draft"),
                row(6, "assistant", "", toolCalls = """[{"id":"c1","function":{"name":"message_agent","arguments":"{\"target\":\"@scribe\",\"message\":\"draft?\"}"}}]"""),
                row(7, "tool", """{"status":"queued","delivery_id":"d","to":"@scribe","process_id":"proc_5"}""", toolCallId = "c1"),
                row(8, "user", "Message from 🤖 scribe (@scribe): here it is"),
                row(9, "assistant", "Scribe sent the draft."),
                row(10, "assistant", "Your request was not processed.", kind = "failed_turn"),
            ),
        )
        val replies = messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals("hermes", replies[0].repliedTo)
        // The silent answer to scribe's ping shows nothing at all.
        assertFalse(replies.any { it.text == "NO_REPLY" })
        // Scribe answering this bot's own question isn't folded away: it's what the person asked for.
        assertNull(replies.last { it.text == "Scribe sent the draft." }.repliedTo)
        assertIs<TranscriptEvent.FailedTurn>((messages.last() as ChatMessage.Event).event)
        assertEquals(3, messages.count { (it as? ChatMessage.Event)?.event is TranscriptEvent.FromBot })
    }

    @Test
    fun aReplyAfterANoReplyMessageIsntFoldedAsAnAnswerToTheSender() {
        // Hermes sends nothing back after "[no reply needed]", so what follows is this bot's own text.
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("Message from 🤖 Scribe (@scribe): [no reply needed] deploy finished")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive("Noted: the deploy is out.")),
            ),
        )
        assertEquals(TranscriptEvent.FromBot("Scribe", "scribe", "deploy finished", noReplyNeeded = true), messages.filterIsInstance<ChatMessage.Event>().single().event)
        assertNull(messages.filterIsInstance<ChatMessage.Assistant>().single().repliedTo)

        // A reply still owed to a bot that asked first stays owed to it.
        val owed = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("Message from 🤖 Scribe (@scribe): is the deploy out?")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("Message from 🤖 Pager (@pager): [no reply needed] deploy finished")),
                SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("Yes, it's out.")),
            ),
        )
        assertEquals("Scribe", owed.filterIsInstance<ChatMessage.Assistant>().single().repliedTo)
    }

    /**
     * A cron job delivering to `origin` in a local (Webapp) chat, as hermes-agent 0.22.6+ writes it
     * (gateway/session_completion_notes.py `note_if_unviewed`) and `GET /api/sessions/{id}/messages` serves it:
     * a `user` row typed `internal_notification`, its producer only in `display_metadata.completion_note`,
     * whose entries are JSON-encoded `[producer, route, id]` lists. Ids are placeholders.
     */
    private fun noteRow(id: Long, content: String, metadata: String? = """{"completion_note": ["[\"cron\",\"route-1\",\"0123abcd\"]"], "completion_admission": "adm-1"}""") =
        HermesJson.decodeFromString<SessionMessage>(
            """{"id": $id, "session_id": "sess-1", "role": "user", "content": ${HermesJson.encodeToString(content)},
               "timestamp": 1700000000.0, "active": 1, "compacted": 0, "display_kind": "internal_notification"
               ${metadata?.let { """, "display_metadata": $it""" }.orEmpty()}}""",
        )

    private fun event(rows: List<SessionMessage>) =
        assertIs<TranscriptEvent.Routine>(assertIs<ChatMessage.Event>(historyToMessages(rows).single()).event)

    private val wrapped = "Cronjob Response: Test job\n(job_id: job-1)\n-------------\n\nTest notification\n\n" +
        "To stop or manage this job, send me a new message (e.g. \"stop reminder Test job\")."

    @Test
    fun aCronResultDeliveredToItsChatShowsWithItsRawText() {
        // `wrap_response: false`: the output alone, so the job has no name to show.
        assertEquals(TranscriptEvent.Routine(null, "Test notification", result = true), event(listOf(noteRow(2, "Test notification"))))
    }

    @Test
    fun aWrappedCronResultShowsTheJobNameAndOnlyItsOutput() {
        assertEquals(TranscriptEvent.Routine("Test job", "Test notification", result = true), event(listOf(noteRow(2, wrapped))))
        // CRLF line ends and an empty output.
        val empty = "Cronjob Response: Test job\r\n(job_id: job-1)\r\n-------------\r\n\r\n\r\n\r\n" +
            "To stop or manage this job, send me a new message (e.g. \"stop reminder Test job\")."
        assertEquals(TranscriptEvent.Routine("Test job", "", result = true), event(listOf(noteRow(2, empty))))
    }

    @Test
    fun aCronFailureNoticeShowsAsFailed() {
        val row = noteRow(2, wrapped, """{"completion_note": ["[\"cron\",\"route-1\",\"0123abcd\"]"], "notification_category": "diagnostic"}""")
        assertEquals(TranscriptEvent.Routine("Test job", "Test notification", failed = true, result = true), event(listOf(row)))
    }

    @Test
    fun metadataSentAsAJsonStringReadsTheSame() {
        val row = noteRow(2, "Test notification", HermesJson.encodeToString("""{"completion_note": ["[\"cron\",\"route-1\",\"0123abcd\"]"]}"""))
        assertEquals(TranscriptEvent.Routine(null, "Test notification", result = true), event(listOf(row)))
    }

    @Test
    fun cronResultsAreNotFoldedIntoSystemMessages() {
        val rows = listOf(noteRow(2, "First"), noteRow(3, "Second"))
        val messages = historyToMessages(rows)
        assertEquals(listOf("First", "Second"), messages.map { assertIs<TranscriptEvent.Routine>(assertIs<ChatMessage.Event>(it).event).body })
    }

    @Test
    fun internalRowsFromOtherProducersStayHidden() {
        val hidden = listOf(
            // A watch note, and a note that folds a watch match into a cron one.
            noteRow(2, "Pattern matched", """{"completion_note": ["[\"watch_match\",\"route-1\",\"ab\"]"]}"""),
            noteRow(3, "Test notification", """{"completion_note": ["[\"cron\",\"route-1\",\"ab\"]", "[\"watch_match\",\"route-1\",\"cd\"]"]}"""),
            // Nothing names a producer.
            noteRow(4, "Test notification", """{"completion_note": []}"""),
            noteRow(5, "Test notification", """{"completion_note": ["not json"]}"""),
            noteRow(6, "Test notification", """{"completion_note": "cron"}"""),
            noteRow(7, "Test notification", "\"not json\""),
            // A wake turn or heartbeat card: metadata without a note, raw text.
            noteRow(8, "Internal wake", """{"display_text": "Still running"}"""),
            noteRow(9, "Internal wake", null),
            // Raw cron output where the route leaves the metadata out can't be told from a wake.
            noteRow(10, "Test notification", null),
            // A header without cron's whole wrapper.
            noteRow(11, "Cronjob Response: Test job\n(job_id: job-1)\n-------------\n\nTest notification", null),
        )
        hidden.forEach { row ->
            assertTrue(TranscriptRows.isNoise(row.text.trim(), row.displayKind, row.displayMetadata), "row ${row.id} must stay hidden")
            assertTrue(historyToMessages(listOf(row)).isEmpty(), "row ${row.id} must stay hidden")
        }
    }

    @Test
    fun withoutMetadataOnlyCronsWholeWrapperShows() {
        // The API server's route, and rows cached before display_metadata was kept.
        assertEquals(TranscriptEvent.Routine("Test job", "Test notification", result = true), event(listOf(noteRow(2, wrapped, null))))
        assertEquals(TranscriptEvent.Routine("Test job", "Test notification", result = true), event(listOf(noteRow(2, wrapped, "null"))))
    }

    @Test
    fun theOtherCronFormatsStillShowAsBefore() {
        val mirror = event(listOf(noteRow(2, "[Cron delivery: Test job]\nTest notification", null)))
        assertEquals(TranscriptEvent.Routine("Test job", "Test notification"), mirror)
    }

    @Test
    fun displayMetadataSurvivesARoundTrip() {
        // The offline cache stores the decoded row, so the note must still be a note when read back.
        val row = noteRow(2, "Test notification")
        val back = HermesJson.decodeFromString<SessionMessage>(HermesJson.encodeToString(row))
        assertEquals(row, back)
        assertEquals(TranscriptEvent.Routine(null, "Test notification", result = true), event(listOf(back)))
    }
}
