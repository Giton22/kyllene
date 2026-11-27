package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.distinctCommentary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Folds one live session event (tui_gateway/contracts/events.py) into the chat. Events for other
 * sessions must be filtered out by the caller. Unknown event types leave the state unchanged.
 * Events carry no time, so [now] (epoch seconds, when it arrived) stamps the reply it finishes.
 */
fun ChatState.reduce(event: GatewayEvent, now: Double? = null): ChatState {
    val payload = event.payload as? JsonObject
    return retireCompactionDone(event.type, payload).fold(event, payload, now)
}

/**
 * `compacted` ("✓ Context compaction complete — continuing turn...") is a one-off edge: the gateway sends no clear after
 * it, and its own clients drop it once the turn moves on. So the next sign of the turn going on, or the next turn,
 * retires it. Any other kind stays until it is replaced or the turn ends.
 */
private fun ChatState.retireCompactionDone(type: String, payload: JsonObject?): ChatState {
    if (statusKind != COMPACTED_KIND) return this
    val movesOn = when (type) {
        "thinking.delta" -> !payload.string("text").isNullOrBlank()
        "reasoning.delta", "reasoning.available", "message.delta", "message.interim", "tool.start", "tool.generating", "message.start" -> true
        else -> false
    }
    return if (movesOn) withoutCompactionDone() else this
}

/** Drops a `compacted` line, for when the turn is known to have gone on past it (a resume that can't replay what was missed). */
internal fun ChatState.withoutCompactionDone(): ChatState =
    if (statusKind == COMPACTED_KIND) copy(status = null, statusKind = null) else this

private fun ChatState.fold(event: GatewayEvent, payload: JsonObject?, now: Double?): ChatState {
    return when (event.type) {
        // Read from the released state: a missed turn's end it found no longer counts as running.
        "message.start" -> releaseQueued(event.admissionId).let { before ->
            // A reply this opens is a new turn's, which carries nothing from a corrected one whose end went unseen.
            val fresh = if (before.messages.openReplyIndex() >= 0) before else before.copy(responseStart = null)
            fresh.withOpenReply { it }.copy(
                running = true,
                error = null,
                // A new turn hasn't planned yet; the last plan is from before (a correction keeps its turn's plan).
                todosLive = before.todosLive && before.running,
                // No totals yet means the agent isn't built, and a new one counts from zero.
                turnStartUsage = before.turnStartUsage.takeIf { before.running } ?: before.usage ?: SessionUsage(),
            )
        }
        "session.usage" -> copy(usage = SessionUsage.parse(payload?.get("usage") as? JsonObject) ?: usage)
        "message.delta" -> appendText(payload.string("text"))
        "message.interim" ->
            // Text next to tool calls; when already streamed it is in the text already. The event doesn't say whether
            // it's Codex commentary or a step's own text, so it stays text.
            // One added whole after a tool, before any of the response, comes before that response; later, it's part of it.
            if (payload.boolean("already_streamed") == true) {
                this
            } else {
                val atResponseStart = atResponseStart()
                appendText(payload.string("text")?.let { "$it\n\n" }).let { if (atResponseStart) it.markResponseStart(afterTool = false) else it }
            }
        "reasoning.delta", "reasoning.available" -> payload.string("text")?.let { chunk ->
            withOpenReply { it.copy(reasoning = it.reasoning + chunk) }
        } ?: this
        // Not reasoning: each frame is the TUI's spinner rewritten ("(>∀<☆)☆ musing..."), so the latest one
        // labels the live activity. An explained provider wait goes to the status line, like Desktop.
        "thinking.delta" -> payload.string("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { frame ->
            if (PROVIDER_WAIT.containsMatchIn(frame)) copy(status = frame, statusKind = null) else copy(thinkingFrame = frame)
        } ?: this
        "tool.start" -> {
            val id = payload.string("tool_id") ?: return this
            val tool = ToolActivity(
                id = id,
                name = payload.string("name") ?: "tool",
                detail = payload.string("context") ?: payload.string("preview"),
                running = true,
                input = ToolDetails.input(payload?.get("args"), payload.string("args_text")),
                agentScreen = AgentScreen.from(payload.string("name") ?: "", payload?.get("args")),
            ).let { if (it.name == DELEGATE_TOOL) it.copy(tasks = delegatedTasks(payload?.get("args"), null)) else it }
            withOpenReply { reply -> reply.copy(tools = reply.tools.filterNot { it.id == id } + tool) }
                .copy(running = true, thinkingFrame = null)
                .markResponseStart(afterTool = true)
        }
        // The model is still writing the call's arguments; say what's coming.
        "tool.generating" -> payload.string("name")?.let { copy(thinkingFrame = "preparing $it…") } ?: this
        "tool.complete" -> {
            val id = payload.string("tool_id") ?: return this
            val finish = { reply: ChatMessage.Assistant ->
                reply.copy(tools = reply.tools.map {
                    if (it.id != id) it else it.copy(
                        running = false,
                        summary = payload.string("summary"),
                        durationSeconds = payload.double("duration_s"),
                        input = it.input ?: ToolDetails.input(payload?.get("args")),
                        output = ToolDetails.output(payload?.get("result"), payload.string("result_text")),
                        diff = payload.string("inline_diff")?.takeIf { d -> d.isNotBlank() },
                        failed = ToolDetails.failed(payload?.get("result")),
                        tasks = if (it.name != DELEGATE_TOOL) it.tasks else delegatedTasks(
                            args = payload?.get("args"),
                            result = payload?.get("result") ?: payload?.get("result_text") ?: JsonPrimitive(""),
                            knownGoals = it.tasks.map { task -> task.goal },
                        ),
                        delegationIds = if (it.name != DELEGATE_TOOL) it.delegationIds else delegationIds(payload?.get("result") ?: payload?.get("result_text")),
                        generatedImage = if (it.name != IMAGE_GENERATE_TOOL) null else GeneratedImage.from(payload?.get("result")),
                        agentScreen = it.agentScreen ?: AgentScreen.from(it.name, payload?.get("args")),
                    )
                })
            }
            // A correction mid-turn closes the reply a tool started in, so look for the tool first.
            val owner = messages.indexOfLast { it is ChatMessage.Assistant && it.tools.any { tool -> tool.id == id } }
            if (owner >= 0) {
                copy(messages = messages.toMutableList().apply { set(owner, finish(get(owner) as ChatMessage.Assistant)) })
            } else {
                withOpenReply(finish)
            }
        }
        "tool.output_risk" -> {
            val (id, risk) = flaggedOutput(payload) ?: return this
            val owner = messages.indexOfLast { it is ChatMessage.Assistant && it.tools.any { tool -> tool.id == id } }
            if (owner < 0) return this
            val reply = messages[owner] as ChatMessage.Assistant
            copy(messages = messages.toMutableList().apply {
                set(owner, reply.copy(tools = reply.tools.map { if (it.id == id) it.copy(risk = risk) else it }))
            })
        }
        "notice" -> payload.string("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            copy(messages = messages + ChatMessage.Notice("notice-$keySeq", text), keySeq = keySeq + 1)
        } ?: this
        // The turn has ended, so a notice about the agent being built is stale even if its clear never came.
        "message.complete" -> complete(payload, now).withPromptRow(payload?.get("persisted_turn") as? JsonObject).withoutAgentNotices()
        "notification.show" -> showNotice(payload)
        "notification.clear" -> withoutNotice(payload.string("key"))
        "status.update" -> {
            val kind = payload.string("kind")
            val text = payload.string("text")?.takeIf { it.isNotBlank() }
            // "ready" is the gateway's clear (a manual /compress ends with it), not a line to show.
            if (kind == READY_KIND || (kind == "status" && text?.trim() == READY_KIND)) {
                copy(status = null, statusKind = null)
            } else {
                copy(status = text, statusKind = kind.takeIf { text != null })
            }
        }
        // A turn cancelled while the agent was still being built ends with only an error, and no clear.
        "error" -> copy(error = payload.string("message")).withoutAgentNotices()
        "session.title" -> copy(title = payload.string("title") ?: title)
        "session.info" -> withInfo(payload).copy(title = payload.string("title")?.takeIf { it.isNotBlank() } ?: title)
        "btw.complete" -> answerSideTask(payload, "/btw")
        "background.complete" -> answerSideTask(payload, "/bg")
        "todo.updated" -> withTodos(TodoList.parse(payload))
        // Pushed on every mutation, our own actions included; an empty snapshot is a clear, one without a snapshot nothing.
        "session.control.update" -> (payload?.get("control") as? JsonObject)?.let { withControl(SessionControl.parse(it)) } ?: this
        "subagent.spawn_requested", "subagent.start", "subagent.progress", "subagent.thinking", "subagent.tool", "subagent.complete" ->
            withSubagentEvent(event.type, payload)
        else -> this
    }
}

