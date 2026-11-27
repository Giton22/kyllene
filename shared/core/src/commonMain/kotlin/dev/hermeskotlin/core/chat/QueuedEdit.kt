package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.hermeskotlin.core.rpc.GatewayEvent

/*
 * Editing a prompt still queued behind a running turn. The queue is the gateway's: Kyllene sends a queued prompt at
 * once (`prompt.submit` with `queued`), so there is no copy held here to change. Only the session authority can edit
 * one, by `prompt.replace_queued` (Hermes v0.22.3 on): it cancels the queued admission and admits the edited text as a
 * new one at the back of the queue, in one write. It says which rows it serves that for in the `controls` of each
 * `pending` admission it publishes; an older owner lists none, and the legacy gateway has no such call, so there the
 * prompt simply offers no edit.
 */

/** How an edit of a queued prompt went. */
enum class QueuedEditOutcome {
    /** The gateway took the edit; the prompt now waits at the back of the queue with the new text. */
    Replaced,

    /** Its turn started (or it left the queue) first, so it went as it was. */
    Started,

    /** This gateway can't edit it: no `prompt.replace_queued`, or not for this prompt. */
    Unsupported,

    /** It couldn't be sent, or the gateway turned it down for another reason; the queued prompt is unchanged. */
    Failed,
}

/** Whether queued prompt [key] can be edited where it waits: the session authority offers `replace` for it. */
fun ChatState.canEditQueued(key: String): Boolean {
    val prompt = messages.firstOrNull { it.key == key } as? ChatMessage.User ?: return false
    val admission = prompt.admissionId ?: return false
    // What it shows must be what went out: a prompt with files, or one shown as something else, isn't plain text.
    return prompt.queued && !prompt.pending && prompt.check == null && prompt.sentText != null &&
        prompt.sentText == prompt.text && admission in replaceableAdmissions && admission !in replacingAdmissions
}

/**
 * The admissions a session authority `pending` list offers `replace` for: queued ones whose `controls` name it.
 * Null when [pending] isn't such a list, so a payload without one leaves what's known as it is.
 */
internal fun replaceableAdmissions(pending: JsonElement?): Set<String>? {
    val rows = pending as? JsonArray ?: return null
    return rows.mapNotNullTo(mutableSetOf()) { row ->
        val admission = row as? JsonObject ?: return@mapNotNullTo null
        val controls = admission["controls"] as? JsonArray ?: return@mapNotNullTo null
        val replace = controls.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull == REPLACE_CONTROL }
        admission.string("admission_id")?.takeIf { replace && admission.string("status") == "queued" }
    }
}

/** A `prompt.replace_queued` of prompt [key]'s admission [replaced] to [text], not answered yet. */
data class UnansweredQueuedEdit(val key: String, val replaced: String, val text: String)

/**
 * [this] with what [pending] (a `session.info` or snapshot `pending`) says can be replaced, when it says. A row whose
 * `input_id` is an [unanswered edit][ChatState.unansweredQueuedEdits]'s submission is that edit, taken by the gateway
 * as that admission, so it shows as made: `input_id` is the request id an admission was taken under
 * (gateway/session_authority.py `_pending_receipt`), which a cancel of the old admission never lists.
 */
internal fun ChatState.withPendingAdmissions(pending: JsonElement?): ChatState {
    val offered = replaceableAdmissions(pending) ?: return this
    return (pending as JsonArray).fold(copy(replaceableAdmissions = offered)) { state, row ->
        val admission = row as? JsonObject
        val submission = admission.string("input_id")
        val edit = submission?.let { state.unansweredQueuedEdits[it] }
        val admitted = admission.string("admission_id")
        if (submission == null || edit == null || admitted == null) {
            state
        } else {
            state.withQueuedEdit(edit.key, edit.replaced, edit.text, admitted, submission)
                .copy(unansweredQueuedEdits = state.unansweredQueuedEdits - submission)
        }
    }
}

/**
 * Queued prompt [key] after the gateway admitted its edit, which [replaced] the prompt's admission, as [admission]
 * (sent as [submission]): the new text, and, like the gateway, at the back of the queue. One whose turn already began
 * isn't waiting any more. A prompt that no longer holds [replaced] (another chat's, after a switch) is left alone.
 */
internal fun ChatState.withQueuedEdit(
    key: String,
    replaced: String,
    text: String,
    admission: String,
    submission: String,
): ChatState {
    val index = messages.indexOfFirst { it.key == key }
    val prompt = messages.getOrNull(index) as? ChatMessage.User ?: return this
    if (prompt.admissionId != replaced) return this
    val started = admission in startedAdmissions
    val edited = prompt.copy(text = text, sentText = text, admissionId = admission, submissionId = submission, queued = !started)
    val rest = messages.toMutableList().apply { removeAt(index) }
    // The back of the queue: after the last prompt still waiting, or where it was if it's the only one.
    val lastQueued = rest.indexOfLast { it is ChatMessage.User && it.queued }
    val at = if (lastQueued >= index) lastQueued + 1 else index
    rest.add(at, edited)
    return copy(messages = rest, replaceableAdmissions = replaceableAdmissions - prompt.admissionId.orEmpty())
}

/** The queued-row verb `prompt.replace_queued` serves (`CanonicalPendingAdmission.controls`). */
private const val REPLACE_CONTROL = "replace"

/**
 * A session authority `message.complete` that settles a queued admission cancelled before it ran: its own
 * `admission_id`, no execution generation, outcome `cancelled`, and no turn of it ever started. The turn running goes on.
 */
internal fun GatewayEvent.isQueuedSettlement(startedAdmissions: List<String>): Boolean {
    val admission = admissionId ?: return false
    return executionGeneration == null && admission !in startedAdmissions &&
        (payload as? JsonObject).string("outcome") == "cancelled"
}
