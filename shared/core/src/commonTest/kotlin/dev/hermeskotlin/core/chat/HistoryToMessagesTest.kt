package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HistoryToMessagesTest {

    @Test
    fun compactionHandoffsNeverBecomePromptsOrMergeIntoReplies() {
        val summary = "```text\r\nbackground\r\n```"
        val checkpoint = "$COMPACTION_PREFIX\r\n$summary\n\n$SUMMARY_END"
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "assistant", content = JsonPrimitive("Before")),
            SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(checkpoint)),
            SessionMessage(id = 3, role = "user", content = JsonPrimitive("[CONTEXT SUMMARY]: Older format")),
            SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("After")),
        ))
        assertEquals(3, messages.size)
        assertEquals("Before", assertIs<ChatMessage.Assistant>(messages[0]).text)
        val handoffs = assertIs<TranscriptEvent.SystemMessages>(assertIs<ChatMessage.Event>(messages[1]).event)
        // What the summary says, not the instructions to the agent around it.
        assertEquals(listOf(summary, "Older format"), handoffs.entries.map { assertIs<TranscriptEvent.Compaction>(it).body })
        assertEquals("After", assertIs<ChatMessage.Assistant>(messages[2]).text)
    }

    @Test
    fun aPromptStartingWithTheCompactionLabelIsStillAPrompt() {
        val text = "[CONTEXT COMPACTION — REFERENCE ONLY] what does this label mean?"
        val message = historyToMessages(listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive(text)))).single()
        assertEquals(text, assertIs<ChatMessage.User>(message).text)
    }

    @Test
    fun aLoneSystemEntryShowsAsItselfNotAGroupOfOne() {
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("one")),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive(""), displayKind = "model_switch"),
            SessionMessage(id = 3, role = "user", content = JsonPrimitive("two")),
        ))
        assertEquals(TranscriptEvent.Label("Model changed"), assertIs<ChatMessage.Event>(messages[1]).event)
    }

    @Test
    fun consecutiveRoutineAndSuccessfulProcessEventsShareOneDisplayRow() {
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("[Cronjob \"Morning\" output — scheduled job, not the user.]\n\n3 new emails"), timestamp = 10.0),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive("[Cron delivery: Calendar]\n2 meetings"), timestamp = 20.0),
            SessionMessage(id = 3, role = "user", content = JsonPrimitive("[IMPORTANT: Background process p1 completed normally (exit code 0).\nCommand: ls\nOutput:\nnotes]"), displayKind = "process_complete", timestamp = 30.0),
        ))
        assertEquals(1, messages.size)
        val group = assertIs<ChatMessage.Event>(messages.single())
        assertEquals("row-3", group.key)
        assertEquals(30.0, group.timestamp)
        assertEquals("row-1", assertIs<TranscriptEvent.SystemMessages>(group.event).firstKey)
        val entries = assertIs<TranscriptEvent.SystemMessages>(group.event).entries
        assertEquals(listOf("Morning", "Calendar"), entries.take(2).map { assertIs<TranscriptEvent.Routine>(it).name })
        assertEquals("notes", assertIs<TranscriptEvent.Process>(entries.last()).output)
    }

    @Test
    fun failuresOtherBotsAndRealConversationBreakSystemGroups() {
        fun row(id: Long, text: String, role: String = "user", kind: String? = null) =
            SessionMessage(id = id, role = role, content = JsonPrimitive(text), displayKind = kind)
        val routine = "[Cron delivery: Monitor]\nNothing new"
        val messages = historyToMessages(listOf(
            row(1, routine),
            row(2, "[IMPORTANT: Background process p1 exited (exit code 2).\nCommand: scan\nOutput:\nfailed]", kind = "process_complete"),
            row(3, routine),
            row(4, "Message from 🤖 scribe (@scribe): hello"),
            row(5, routine),
            row(6, "Please review the compaction checkpoint and cron messages"),
            row(7, "Here is your summary", "assistant"),
            row(8, routine),
            row(9, "Your request was not processed.", "assistant", "failed_turn"),
        ))
        assertEquals(9, messages.size)
        assertEquals(true, assertIs<TranscriptEvent.Process>(assertIs<ChatMessage.Event>(messages[1]).event).failed)
        assertIs<TranscriptEvent.FromBot>(assertIs<ChatMessage.Event>(messages[3]).event)
        assertIs<ChatMessage.User>(messages[5])
        assertEquals("Here is your summary", assertIs<ChatMessage.Assistant>(messages[6]).text)
        assertIs<TranscriptEvent.FailedTurn>(assertIs<ChatMessage.Event>(messages[8]).event)
    }

    @Test
    fun hiddenScaffoldingStaysHiddenAndDisplayProjectionWins() {
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("[CONTEXT COMPACTION — REFERENCE ONLY] secret"), displayKind = "hidden"),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive("[CONTEXT COMPACTION — REFERENCE ONLY] internal"), displayContent = JsonPrimitive("Actual prompt")),
            SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("")),
            SessionMessage(id = 4, role = "user", content = JsonPrimitive("An example: [CONTEXT COMPACTION — REFERENCE ONLY]")),
        ))
        assertEquals(listOf("Actual prompt", "An example: [CONTEXT COMPACTION — REFERENCE ONLY]"), messages.filterIsInstance<ChatMessage.User>().map { it.text })
    }

    @Test
    fun exactCompactionRemindersStayOutOfHistoryAndTheOriginalBotMessageRemains() {
        val restatement = "[STILL IN PROGRESS — this is the active request, restated after the " +
            "compaction boundary because it was not finished yet. Continue it; do not start over.]"
        val taskList = "[Your active task list was preserved across context compression]"
        val original = "Message from 🤖 Rook (@rook): Please check the backup."
        val question = "$taskList appears in my chat. What is it?"
        val mention = "Please explain this header:\n$restatement"
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive(original)),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive("$COMPACTION_PREFIX Notes\n$SUMMARY_END\n$restatement\n$original"),
                displayContent = JsonPrimitive("$restatement\n$original")),
            SessionMessage(id = 3, role = "user", content = JsonPrimitive("$taskList\r\n- Check the backup")),
            SessionMessage(id = 4, role = "user", content = JsonPrimitive(question)),
            SessionMessage(id = 5, role = "user", content = JsonPrimitive(mention)),
        ))

        assertEquals(listOf("row-1", "row-4", "row-5"), messages.map { it.key })
        assertEquals(TranscriptEvent.FromBot("Rook", "rook", "Please check the backup."), assertIs<ChatMessage.Event>(messages[0]).event)
        assertEquals(listOf(question, mention), messages.filterIsInstance<ChatMessage.User>().map { it.text })
    }

    @Test
    fun restatedRuntimeRowsKeepTheirOriginalTreatment() {
        val bodies = listOf(
            "[System: wake]",
            "[Background process proc_1 heartbeat #1 progress]",
            "[ASYNC DELEGATION COMPLETE — batch_1]\nStatus: completed\n--- RESULT ---\nDone.",
        )
        val rows = bodies.mapIndexed { index, body ->
            SessionMessage(id = index.toLong(), role = "user", content = JsonPrimitive(body))
        }
        val replays = rows.map { row ->
            row.copy(content = JsonPrimitive("[STILL IN PROGRESS — this is the active request, restated after the " +
                "compaction boundary because it was not finished yet. Continue it; do not start over.]\n${row.text}"))
        }

        val expected = historyToMessages(rows)
        assertIs<ChatMessage.Notice>(expected.single())
        assertEquals(expected, historyToMessages(replays))
    }

    @Test
    fun compactionRestatementKeepsTheRequestUntilItsOriginalIsInHistory() {
        val original = SessionMessage(id = 1, role = "user", content = JsonPrimitive("Check the backup."), messageUid = "original")
        val restated = JsonPrimitive("[STILL IN PROGRESS — this is the active request, restated after the " +
            "compaction boundary because it was not finished yet. Continue it; do not start over.]\r\nCheck the backup.")
        val replays = listOf(
            original.copy(id = 2, content = restated),
            original.copy(
                id = 3,
                content = JsonPrimitive("$COMPACTION_PREFIX Notes\n$SUMMARY_END\n${restated.content}"),
                displayContent = restated,
                messageUid = "carrier",
                absorbedMessageUids = JsonPrimitive("""["original"]"""),
            ),
        )
        val earlierSameText = original.copy(id = 0, messageUid = "earlier")
        for (replay in replays) {
            val onlyCopy = assertIs<ChatMessage.User>(historyToMessages(listOf(replay)).single())
            assertEquals(original.text, onlyCopy.text)
            assertEquals(null, onlyCopy.rowId)
            assertEquals(listOf("row-0", onlyCopy.key), historyToMessages(listOf(earlierSameText, replay)).map { it.key })
            assertEquals(listOf("row-1"), historyToMessages(listOf(original, replay)).map { it.key })
            assertEquals(listOf("row-1"), historyToMessages(listOf(original.copy(messageUid = null), replay)).map { it.key })
        }
        val carrierWithoutSourceUid = replays.last().copy(absorbedMessageUids = JsonPrimitive("[]"))
        assertEquals(listOf("row-1"), historyToMessages(listOf(original, carrierWithoutSourceUid)).map { it.key })
    }

    @Test
    fun aTaskListCompactionAppendsToAPromptIsCutAndAFlaggedRestatementShowsOnlyAsTheSoleCopy() {
        // As v0.23.4 stores them: the list folded into the last real user row, which can be the restated request.
        val taskList = "[Your active task list was preserved across context compression]\n" +
            "- [>] 1. Draft the release notes (in_progress)\n- [ ] 2. Check the backup (pending)"
        // agent/conversation_compression.py `_pruned_skill_reload_notice`, after the list.
        val reload = "[Skills pruned during compression — reload before acting on these tasks]\nThe task list above " +
            "crossed the compression boundary verbatim, but the skill instructions that governed it were pruned."
        val pasted = "Why does this show?\n[Your active task list was preserved across context compression]\n- x"
        val restated = SessionMessage(id = 3, role = "user", content = JsonPrimitive(
            "[STILL IN PROGRESS — this is the active request, restated after the compaction boundary because it was " +
                "not finished yet. Continue it; do not start over.]\nDraft them\n\n$taskList"),
            displayMetadata = buildJsonObject { put("compaction_inflight_task_restatement", true) })
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("Hello three\n\n$taskList")),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive("Crlf too\r\n\r\n${taskList.replace("\n", "\r\n")}\r\n\r\n$reload")),
            restated,
            SessionMessage(id = 4, role = "user", content = JsonPrimitive(pasted),
                displayMetadata = buildJsonObject { put("compaction_inflight_task_restatement", false) }),
        ))

        val prompts = messages.filterIsInstance<ChatMessage.User>()
        // The restated request is the only copy loaded, so it shows (without its header and the list).
        assertEquals(listOf("Hello three", "Crlf too", "Draft them", pasted), prompts.map { it.text })
        // Sending a prompt again sends what the person wrote, not the list.
        assertEquals("Hello three", prompts[0].sentText)
        // With its original loaded, the restatement is left out.
        val original = SessionMessage(id = 0, role = "user", content = JsonPrimitive("Draft them"))
        assertEquals(listOf("row-0"), historyToMessages(listOf(original, restated)).map { it.key })
    }

    @Test
    fun aTaskListPartBesideAPictureIsCutAndThePictureStays() {
        // A prompt with a picture gets the list as a text part of its own, with no blank line before it.
        val taskList = "[Your active task list was preserved across context compression]\n- [>] 1. Draft the release notes (in_progress)"
        val image = buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64,AAAA") }) }
        fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = buildJsonArray { add(text("Describe this")); add(image); add(text(taskList)) }),
            SessionMessage(id = 2, role = "user", content = buildJsonArray { add(image); add(text(taskList)) }),
            SessionMessage(id = 3, role = "user", content = buildJsonArray { add(text("Quoting:")); add(text("[Your active task list was preserved across context compression]")) }),
        ))

        val prompts = messages.filterIsInstance<ChatMessage.User>()
        assertEquals(listOf("Describe this", "", "Quoting:\n[Your active task list was preserved across context compression]"), prompts.map { it.text })
        assertEquals(listOf(1, 1, 0), prompts.map { it.attachments.size })
    }

    @Test
    fun aTaskListOnABotsMessageIsCutAndAPromptOpeningWithAHeaderStays() {
        val taskList = "[Your active task list was preserved across context compression]\n- [ ] 1. Check the backup (pending)"
        val opensWithHeader = "[Your active task list was preserved across context compression]\nWhat is this?"
        val messages = historyToMessages(listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("Message from 🤖 Rook (@rook): Please check the backup.\n\n$taskList")),
            SessionMessage(id = 2, role = "user", content = JsonPrimitive(opensWithHeader)),
            SessionMessage(id = 3, role = "user", content = JsonPrimitive(
                "[Skills pruned during compression — reload before acting on these tasks]\nThe task list above crossed the " +
                    "compression boundary verbatim, but the skill instructions that governed it were pruned.")),
        ))

        assertEquals(TranscriptEvent.FromBot("Rook", "rook", "Please check the backup."), assertIs<ChatMessage.Event>(messages[0]).event)
        assertEquals(listOf(opensWithHeader), messages.filterIsInstance<ChatMessage.User>().map { it.text })
        assertEquals(2, messages.size)
    }

    @Test
    fun regroupingHistoryKeepsContentAndChronologyAsOlderRowsArrive() {
        val text = "| Name | Result |\r\n|---|---|\r\n| scan | ${"word".repeat(200)} |\r\n\r\n```text\r\nready\r\n```"
        val older = SessionMessage(id = 1, role = "user", content = JsonPrimitive("[Cron delivery: Earlier]\n"))
        val newer = SessionMessage(id = 2, role = "user", content = JsonPrimitive("[Cron delivery: Latest]\n$text"))
        val loaded = historyToMessages(listOf(newer))
        val reloaded = historyToMessages(listOf(older, newer))
        assertEquals(1, loaded.size)
        assertEquals(1, reloaded.size)
        // Same key before and after the earlier page, so the list keeps its place.
        assertEquals(loaded.single().key, reloaded.single().key)
        assertEquals(reloaded, historyToMessages(listOf(older, newer)))
        val entries = assertIs<TranscriptEvent.SystemMessages>(assertIs<ChatMessage.Event>(reloaded.single()).event).entries
        assertEquals(listOf("", text), entries.map { assertIs<TranscriptEvent.Routine>(it).body })
    }

    @Test
    fun aLegacyCompactionCarrierWithALiveAskRemainsARealPrompt() {
        val text = "$COMPACTION_PREFIX Old context\n\n$SUMMARY_END\n\nPlease check the backup."
        val message = historyToMessages(listOf(SessionMessage(id = 9, role = "user", content = JsonPrimitive(text)))).single()
        assertEquals(text, assertIs<ChatMessage.User>(message).text)
        assertEquals(9L, message.rowId)
    }

    @Test
    fun internalCronInputsSeparateTheirRepliesWhileOtherInternalRowsStayHidden() {
        listOf(
            "[Cronjob \"Backup Watch\" output — scheduled job, not the user. Review it, act on anything that needs action, and summarize for the chat.]\n\nScan complete.",
            "[Cron delivery: Backup Watch]\nScan complete.",
        ).forEach { cron ->
            val messages = historyToMessages(listOf(
                SessionMessage(id = 1, role = "assistant", content = JsonPrimitive("Previous reply")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive(cron), displayKind = "internal_notification"),
                SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("Cron response")),
                SessionMessage(id = 4, role = "user", content = JsonPrimitive("Internal bookkeeping mentioning cron"), displayKind = "internal_notification"),
                SessionMessage(id = 5, role = "user", content = JsonPrimitive(cron), displayKind = "hidden"),
            ))
            assertEquals(3, messages.size)
            assertEquals("Previous reply", assertIs<ChatMessage.Assistant>(messages[0]).text)
            // A lone system entry shows as itself, not as a group of one.
            val routine = assertIs<TranscriptEvent.Routine>(assertIs<ChatMessage.Event>(messages[1]).event)
            assertEquals("Backup Watch", routine.name)
            assertEquals("Scan complete.", routine.body)
            assertEquals("Cron response", assertIs<ChatMessage.Assistant>(messages[2]).text)
        }
    }

    private companion object {
        /** The start and end of the handoff the gateway writes (agent/context_compressor.py SUMMARY_PREFIX), shortened. */
        const val COMPACTION_PREFIX = "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below. " +
            "This is a handoff from a previous context window. … The current session state (files, config, etc.) " +
            "may reflect work described here — avoid repeating it:"
        const val SUMMARY_END = "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---"
    }

    private fun toolCall(name: String) = buildJsonArray {
        add(buildJsonObject { put("id", "c-$name"); put("function", buildJsonObject { put("name", name) }) })
    }

    @Test
    fun aStoredCorrectionShowsWhatWasTyped() {
        val stored = "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position]\n" +
            "Actually describe London instead\n[/OUT-OF-BAND USER MESSAGE]"
        val messages = historyToMessages(listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive(stored))))
        assertEquals("Actually describe London instead", (messages.single() as ChatMessage.User).text)
    }

    @Test
    fun toolStepsMergeIntoOneReplyAndToolRowsDropOut() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("list files")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = toolCall("terminal")),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c-terminal"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("There is one file.")),
                SessionMessage(id = 5, role = "user", content = JsonPrimitive("summary"), displayKind = "hidden"),
                SessionMessage(id = 6, role = "system", content = JsonPrimitive("You are Hermes")),
            ),
        )

        assertEquals(
            listOf(
                ChatMessage.User("row-1", "list files", rowId = 1, sentText = "list files"),
                ChatMessage.Assistant(
                    "row-2",
                    text = "There is one file.",
                    tools = listOf(ToolActivity("c-terminal", "terminal", output = "a.txt", completed = true)),
                    reactionRowId = 4,
                ),
            ),
            messages,
        )
    }

    @Test
    fun promptsKeepTheirTimeAndAMergedReplyTakesItsLastStepsTime() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("list files"), timestamp = 1_000.0),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = toolCall("terminal"), timestamp = 1_002.0),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c-terminal", timestamp = 1_003.0),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("One file."), timestamp = 1_009.5),
                // A step stored without a time leaves the reply dated by the one before.
                SessionMessage(id = 5, role = "assistant", content = JsonPrimitive("Done.")),
            ),
        )
        assertEquals(1_000.0, (messages[0] as ChatMessage.User).timestamp)
        assertEquals(1_009.5, (messages[1] as ChatMessage.Assistant).timestamp)
    }

    @Test
    fun aNewUserPromptStartsANewReply() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "assistant", content = JsonPrimitive("Hi")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("Hello")),
                SessionMessage(id = 3, role = "assistant", content = JsonPrimitive("How can I help?")),
            ),
        )
        assertEquals(3, messages.size)
    }

    @Test
    fun storedPromptsShowTheirImagesAndFileReferencesAsAttachments() {
        val parts = Json.parseToJsonElement(
            """[{"type":"text","text":"What is this?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"}}]""",
        )
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = parts),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("@file:attachments/notes.txt\n@file:\"attachments/my spec.pdf\"\n\nsummarise both")),
                SessionMessage(id = 3, role = "user", content = JsonPrimitive("Describe this\n@image:/opt/data/images/upload_1.jpg")),
                SessionMessage(
                    id = 4,
                    role = "user",
                    content = JsonPrimitive(
                        "@file:attachments/notes.txt\n\nWhat is on this list\n\n--- Attached Context ---\n\n" +
                            "📄 @file:attachments/notes.txt (12 tokens)\n```\nmilk, eggs\n```",
                    ),
                ),
            ),
        )
        val expanded = messages[3] as ChatMessage.User
        assertEquals("What is on this list", expanded.text)
        // The gateway appends the images' directives after the pasted context (session_history.py).
        val both = historyToMessages(
            listOf(
                SessionMessage(
                    id = 5,
                    role = "user",
                    content = JsonPrimitive(
                        "@file:attachments/screen.txt\n\nWhat's this?\n\n--- Attached Context ---\n\n" +
                            "📄 @file:attachments/screen.txt (40 tokens)\n```\n@image:not/a/directive.jpg\n```\n" +
                            "@image:/opt/data/images/upload_2.jpg",
                    ),
                ),
            ),
        ).single() as ChatMessage.User
        assertEquals("What's this?", both.text)
        assertEquals(listOf("screen.txt", "upload_2.jpg"), both.attachments.map { it.name })
        assertEquals("/opt/data/images/upload_2.jpg", both.attachments.last().gatewayPath)
        assertEquals(listOf("notes.txt"), expanded.attachments.map { it.name })
        val stored = messages[2] as ChatMessage.User
        assertEquals("Describe this", stored.text)
        assertEquals("/opt/data/images/upload_1.jpg", stored.attachments.single().gatewayPath)
        assertEquals("upload_1.jpg", stored.attachments.single().name)
        val image = messages[0] as ChatMessage.User
        assertEquals("What is this?", image.text)
        assertEquals(listOf(AttachmentKind.Image), image.attachments.map { it.kind })
        val files = messages[1] as ChatMessage.User
        assertEquals("summarise both", files.text)
        assertEquals(listOf("notes.txt", "my spec.pdf"), files.attachments.map { it.name })
        assertEquals(listOf(AttachmentKind.File, AttachmentKind.Pdf), files.attachments.map { it.kind })
    }

    @Test
    fun onlyPromptsTheLiveAgentHoldsCanBeCutAt() {
        val rows = Json.decodeFromString<List<SessionMessage>>(
            """[
            {"id":1,"session_id":"old","role":"user","content":"before the compression","active":0},
            {"id":2,"session_id":"old","role":"assistant","content":"ok","active":0},
            {"id":3,"session_id":"tip","role":"user","content":"summarized","active":0,"compacted":1},
            {"id":4,"session_id":"tip","role":"user","content":"now","active":1,"compacted":0}
            ]""",
        )

        val prompts = historyToMessages(rows, liveSessionId = "tip").filterIsInstance<ChatMessage.User>()

        assertEquals(listOf(null, null, 4L), prompts.map { it.rowId })
        // An ancestor's row stays out even when it reads as active.
        val ancestor = SessionMessage(id = 9, role = "user", content = JsonPrimitive("hi"), sessionId = "old")
        assertEquals(null, (historyToMessages(listOf(ancestor), liveSessionId = "tip").single() as ChatMessage.User).rowId)
    }

    @Test
    fun whatWentOutIsKeptUnlessFilesRodeAlong() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("plain")),
                SessionMessage(id = 2, role = "user", content = JsonPrimitive("@file:notes.txt\n\nread it")),
            ),
        ).filterIsInstance<ChatMessage.User>()

        assertEquals("plain", messages[0].sentText)
        assertEquals(1L, messages[0].rowId)
        assertEquals(null, messages[1].sentText)
    }

    @Test
    fun incomingBotMessagesRemainVisibleWhenHermesMarksThemInternal() {
        val body = "Please review `draft.md`."
        val rows = listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("wake"), displayKind = "internal_notification"),
            SessionMessage(
                id = 2,
                role = "user",
                content = JsonPrimitive("Message from 🤖 Scribe (@scribe): $body"),
                displayKind = "internal_notification",
                timestamp = 200.0,
            ),
            SessionMessage(
                id = 3,
                role = "user",
                content = JsonPrimitive("[Message from agent 'scribe'] Done."),
                displayKind = "internal_notification",
                timestamp = 210.0,
            ),
            SessionMessage(id = 4, role = "user", content = JsonPrimitive("Message from the scheduler: wake"), displayKind = "internal_notification"),
            SessionMessage(id = 5, role = "user", content = JsonPrimitive("Message from 🤖 Scribe: wake"), displayKind = "internal_notification"),
            SessionMessage(id = 6, role = "user", content = JsonPrimitive("Message from Scribe (@scribe): wake"), displayKind = "internal_notification"),
        )

        val messages = historyToMessages(rows).map { assertIs<ChatMessage.Event>(it) }
        assertEquals(
            listOf(TranscriptEvent.FromBot("Scribe", "scribe", body), TranscriptEvent.FromBot("scribe", null, "Done.")),
            messages.map { it.event },
        )
        assertEquals(listOf(200.0, 210.0), messages.map { it.timestamp })
    }

    @Test
    fun scheduledResultWithPlainSenderTextRemainsARoutine() {
        val body = "Message from the scheduler: backup complete"
        val row = SessionMessage(
            id = 1,
            role = "user",
            content = JsonPrimitive(body),
            displayKind = "internal_notification",
            displayMetadata = Json.parseToJsonElement("""{"completion_note":[["cron","local","job"]]}"""),
        )

        val message = assertIs<ChatMessage.Event>(historyToMessages(listOf(row)).single())
        assertEquals(body, assertIs<TranscriptEvent.Routine>(message.event).body)
    }

    @Test
    fun scheduledResultWithCompactionReminderHeaderRemainsARoutine() {
        val body = "[Your active task list was preserved across context compression]\n- Check the backup"
        val row = SessionMessage(
            id = 1,
            role = "user",
            content = JsonPrimitive(body),
            displayKind = "internal_notification",
            displayMetadata = Json.parseToJsonElement("""{"completion_note":[["cron","local","job"]]}"""),
        )

        val message = assertIs<ChatMessage.Event>(historyToMessages(listOf(row)).single())
        assertEquals(body, assertIs<TranscriptEvent.Routine>(message.event).body)
    }

    @Test
    fun aTaskListFoldedIntoAScheduledResultIsCut() {
        val row = SessionMessage(
            id = 1,
            role = "user",
            content = JsonPrimitive("Backup done.\n\n[Your active task list was preserved across context compression]\n- [ ] 1. Check (pending)"),
            displayKind = "internal_notification",
            displayMetadata = Json.parseToJsonElement("""{"completion_note":[["cron","local","job"]]}"""),
        )

        val message = assertIs<ChatMessage.Event>(historyToMessages(listOf(row)).single())
        assertEquals("Backup done.", assertIs<TranscriptEvent.Routine>(message.event).body)
    }

    @Test
    fun anotherBotsMessageKeepsItsTimeSoTheNewMessagesLineCanFindIt() {
        val rows = listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("Message from 🤖 Scribe (@scribe): the draft is ready"), timestamp = 200.0),
        )

        val event = historyToMessages(rows).single() as ChatMessage.Event

        assertIs<TranscriptEvent.FromBot>(event.event)
        assertEquals(200.0, event.timestamp)
    }
}