/**
 * A `tool.output_risk` with something found: the call id and why. Every scan of outside content
 * reports, mostly "low"; only a finding is worth a badge.
 */
internal fun flaggedOutput(payload: JsonObject?): Pair<String, ToolRisk>? {
    val id = payload.string("tool_id")?.takeIf { it.isNotEmpty() } ?: return null
    if (payload.string("risk") != "high") return null
    return id to ToolRisk(
        findings = (payload?.get("findings") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        redacted = payload.boolean("redacted") == true,
    )
}

/** Takes a plan snapshot unless an older one arrived late. */
internal fun ChatState.withTodos(list: TodoList?): ChatState {
    if (list == null) return this
    val current = todos
    if (current != null && list.revision < current.revision) return this
    return copy(todos = list, todosLive = running)
}

/**
 * Takes a control snapshot, keeping the same reference when its revision matches: an unchanged
 * revision means identical state, so nothing should recompose for it.
 */
internal fun ChatState.withControl(next: SessionControl?): ChatState {
    val current = control
    if (next == null) return if (current == null) this else copy(control = null)
    if (current != null && next.revision.isNotEmpty() && next.revision == current.revision) return this
    return copy(control = next)
}

/**
 * A side task's answer (`btw.complete` for `/btw`, `background.complete` for `/bg`): fills the card that
 * started it, or adds one under [command] when it was started from another client. The gateway reports a
 * task that crashed as `error: <why>` in the same text.
 */
private fun ChatState.answerSideTask(payload: JsonObject?, command: String): ChatState {
    val text = payload.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    return answerSideTask(payload.string("task_id"), text, command, failed = text.startsWith(SIDE_TASK_ERROR), asked = payload.string("question"))
}

/** Puts [text] on the card for [taskId], or on a new one under [command]; [asked] is a `/btw`'s question. */
internal fun ChatState.answerSideTask(taskId: String?, text: String, command: String, failed: Boolean = false, asked: String? = null): ChatState {
    val index = messages.indexOfFirst { it is ChatMessage.Command && it.taskId != null && it.taskId == taskId }
    if (index >= 0) {
        val card = messages[index] as ChatMessage.Command
        return copy(messages = messages.toMutableList().apply { set(index, card.copy(output = text, running = false, failed = failed)) })
    }
    // Only /btw's answer repeats what was asked.
    val card = ChatMessage.Command("side-$keySeq", "$command ${asked?.trim().orEmpty()}".trimEnd(), text, running = false, failed = failed, taskId = taskId)
    return copy(messages = messages + card, keySeq = keySeq + 1)
}

/** How `_spawn_side_agent` reports a side task that threw. */
private const val SIDE_TASK_ERROR = "error: "

/** Desktop's `providerWaitText` (store/provider-wait.ts): the waits the core explains after a long silence. */
private val PROVIDER_WAIT = Regex(
    """^(?:⏳|⚠|↻|⚙)\s*(?:(?:still\s+)?waiting on|loading|processing prompt|no (?:output|response)|model returned|rate limited|provider (?:overloaded|temporarily unavailable))""",
    RegexOption.IGNORE_CASE,
)

/** The model fields of a `session.info` payload (also the `info` of `session.create`/`session.resume`). */
internal fun ChatState.withInfo(info: JsonObject?): ChatState {
    if (info == null) return this
    return copy(
        model = info.string("model")?.takeIf { it.isNotBlank() } ?: model,
        provider = info.string("provider")?.takeIf { it.isNotBlank() } ?: provider,
        // "" means the profile default, which is worth showing as such rather than as a stale pick.
        reasoningEffort = info.string("reasoning_effort")?.let { it.ifBlank { null } } ?: reasoningEffort.takeIf { "reasoning_effort" !in info },
        reasoningRevision = if ("reasoning_effort" in info) reasoningRevision + 1 else reasoningRevision,
        fast = info.boolean("fast") ?: fast,
        yolo = info.boolean("yolo") ?: yolo,
        usage = SessionUsage.parse(info["usage"] as? JsonObject) ?: usage,
    )
}

private fun ChatState.appendText(chunk: String?): ChatState {
    if (chunk.isNullOrEmpty()) return this
    return withOpenReply { it.copy(text = it.text + chunk) }.copy(running = true)
}

private fun ChatState.complete(payload: JsonObject?, now: Double?): ChatState {
    val outcome = when (payload.string("status")) {
        "error" -> TurnOutcome.Error
        "interrupted" -> TurnOutcome.Interrupted
        else -> TurnOutcome.Complete
    }
    val error = payload.string("error") ?: payload.string("failure_reason")
    val warning = payload.string("warning")?.trim()?.takeIf { it.isNotEmpty() }
    // The turn is over, so no tool runs on, including in a part a correction closed early.
    val messages = messages.map { message ->
        if (message is ChatMessage.Assistant && message.tools.any { it.running }) {
            message.copy(tools = message.tools.map { it.copy(running = false) })
        } else {
            message
        }
    }
    // After a correction, the part above it was already shown and the final text repeats it.
    val shown = (messages.find { it.key == correctedReplyKey } as? ChatMessage.Assistant)?.text?.takeIf { it.isNotBlank() }
    val openKey = (messages.getOrNull(messages.openReplyIndex()) as? ChatMessage.Assistant)?.key ?: "live-$keySeq"
    val finalShown = payload.boolean("response_previewed") == true || payload.boolean("response_reused") == true
    val finalText = payload.string("text").orEmpty().let { text ->
        if (shown != null && text.startsWith(shown)) text.removePrefix(shown).trim() else text
    }.let { text ->
        // A completed turn's final is its last response, whose start may be in the part above a correction.
        if (outcome == TurnOutcome.Complete) text.withoutCarried(carriedResponse(openKey)) else text
    }.let { text ->
        // One the gateway says already showed, in the part above a correction, isn't shown again below it.
        val sealed = shown?.collapsedSpaces()
        if (outcome == TurnOutcome.Complete && finalShown && sealed != null && text.isNotBlank() && text.collapsedSpaces() in sealed) "" else text
    }
    val finalReasoning = payload.string("reasoning").orEmpty().takeIf { correctedReplyKey == null }.orEmpty()
    val finalUsage = SessionUsage.parse(payload?.get("usage") as? JsonObject)
    val index = messages.openReplyIndex().takeIf { it >= 0 }
        ?: if (finalText.isBlank() && error == null && warning == null) return endTurn(messages, finalUsage) else messages.size
    val base = messages.getOrNull(index) as? ChatMessage.Assistant ?: ChatMessage.Assistant(key = "live-$keySeq")
    val reply = base.copy(
        // What streamed holds every segment, so it stays. A completed turn's final text is its last response, which
        // fills in what didn't stream (a non-streaming provider, deltas missed). A stopped or failed turn's may be the
        // whole reply kept so far, so it only stands in for nothing.
        text = if (outcome == TurnOutcome.Complete) {
            withFinalResponse(
                text = base.text,
                final = finalText,
                start = responseStart?.takeIf { it.replyKey == base.key }?.offset,
                besideFrom = responseStart?.takeIf { it.replyKey == base.key }?.toolOffset,
                shown = finalShown,
                transformed = payload.boolean("response_transformed") == true,
            )
        } else {
            base.text.ifBlank { finalText }
        },
        reasoning = base.reasoning.ifBlank { finalReasoning },
        tools = base.tools.map { it.copy(running = false) },
        streaming = false,
        outcome = outcome,
        error = error.takeIf { outcome == TurnOutcome.Error },
        usage = finalUsage?.let { end -> turnStartUsage?.let { end - it } }?.takeIf { it.any },
        warning = warning,
        timestamp = now ?: base.timestamp,
        // The completion receipt names the committed row even after the session rotates.
        reactionRowId = (payload?.get("persisted_turn") as? JsonObject)
            .long("final_assistant_row_id")?.takeIf { it > 0 } ?: base.reactionRowId,
    )
    // A turn that ended with nothing to show (e.g. interrupted at once) leaves no bubble, unless it failed or warns.
    val empty = reply.text.isBlank() && reply.reasoning.isBlank() && reply.commentary.isEmpty() && reply.tools.isEmpty() && reply.error == null && warning == null
    val updated = messages.toMutableList().apply {
        when {
            index == size -> if (!empty) add(reply)
            empty && outcome != TurnOutcome.Error -> removeAt(index)
            else -> set(index, reply)
        }
    }
    return endTurn(updated, finalUsage).copy(keySeq = keySeq + 1)
}

/**
 * Gives the prompt a finished turn ran the row it was stored at (`message.complete` `persisted_turn`), so it can be
 * regenerated or edited. Only the session authority names the prompt there, by its `submission_id`: its
 * `prompt.submit` answers before the row is written, so the reply has no `user_row_id`.
 */
private fun ChatState.withPromptRow(persisted: JsonObject?): ChatState {
    val submission = persisted.string("submission_id") ?: return this
    // Its turn has run, so it waits for nothing, whatever the order its start and its reply were read in.
    if (messages.any { it is ChatMessage.User && it.submissionId == submission && it.queued }) {
        return copy(messages = messages.map { if (it is ChatMessage.User && it.submissionId == submission) it.copy(queued = false) else it })
            .withPromptRow(persisted)
    }
    val row = persisted.long("user_row_id") ?: return this
    if (messages.none { it is ChatMessage.User && it.submissionId == submission && it.rowId == null }) return this
    return copy(messages = messages.map { if (it is ChatMessage.User && it.submissionId == submission && it.rowId == null) it.copy(rowId = row) else it })
}

private fun ChatState.endTurn(messages: List<ChatMessage>, finalUsage: SessionUsage?) = copy(
    running = false,
    status = null,
    statusKind = null,
    thinkingFrame = null,
    messages = messages,
    correctedReplyKey = null,
    responseStart = null,
    todosLive = false,
    usage = finalUsage ?: usage,
    turnStartUsage = null,
)

/**
 * The open reply's text from here on is a new response: a tool was called, or text beside the calls was added whole.
 * Every delta after it, until the next one, is the same response, which `message.complete` sends again.
 */
private fun ChatState.markResponseStart(afterTool: Boolean): ChatState {
    val reply = messages.getOrNull(messages.openReplyIndex()) as? ChatMessage.Assistant ?: return this
    val same = responseStart?.takeIf { it.replyKey == reply.key }
    // After a tool the response is new; text beside the calls stays part of the one a steer may have split.
    return copy(
        responseStart = if (afterTool) {
            ResponseStart(reply.key, reply.text.length, toolOffset = reply.text.length)
        } else {
            ResponseStart(reply.key, reply.text.length, carried = same?.carried.orEmpty(), toolOffset = same?.toolOffset ?: 0)
        },
    )
}

/** Whether the open reply has none of the current response yet; with no known start, whether it has no text. */
private fun ChatState.atResponseStart(): Boolean {
    val reply = messages.getOrNull(messages.openReplyIndex()) as? ChatMessage.Assistant ?: return true
    val start = responseStart?.takeIf { it.replyKey == reply.key } ?: return reply.text.isBlank()
    return reply.text.substring(start.offset.coerceIn(0, reply.text.length)).isBlank()
}

/**
 * The start of the current response that a correction sealed into the reply above, when the reply [key] holds the
 * rest: kept from when that reply opened, or, for one `message.complete` opens, read from the sealed reply now.
 */
private fun ChatState.carriedResponse(key: String): String {
    val start = responseStart ?: return ""
    if (start.replyKey == key) return start.carried
    if (start.replyKey != correctedReplyKey) return ""
    val sealed = messages.find { it.key == start.replyKey } as? ChatMessage.Assistant ?: return ""
    // A reply sealed by a second steer holds only the middle; the part above it holds the start.
    return start.carried + sealed.text.substring(start.offset.coerceIn(0, sealed.text.length))
}

/** This final response without the start of it that [carried] already showed. */
private fun String.withoutCarried(carried: String): String {
    if (carried.isBlank()) return this
    return afterPrefixIgnoringSpaces(carried)?.trim() ?: this
}

/**
 * A completed reply's [text] with its last response, [final] (`message.complete` `text`), in place. Hermes sends the
 * response after the last tool there (v0.22.0 to v0.23.4 tui_gateway/prompt_turn.py `_turn_outcome`, and the session
 * authority's settled `response`), and Desktop merges it in as that response, so deltas that never arrived (a gap
 * the replay couldn't fill, a plugin that held the stream) don't cost the answer. [start] is where that response
 * begins in [text] ([ChatState.responseStart]), null when unknown (a reply rebuilt from the gateway's copy).
 * [shown]: the gateway says the response already showed (`response_previewed`, `response_reused`). [transformed]: a
 * plugin rewrote it after it streamed (`response_transformed`), so it replaces what did. [besideFrom]: where the text
 * after the last tool begins ([ResponseStart.toolOffset]); text added whole beside the calls since may be the final.
 */
internal fun withFinalResponse(
    text: String,
    final: String,
    start: Int?,
    shown: Boolean,
    transformed: Boolean,
    besideFrom: Int? = null,
): String {
    if (final.isBlank()) return text
    if (text.isBlank()) return final
    val at = start?.coerceIn(0, text.length)
    val head = if (at == null) "" else text.substring(0, at)
    val current = if (at == null) text else text.substring(at)
    // A final that repeats the earlier segments too carries only what's after them.
    val response = final.trim().let { whole ->
        val earlier = head.trim()
        if (earlier.isEmpty()) whole else whole.afterPrefixIgnoringSpaces(earlier)?.trim() ?: whole
    }
    if (response.isEmpty()) return text
    val wanted = response.collapsedSpaces()
    val streamed = current.collapsedSpaces()
    // It streamed (spacing aside), or came whole as text beside the calls (a paragraph of its own), or was sent twice.
    // Delivery may also have dropped a trailing silence marker that streamed (Bot Mode).
    val beside = head.substring((besideFrom ?: 0).coerceIn(0, head.length))
    if (streamed.endsWith(wanted) || beside.endsWithParagraph(wanted) || (streamed.startsWith(wanted) && !transformed)) {
        return text
    }
    if (shown && text.collapsedSpaces().contains(wanted)) return text
    // None of it streamed: it goes after the earlier segments.
    if (current.isBlank()) return afterSegments(head, "", response)
    if (at != null) {
        // What follows the last tool is this response, give or take text beside the calls before it: from where what
        // streamed of it begins (some deltas may be missing in between), the final takes its place.
        if (transformed) return afterSegments(head, current.takeWhile { it.isWhitespace() }, response)
        val from = current.paragraphStarts().firstOrNull { current.substring(it).isStreamedPartOf(response) }
        if (from != null) return afterSegments(text.substring(0, at + from), "", response)
        // Shown already, which this can't place.
        if (streamed.contains(wanted)) return text
        return "${text.trimEnd()}\n\n$response"
    }
    // Where it begins isn't known, so it goes last; the paragraphs at the end that are a start of it give way to it.
    val from = text.paragraphStarts().firstOrNull { text.substring(it).isStreamedPartOf(response) }
    if (from != null) return afterSegments(text.substring(0, from), "", response)
    if (text.collapsedSpaces().contains(wanted)) return text
    return "${text.trimEnd()}\n\n$response"
}

/** Where each paragraph of this begins (past its leading whitespace), first to last; the first is always there. */
private fun String.paragraphStarts(): List<Int> =
    listOf(indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)) +
        PARAGRAPH.findAll(this).map { it.range.last + 1 }.filter { it < length && it > 0 }

