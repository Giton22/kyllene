package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A transcript row the machinery wrote rather than the person: Hermes stores most of them as user turns,
 * because the agent has to read them, but showing them as the user's prompts would put words in their mouth.
 */
sealed interface TranscriptEvent {
    /** Another bot's message (Bot Mode's `message_agent`, a relay or `hermes peer`). */
    data class FromBot(val sender: String, val handle: String?, val body: String) : TranscriptEvent

    /** How a message this bot sent another one ended: the reply, or why there isn't one (yet). */
    data class Delivery(val target: String?, val outcome: DeliveryOutcome) : TranscriptEvent

    /** A background process the agent started has finished; [output] is what it printed. */
    data class Process(val title: String, val output: String, val failed: Boolean) : TranscriptEvent

    /**
     * A scheduled job's output: handed to the bot to act on (cron `deliver: bot-chat`, or a mirror), or, when
     * [result], the job's message itself, written into the chat it was scheduled from (`deliver: origin`).
     * [name] is null when the delivered text doesn't say; [failed] for a notice that the job went wrong.
     */
    data class Routine(val name: String?, val body: String, val failed: Boolean = false, val result: Boolean = false) : TranscriptEvent

    /** A legacy compaction handoff kept as reference for the agent, not a prompt or reply. */
    data class Compaction(val body: String) : TranscriptEvent

    /** Consecutive background entries, folded together without moving them past a reply or failure. */
    data class SystemMessages(val entries: List<TranscriptEvent>, val firstKey: String) : TranscriptEvent

    /** A turn that didn't complete, in the gateway's words. */
    data class FailedTurn(val text: String) : TranscriptEvent

    /** A one-line fact about the session, e.g. that the model changed. */
    data class Label(val text: String) : TranscriptEvent
}

sealed interface DeliveryOutcome {
    data class Replied(val text: String) : DeliveryOutcome

    /** The other bot read it and chose to say nothing. */
    data object NoReply : DeliveryOutcome

    /** Still on its way or its fate unknown: the message must not be sent again. */
    data class Waiting(val detail: String?) : DeliveryOutcome

    data class Failed(val reason: String?, val detail: String?) : DeliveryOutcome {
        /** The gateway's typed reason in words (tools/bot_failure_reasons.py), else its own message. */
        val label: String get() = reason?.let(REASON_LABELS::get) ?: detail?.lineSequence()?.firstOrNull()?.take(120) ?: "delivery failed"
    }
}

/** Classifies stored rows. Ported from Desktop's renderers and the gateway's own formats; see docs/bot-mode-integration.md §6. */
object TranscriptRows {

    /**
     * User rows that are machinery the agent reads but nobody needs to see. [displayMetadata] is the row's
     * `display_metadata`, which tells a scheduled job's message apart from the wakes that share its kind.
     */
    fun isNoise(row: String, displayKind: String?, displayMetadata: JsonElement? = null): Boolean =
        (displayKind == "internal_notification" &&
            AgentMessage.parse(row, requireEnvelope = true) == null &&
            routine(row) == null && scheduledResult(row, displayMetadata) == null) ||
            row.startsWith("[System:") || HEARTBEAT.containsMatchIn(row)

    /** Older gateways/plugins kept handoffs under either user or assistant without a display kind. */
    fun compaction(text: String): TranscriptEvent.Compaction? {
        // The whole introduction every shipped prefix shares, as the gateway matches it (hermes_state_common.py),
        // so a prompt that merely starts with the bracketed label stays a prompt.
        val trimmed = text.trim()
        val prefix = listOf(SUMMARY_INTRO, LEGACY_SUMMARY_PREFIX).firstOrNull(trimmed::startsWith) ?: return null
        // A legacy carrier can hold a real ask after the summary. Without a display projection,
        // leave that whole row visible rather than hide the ask and its prompt actions.
        if (trimmed.substringAfter(SUMMARY_END, "").isNotBlank()) return null
        // What the summary says, without the instructions to the agent before it (they all end the same way).
        val body = trimmed.substringBefore(SUMMARY_END)
            .let { if (prefix == SUMMARY_INTRO && PREFIX_END in it) it.substringAfter(PREFIX_END) else it.removePrefix(prefix) }
        return TranscriptEvent.Compaction(body.trim())
    }