/** Whether this ends with [wanted] (spacing aside) as whole paragraphs, not inside a sentence. */
private fun String.endsWithParagraph(wanted: String): Boolean =
    isNotBlank() && paragraphStarts().any { substring(it).collapsedSpaces() == wanted }

/**
 * Whether this, a streamed start of a response, is part of [response] with words missing at most: its first word
 * starts it, the rest come in order, and the last may be cut short. Deltas lost in a gap leave words out.
 */
private fun String.isStreamedPartOf(response: String): Boolean {
    val have = split(WHITESPACE).filter { it.isNotEmpty() }
    val want = response.split(WHITESPACE).filter { it.isNotEmpty() }
    if (have.isEmpty()) return true
    var j = 0
    have.forEachIndexed { i, word ->
        val last = i == have.lastIndex
        while (j < want.size && want[j] != word && !(last && want[j].startsWith(word))) {
            if (i == 0) return false
            j++
        }
        if (j == want.size) return false
        j++
    }
    return true
}

/** Whether this starts with [prefix], whitespace aside in both (CRLF and LF line ends alike). */
private fun String.startsWithIgnoringSpaces(prefix: String): Boolean = afterPrefixIgnoringSpaces(prefix) != null

/** This after [prefix], whitespace aside in both; null when it doesn't start with it. */
private fun String.afterPrefixIgnoringSpaces(prefix: String): String? {
    var i = 0
    var j = 0
    while (true) {
        while (j < prefix.length && prefix[j].isWhitespace()) j++
        if (j == prefix.length) return substring(i)
        while (i < length && this[i].isWhitespace()) i++
        if (i == length || this[i] != prefix[j]) return null
        i++
        j++
    }
}

private val PARAGRAPH = Regex("""\r?\n[ \t]*\r?\n\s*""")

/** [response] after the reply's earlier segments, a blank line apart unless the text before already breaks. */
private fun afterSegments(head: String, lead: String, response: String): String {
    val before = head + lead
    return when {
        head.isBlank() -> response
        // A single line break isn't a paragraph in Markdown (after a list item it would join it).
        PARAGRAPH_END.containsMatchIn(before) -> before + response
        else -> "${before.trimEnd()}\n\n$response"
    }
}

private val PARAGRAPH_END = Regex("""\r?\n[ \t]*\r?\n[ \t]*$""")

private fun String.collapsedSpaces(): String = WHITESPACE.replace(this, " ").trim()

private val WHITESPACE = Regex("""\s+""")

/** The reply still streaming. Not necessarily last: a prompt queued mid-turn sits after it. */
private fun List<ChatMessage>.openReplyIndex(): Int = indexOfLast { it is ChatMessage.Assistant && it.streaming }

/**
 * The prompt a starting turn runs stops waiting. The session authority names it by [admission]. Elsewhere, a turn
 * starting with no reply open is the gateway running what it queued: the oldest waiting prompt goes first. With a
 * reply still streaming, the start belongs to that turn and the queue keeps waiting.
 */