    /**
     * What a stored user row really is, or null when the person wrote it. [deliveries] maps the process ids
     * of this chat's `message_agent` sends to whom they went, to name a delivery's other end.
     */
    fun classify(
        text: String,
        displayKind: String?,
        deliveries: Map<String, String> = emptyMap(),
        displayMetadata: JsonElement? = null,
    ): List<TranscriptEvent>? {
        val row = text.trim()
        AgentMessage.parse(row, requireEnvelope = displayKind == "internal_notification")
            ?.let { return listOf(TranscriptEvent.FromBot(it.sender, it.handle, it.body)) }
        routine(row)?.let { return listOf(it) }
        if (displayKind == "internal_notification") scheduledResult(row, displayMetadata)?.let { return listOf(it) }
        if (displayKind == "process_complete" || displayKind == "async_delegation_complete" || PROCESS.matches(row)) {
            val parts = row.split(BATCH_SPLIT).mapNotNull { processEvent(it, deliveries) }
            if (parts.isNotEmpty()) return parts
        }
        return when (displayKind) {
            "model_switch" -> listOf(TranscriptEvent.Label("Model changed"))
            "personality_switch" -> listOf(TranscriptEvent.Label("Personality changed"))
            "auto_continue" -> listOf(TranscriptEvent.Label("Resumed an interrupted turn"))
            else -> null
        }
    }

    /**
     * A reply that is only one of Hermes' intentional-silence tokens: the bot chose to say nothing, which shows
     * as nothing (gateway/response_filters.py `is_intentional_silence_response`).
     */
    fun isSilence(text: String): Boolean {
        val stripped = text.trim()
        if (stripped.isEmpty() || stripped.length > 64) return false
        fun normal(value: String) = value.uppercase().replace(Regex("\\s+"), " ").trim()
        if (normal(stripped) in SILENCE) return true
        // Edge punctuation (other than brackets) is stripped too: *NO_REPLY*, .NO_REPLY
        val trimmed = stripped.trim { !it.isLetterOrDigit() && !it.isWhitespace() && it != '[' && it != ']' && it != '_' }
        return trimmed.isNotEmpty() && normal(trimmed) in SILENCE
    }

    /** `message_agent`'s acknowledgement: which background process carries the message, and to whom. */
    fun deliveryAck(result: String?): Pair<String, String>? {
        val obj = runCatching { HermesJson.parseToJsonElement(result ?: return null) as? JsonObject }.getOrNull() ?: return null
        val process = obj.text("process_id") ?: return null
        val to = obj.text("to")?.removePrefix("@") ?: return null
        return process to to
    }

    private fun processEvent(part: String, deliveries: Map<String, String>): TranscriptEvent? {
        val body = part.trim().removePrefix("[IMPORTANT:").trim().removeSuffix("]").trim()
        val headline = body.lineSequence().firstOrNull()?.trim().orEmpty()
        if (BATCH_HEADER.containsMatchIn(headline)) return null
        val command = COMMAND.find(body)?.groupValues?.get(1)?.trim()
        val output = body.substringAfter("\nOutput:", "").trim()
        val failed = FAILED_STATUS.containsMatchIn(headline)
        val processId = PROCESS_ID.find(headline)?.groupValues?.get(1)
        val target = processId?.let(deliveries::get)
        if (target != null || command?.let(DELIVERY_COMMAND::containsMatchIn) == true) {
            return TranscriptEvent.Delivery(target ?: command?.let(::deliveryTarget), deliveryOutcome(output, failed))
        }
        val title = command?.let { "Background process finished: ${it.take(80)}" } ?: headline.ifEmpty { "Background process finished" }
        return TranscriptEvent.Process(title, output, failed)
    }

    private fun deliveryTarget(command: String): String? {
        val profile = TARGET_PROFILE.find(command)?.groupValues?.get(1) ?: PEER_TARGET.find(command)?.groupValues?.get(1) ?: return null
        // The primary bot answers to @hermes.
        return if (profile == "default") "hermes" else profile
    }