private fun ChatState.releaseQueued(admission: String?): ChatState {
    if (admission != null) {
        // The session authority names the admission the turn runs: that prompt goes, and no other.
        val started = copy(startedAdmissions = (startedAdmissions - admission + admission).takeLast(STARTED_ADMISSIONS_KEPT))
        val index = messages.indexOfFirst { it is ChatMessage.User && it.admissionId == admission }
        if (index >= 0) {
            val open = messages.openReplyIndex()
            val released = messages.toMutableList().apply { set(index, (get(index) as ChatMessage.User).copy(queued = false)) }
            // A reply still open above it is a turn whose end was missed (a resume that said running): it ended before
            // this one began, so it ends here, tools and all, and this turn's reply goes below its prompt.
            if (open !in 0 until index) return started.copy(messages = released)
            // As at its end: no tool runs on, including in a part a correction closed early.
            val ended = released.mapIndexed { i, message ->
                if (message !is ChatMessage.Assistant || (i != open && message.tools.none { it.running })) {
                    message
                } else {
                    message.copy(streaming = message.streaming && i != open, tools = message.tools.map { it.copy(running = false) })
                }
            }
            return started.endTurn(ended, finalUsage = null)
        }
        // Another client's prompt, or ours before its reply is read. Only one queued without an id goes, as before:
        // one with an id waits for its own.
        return started.releaseQueuedInOrder { it.admissionId == null }
    }
    return releaseQueuedInOrder { true }
}

/** How many started admissions [ChatState.startedAdmissions] keeps: enough for the prompts one device can have in flight. */
private const val STARTED_ADMISSIONS_KEPT = 16

private fun ChatState.releaseQueuedInOrder(eligible: (ChatMessage.User) -> Boolean): ChatState {
    if (messages.openReplyIndex() >= 0) return this
    val index = messages.indexOfFirst { it is ChatMessage.User && it.queued && eligible(it) }
    if (index < 0) return this
    return copy(messages = messages.toMutableList().apply { set(index, (get(index) as ChatMessage.User).copy(queued = false)) })
}

/** Applies [change] to the reply being streamed, or opens a new one at the end. */
private fun ChatState.withOpenReply(change: (ChatMessage.Assistant) -> ChatMessage.Assistant): ChatState {
    val index = messages.openReplyIndex()
    return if (index >= 0) {
        copy(messages = messages.toMutableList().apply { set(index, change(get(index) as ChatMessage.Assistant)) })
    } else {
        // A reply opened here holds only what arrives from now on: its text is all one response until a tool. Below a
        // correction, that response may have begun in the reply sealed above.
        copy(
            messages = messages + change(ChatMessage.Assistant(key = "live-$keySeq", streaming = true)),
            responseStart = ResponseStart("live-$keySeq", 0, carried = carriedResponse("live-$keySeq")),
            keySeq = keySeq + 1,
        )
    }
}

/** agent/prompt_builder.py's frame around a mid-turn correction, stored as the user row. */
private val CORRECTION_FRAME = Regex("""^\[OUT-OF-BAND USER MESSAGE[^\]]*]\s*([\s\S]*?)\s*\[/OUT-OF-BAND USER MESSAGE]$""")

/** A correction's stored row → what the person typed; anything else unchanged. */
internal fun unwrapCorrection(text: String): String = CORRECTION_FRAME.find(text)?.groupValues?.get(1) ?: text

/**
 * Stored rows → chat messages: tool results and system/hidden rows drop out, and consecutive
 * assistant rows (text and tool-call steps) merge into one reply listing every tool it used.
 * [liveSessionId] is the session the page was read from; prompts stored under an older one can't be cut at.
 * [turnStartedAt]: when the running turn began (gateway clock). Its saved steps start a reply of their own even
 * when its prompt doesn't show (hidden, a system note), since they are what its live reply repeats.
 */