    /** The runner's stdout in one of its shapes (tools/bot_mode_dm.py, hermes_cli/subcommands/peer.py). */
    internal fun deliveryOutcome(output: String, exitFailed: Boolean): DeliveryOutcome {
        val text = output.lineSequence().filterNot { SESSION_ID_LINE.matches(it) }.joinToString("\n")
            .replace(RUNNER_NOTE, "").trim()
        if (text.startsWith("{")) {
            val obj = runCatching { HermesJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (obj != null) {
                val status = obj.text("status")
                val reply = obj.text("reply")
                val error = obj.text("error")
                return when {
                    status in WAITING_STATUSES -> DeliveryOutcome.Waiting(obj.text("detail") ?: error)
                    error != null || status == "failed" || status == "cancelled" -> DeliveryOutcome.Failed(obj.text("reason"), error)
                    reply.isNullOrBlank() -> DeliveryOutcome.NoReply
                    else -> DeliveryOutcome.Replied(reply.trim())
                }
            }
        }
        REPLY_FROM.matchEntire(text)?.let { return DeliveryOutcome.Replied(it.groupValues[1].trim()) }
        DELIVERY_FAILED.find(text)?.let { return DeliveryOutcome.Failed(it.groupValues[1], text) }
        if (NOT_YET.containsMatchIn(text)) return DeliveryOutcome.Waiting(text)
        if (exitFailed) return DeliveryOutcome.Failed(null, text.ifEmpty { null })
        if (text.isEmpty() || text == "(no reply)") return DeliveryOutcome.NoReply
        return DeliveryOutcome.Replied(text)
    }

    // Some gateways type cron deliveries as generic internal notifications. Only known envelopes
    // escape that filter; this best-effort presentation match never grants permission to act.
    private fun routine(row: String): TranscriptEvent.Routine? {
        val match = ROUTINE.matchEntire(row) ?: CRON_MIRROR.matchEntire(row) ?: return null
        return TranscriptEvent.Routine(match.groupValues[1], match.groupValues[2].trim())
    }

    /**
     * A cron result written into a local chat as a note (`deliver: origin`, hermes-agent 0.22.6 and later): an
     * `internal_notification` row whose `display_metadata.completion_note` names only `cron` as its producer
     * (gateway/session_completion_notes.py `note_if_unviewed`). Wakes, watch notes and heartbeats share the kind
     * but name another producer or none. Where the route leaves the metadata out, only the exact text cron wraps
     * a result in (cron/scheduler_delivery.py `_deliver_result`) tells one apart.
     */
    private fun scheduledResult(row: String, displayMetadata: JsonElement?): TranscriptEvent.Routine? {
        val metadata = displayMetadataObject(displayMetadata)
        val producers = metadata?.get("completion_note")?.let(::completionProducers)
        val wrapped = CRON_WRAPPED.matchEntire(row)
        if (producers == null) {
            // No note metadata: the whole wrapper, footer included, or it stays hidden.
            if (displayMetadata != null && displayMetadata !is JsonNull && metadata == null) return null
            if (wrapped == null || wrapped.groupValues[3].isEmpty()) return null
        } else if (producers.isEmpty() || producers.any { it != "cron" }) {
            return null
        }
        val failed = (metadata?.get("notification_category") as? JsonPrimitive)?.contentOrNull == "diagnostic"
        return TranscriptEvent.Routine(
            name = wrapped?.groupValues?.get(1)?.trim()?.ifEmpty { null },
            body = (wrapped?.groupValues?.get(2) ?: row).trim(),
            failed = failed,
            result = true,
        )
    }

    /** Each `completion_note` entry's producer: entries are JSON-encoded `[producer, route, id]` lists. */
    private fun completionProducers(notes: JsonElement): List<String?> {
        val entries = notes as? JsonArray ?: return emptyList()
        return entries.map { entry ->
            val identity = when (entry) {
                is JsonArray -> entry
                is JsonPrimitive -> entry.contentOrNull?.let { runCatching { HermesJson.parseToJsonElement(it) }.getOrNull() } as? JsonArray
                else -> null
            }
            (identity?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** agent/context_compressor.py: the start of SUMMARY_PREFIX and its historical ones, the end they share, the oldest prefix. */
    private const val SUMMARY_INTRO = "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below."
    private const val PREFIX_END = "may reflect work described here — avoid repeating it:"
    private const val LEGACY_SUMMARY_PREFIX = "[CONTEXT SUMMARY]:"
    private const val SUMMARY_END = "--- END OF CONTEXT SUMMARY — respond to the message below, not the summary above ---"
    private val HEARTBEAT = Regex("""^\[Background process \S+ heartbeat #\d+ """)
    private val PROCESS = Regex("""^\[IMPORTANT: (?:Background process|\d+ background processes) [\s\S]*]$""")
    private val BATCH_SPLIT = Regex("""\n\n(?=\[IMPORTANT: )""")
    private val BATCH_HEADER = Regex("""^\d+ background processes completed\.""")
    private val COMMAND = Regex("""(?m)^Command: (.*)$""")
    private val PROCESS_ID = Regex("""Background process (\S+)""")
    private val FAILED_STATUS = Regex("""exited \(exit code [1-9]|terminated by|marked lost|failed to start""")
    private val DELIVERY_COMMAND = Regex("""bot_mode_dm\.py\b[\s\S]*--(?:run-delivery|wait-reply)|\bpeer\s+dm\b""")
    private val TARGET_PROFILE = Regex("""(?:^|\s)-p\s+'?"?([a-z0-9][a-z0-9_-]{0,63})'?"?\s+chat\b""")
    private val PEER_TARGET = Regex("""\bpeer\s+dm\s+'?"?([A-Za-z0-9_./-]+)""")
    private val ROUTINE = Regex("""^\[Cronjob "(.+?)" output — [^\]\n]*]\s*([\s\S]*)$""")
    private val CRON_MIRROR = Regex("""^\[Cron delivery: ([^\]\n]+)](?:\n([\s\S]*))?$""")
    /** cron/scheduler_delivery.py `_deliver_result`: header, job id, rule, the output, then the footer. */
    private val CRON_WRAPPED = Regex(
        """^Cronjob Response: ([^\n]*)\r?\n\(job_id: [^)\n]*\)\r?\n-{13}\r?\n\r?\n([\s\S]*?)""" +
            """(\r?\n\r?\nTo stop or manage this job, send me a new message \(e\.g\. "stop reminder [^\n]*"\)\.)?$""",
    )
    /** The CLI runner's own lines around a reply: the session it used, and that it resumed one. */
    private val SESSION_ID_LINE = Regex("""^\s*(?:session_id:\s.*|↻ Resumed session\b.*)$""")
    /** The CLI's note when it resumes an empty session, which lands in the middle of a delivered reply. */
    private val RUNNER_NOTE = Regex("""\s*Session \S+ found but has no messages\. Starting fresh\.""")
    private val REPLY_FROM = Regex("""^Reply from [^:\n]+:\s*([\s\S]*)$""")
    private val DELIVERY_FAILED = Regex("""^Delivery to .+? failed \[reason: ([a-z_]+)]""")
    private val NOT_YET = Regex("""^No reply from .+ within|has its Bot Chat open|Do NOT resend|do not resend""", RegexOption.IGNORE_CASE)
    private val WAITING_STATUSES = setOf("queued", "claimed", "ambiguous")
    private val SILENCE = setOf("[SILENT]", "SILENT", "NO_REPLY", "NO REPLY", "[静默]", "静默", "[沉默]", "沉默")
}

private val REASON_LABELS = mapOf(
    "target_busy" to "their chat is open elsewhere",
    "runtime_offline" to "their machine is offline",
    "queued_expired" to "it waited too long",
    "delivery_timeout" to "no answer in time",
    "agent_blocked" to "they're blocked",
    "cancelled" to "cancelled",
    "provider_auth_or_access" to "they need to sign in again",
    "provider_quota_limit" to "their quota ran out",
    "provider_rate_limit" to "rate limited",
    "provider_server_error" to "their model provider failed",
    "context_overflow" to "their chat is too long",
    "missing_config" to "they have no model set up",
    "model_unavailable" to "their model is unavailable",
)

/** Only successful background bookkeeping folds away; failures and other bots remain visible. */
internal fun groupSystemMessages(messages: List<ChatMessage>): List<ChatMessage> = buildList {
    val pending = mutableListOf<ChatMessage.Event>()
    fun flush() {
        when (pending.size) {
            0 -> return
            // One alone says what it is, as before: "Model changed" shouldn't need a tap.
            1 -> add(pending.single())
            // Keyed by the newest entry, which an earlier page loaded on scroll-up can't change, so the list
            // keeps its place; the first one is where the group starts in the stored rows.
            else -> add(ChatMessage.Event(pending.last().key,
                TranscriptEvent.SystemMessages(pending.map { it.event }, firstKey = pending.first().key), pending.last().timestamp))
        }
        pending.clear()
    }
    messages.forEach { message ->
        val event = (message as? ChatMessage.Event)?.event
        val fold = when (event) {
            is TranscriptEvent.Compaction, is TranscriptEvent.Label -> true
            // A job's own message is what the person scheduled it for: it stays in view.
            is TranscriptEvent.Routine -> !event.result
            is TranscriptEvent.Process -> !event.failed
            else -> false
        }
        if (fold) {
            pending += message as ChatMessage.Event
        } else {
            flush()
            add(message)
        }
    }
    flush()
}