fun historyToMessages(rows: List<SessionMessage>, liveSessionId: String? = null, turnStartedAt: Double? = null): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    // Each call's result is a later `tool` row carrying its id.
    val results = rows.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId }
    // Messages this bot sent others (`message_agent`): their delivery process → to whom, to name the outcome.
    val deliveries = rows.asSequence().filter { it.role == "assistant" }.flatMap { it.storedToolCalls }
        .filter { it.name == MESSAGE_AGENT_TOOL }
        .mapNotNull { call -> call.id?.let(results::get)?.text?.let(TranscriptRows::deliveryAck) }
        .toMap()
    // Who this bot has messaged since the person last spoke: their answers aren't replies to fold away.
    val messagedSinceUser = mutableSetOf<String>()
    var replyingTo: String? = null
    val shownRequestTextsByUid = mutableMapOf<String, MutableSet<String>>()
    val shownRequestTexts = mutableSetOf<String>()
    val shownLegacyRequestTexts = mutableSetOf<String>()
    fun rememberRequest(row: SessionMessage, text: String) {
        val uids = row.requestUids
        uids.forEach { uid -> shownRequestTextsByUid.getOrPut(uid) { mutableSetOf() }.add(text) }
        if (row.imageCount == 0) {
            shownRequestTexts += text
            if (uids.isEmpty()) shownLegacyRequestTexts += text
        }
    }
    rows.forEachIndexed { index, row ->
        if (row.isHidden) {
            // A stop during a tool closes the turn with a hidden row that holds the stopped mark
            // (v0.23.4 agent/message_sanitization.py `close_interrupted_tool_sequence`): the reply it closes was stopped.
            val last = messages.lastOrNull()
            if (row.role == "assistant" && row.stopped && last is ChatMessage.Assistant) {
                messages[messages.lastIndex] = last.copy(outcome = TurnOutcome.Interrupted)
            }
            return@forEachIndexed
        }
        val key = row.id?.let { "row-$it" } ?: "h$index"
        if ((row.role == "user" || row.role == "assistant") && row.displayKind != "failed_turn") {
            TranscriptRows.compaction(row.text)?.let { checkpoint ->
                messages += ChatMessage.Event(key, checkpoint, timestamp = row.timestamp)
                replyingTo = null
                return@forEachIndexed
            }
        }
        when (row.role) {
            "user" -> {
                // Without the task list compaction may fold into the last prompt (or a bot's message), which is for the agent.
                val displayText = TranscriptRows.promptText(row).trim()
                val noise = TranscriptRows.isNoise(displayText, row.displayKind, row.displayMetadata)
                val restated = if (noise) TranscriptRows.restatedRequest(displayText) else null
                val shownText = restated ?: displayText
                if (restated != null) {
                    val uids = row.requestUids
                    if (uids.any { restated in shownRequestTextsByUid[it].orEmpty() }) return@forEachIndexed
                    if (row.imageCount == 0 && restated in shownRequestTexts) {
                        if (uids.isEmpty() || restated in shownLegacyRequestTexts) return@forEachIndexed
                        // The summary's own UID cannot identify the original when its absorbed UID is absent.
                        val carrierWithoutSource = uids.singleOrNull() == row.messageUid &&
                            row.displayContent != null && TranscriptRows.carriesRestatedRequest(row.storedText)
                        if (carrierWithoutSource) return@forEachIndexed
                    }
                }
                // Background subagents reporting back: written for the agent, so a line here, and their results go to their card.
                parseDelegationReport(shownText)?.let { report ->
                    messages.settle(report)
                    messages += ChatMessage.Notice(key, report.line, stored = true)
                    rememberRequest(row, shownText)
                    return@forEachIndexed
                }
                val shownNoise = if (restated == null) noise else TranscriptRows.isNoise(shownText, row.displayKind, row.displayMetadata)
                if (shownNoise) return@forEachIndexed
                TranscriptRows.classify(shownText, row.displayKind, deliveries, row.displayMetadata)?.let { events ->
                    events.forEachIndexed { i, event ->
                        if (event is TranscriptEvent.FromBot) rememberRequest(row, shownText)
                        messages += ChatMessage.Event(if (events.size == 1) key else "$key-$i", event, timestamp = row.timestamp)
                        // After "[no reply needed]" hermes sends nothing back to that sender (gateway/session_bot_outcome.py),
                        // so a reply still owed to an earlier bot stays owed.
                        if ((event as? TranscriptEvent.FromBot)?.noReplyNeeded != true) {
                            replyingTo = (event as? TranscriptEvent.FromBot)?.takeUnless { from ->
                                from.handle?.lowercase() in messagedSinceUser || from.sender.lowercase() in messagedSinceUser
                            }?.sender
                        }
                    }
                    return@forEachIndexed
                }
                messagedSinceUser.clear()
                replyingTo = null
                val raw = unwrapCorrection(shownText)
                val (refs, text) = skillInvocationText(raw)?.let { emptyList<ShownAttachment>() to it } ?: splitAttachmentRefs(raw, key)
                val attachments = List(row.imageCount) { ShownAttachment("$key-i$it", "Image", AttachmentKind.Image) } + refs
                // A recovered restatement is display-only. Rewind targets must be original rows the live agent still holds.
                val cuttable = restated == null && row.active && !row.compacted &&
                    (liveSessionId == null || row.sessionId == null || row.sessionId == liveSessionId)
                if (text.isNotEmpty() || attachments.isNotEmpty()) {
                    rememberRequest(row, shownText)
                    messages += ChatMessage.User(
                        key,
                        text,
                        attachments = attachments,
                        rowId = row.id?.takeIf { cuttable },
                        // Pictures and files went up once and can't be sent again with the text.
                        sentText = raw.takeIf { attachments.isEmpty() && raw.isNotEmpty() },
                        timestamp = row.timestamp,
                    )
                }
            }
            "assistant" -> {
                if (row.displayKind == "failed_turn") {
                    messages += ChatMessage.Event(key, TranscriptEvent.FailedTurn(row.text.trim()))
                    return@forEachIndexed
                }
                // A bot that chose to say nothing shows nothing.
                val silent = TranscriptRows.isSilence(row.text.trim())
                val text = if (silent) "" else row.text.trim()
                row.storedToolCalls.filter { it.name == MESSAGE_AGENT_TOOL }.forEach { call ->
                    messageTarget(call.arguments)?.let { messagedSinceUser += it.lowercase() }
                }
                // A step of the running turn is saved before its calls end: one with no result row yet still runs.
                val runningTurn = turnStartedAt != null && row.timestamp != null && row.timestamp >= turnStartedAt
                val tools = row.storedToolCalls.mapIndexed { i, call ->
                    val result = call.id?.let(results::get)?.content
                    ToolActivity(
                        id = call.id ?: "$key-$i",
                        name = call.name,
                        running = runningTurn && call.id != null && call.id !in results,
                        input = ToolDetails.input(call.arguments),
                        output = ToolDetails.output(result),
                        failed = ToolDetails.failed(result),
                        tasks = if (call.name == DELEGATE_TOOL) delegatedTasks(call.arguments, result) else emptyList(),
                        delegationIds = if (call.name == DELEGATE_TOOL) delegationIds(result) else emptyList(),
                        generatedImage = if (storedToolName(call.name, call.arguments) == IMAGE_GENERATE_TOOL) GeneratedImage.from(result) else null,
                        agentScreen = AgentScreen.from(call.name, call.arguments),
                    )
                }
                val reasoning = row.reasoningText
                val commentary = row.commentary
                val previous = (messages.lastOrNull() as? ChatMessage.Assistant)?.takeUnless { before ->
                    val at = row.timestamp
                    val ended = before.timestamp
                    turnStartedAt != null && at != null && ended != null && at >= turnStartedAt && ended < turnStartedAt
                }
                // Like the completing receipt: use the final assistant row without calls. A silence marker
                // can close the reply after visible text, but cannot make a standalone reply reactable.
                val reactionRow = row.id?.takeIf {
                    it > 0 && tools.isEmpty() && (text.isNotEmpty() || (silent && previous?.text?.isNotEmpty() == true))
                }
                if (previous != null) {
                    // A later step with no reaction of its own (a goal's continuation, say) leaves the reply on the
                    // row that holds the user's reaction, so it still shows and a tap changes that one.
                    val keepTarget = reactionRow == null || (row.userReaction == null && previous.userReaction != null)
                    messages[messages.lastIndex] = previous.copy(
                        text = listOf(previous.text, text).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        reasoning = listOf(previous.reasoning, reasoning).filter { it.isNotEmpty() }.joinToString("\n\n"),
                        reactionRowId = if (keepTarget) previous.reactionRowId else reactionRow,
                        userReaction = if (keepTarget) previous.userReaction else row.userReaction,
                        commentary = (previous.commentary + commentary).distinctCommentary(),
                        tools = previous.tools + tools,
                        outcome = if (row.stopped) TurnOutcome.Interrupted else previous.outcome,
                        // The reply is dated by its last step, when it finished.
                        timestamp = row.timestamp ?: previous.timestamp,
                    )
                } else if (text.isNotEmpty() || (reasoning.isNotEmpty() && !silent) || commentary.isNotEmpty() || tools.isNotEmpty()) {
                    messages += ChatMessage.Assistant(
                        key,
                        text = text,
                        reasoning = reasoning,
                        reactionRowId = reactionRow,
                        userReaction = row.userReaction.takeIf { reactionRow != null },
                        commentary = commentary,
                        tools = tools,
                        repliedTo = replyingTo,
                        timestamp = row.timestamp,
                        // Still reads as stopped once the transcript replaces the live copy.
                        outcome = TurnOutcome.Interrupted.takeIf { row.stopped },
                    )
                }
            }
        }
    }
    return groupSystemMessages(messages)
}

/** Bot Mode's tool for messaging another bot. */
const val MESSAGE_AGENT_TOOL = "message_agent"

/** What a running tool is doing, in words: "terminal" reads as "Running a command"; other tools as "Using <name>". */
fun toolInProgress(name: String): String = when (name) {
    "terminal", "shell", "bash" -> "Running a command"
    "read_file", "file_read" -> "Reading a file"
    "write_file", "patch", "edit_file" -> "Editing a file"
    "web_search", "search" -> "Searching the web"
    "web_extract", "browser", "fetch" -> "Reading a web page"
    "delegate_task" -> "Working with subagents"
    MESSAGE_AGENT_TOOL -> "Messaging another bot"
    else -> "Using ${name.replace('_', ' ')}"
}

/** The bot a `message_agent` call is addressed to (`target`, with or without its `@`). */
fun messageTarget(arguments: kotlinx.serialization.json.JsonElement?): String? {
    val obj = when (arguments) {
        is kotlinx.serialization.json.JsonObject -> arguments
        is kotlinx.serialization.json.JsonPrimitive -> arguments.contentOrNull?.let {
            runCatching { dev.hermeskotlin.core.network.HermesJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        }
        else -> null
    } ?: return null
    return (obj["target"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("@")?.substringBefore('@')?.takeIf { it.isNotEmpty() }
}

/** `status.update` kinds: auto-compaction's terminal edge, and the clear a manual `/compress` ends with. */
private const val COMPACTED_KIND = "compacted"
private const val READY_KIND = "ready"
