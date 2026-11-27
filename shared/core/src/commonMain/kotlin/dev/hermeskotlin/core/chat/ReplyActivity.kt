package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SEEN_SLACK_SECONDS
import dev.hermeskotlin.core.sessions.SessionMessage

/** Reply notifications require words the user can read. */
fun String.replyTextOrNull(): String? = trim().takeIf { it.isNotEmpty() && !TranscriptRows.isSilence(it) }

/** A reply in display history, including visible rows archived by compaction. */
fun SessionMessage.readableReplyText(): String? {
    if (role != "assistant" || isHidden || displayKind == "failed_turn" ||
        storedToolCalls.isNotEmpty() || TranscriptRows.compaction(text) != null
    ) return null
    return text.replyTextOrNull()
}

/** A bot's written reply or a message addressed to it by another bot can make its chat unread. */
fun List<SessionMessage>.hasUnreadBotMessage(readUpTo: Double): Boolean = any { row ->
    val at = row.timestamp ?: return@any false
    at > readUpTo + SEEN_SLACK_SECONDS && (row.readableReplyText() != null ||
        row.role == "user" && !row.isHidden &&
            AgentMessage.parse(row.text, requireEnvelope = row.displayKind == "internal_notification") != null)
}

/**
 * The reply a turn that just ended gave, from the chat as it was when the turn began ([atStart]).
 *
 * A silent completion can leave the previous, already finished reply at the end of the chat: Bot Chat's
 * `message.complete` carries no text for one, so the turn's empty bubble goes and the reply before it is last again.
 * That reply was there, finished, when the turn began. Measured from the start, not from the state just before the
 * end ([previous]): a reconnect reads the stored transcript, this turn's reply finished in it, before it says the turn
 * is over.
 *
 * A history read during the turn (a reconnect, a turn that isn't ours) gives the old reply its stored row's key. A
 * reply this turn didn't stream, under a key the start doesn't have, is the old one when its words are the same and
 * it didn't finish later than the old one did (a routine's "Nothing new." read back on a reconnect is a new reply).
 */
fun ChatState.newFinishedReply(atStart: ChatState?, previous: ChatState? = atStart): ChatMessage.Assistant? {
    val reply = messages.lastOrNull() as? ChatMessage.Assistant ?: return null
    val before = atStart?.messages ?: return reply
    val streamed = previous?.messages?.any { it.key == reply.key && it is ChatMessage.Assistant && it.streaming } == true
    val old = before.lastOrNull { it.key == reply.key } as? ChatMessage.Assistant
        ?: (before.lastOrNull { it is ChatMessage.Assistant && !it.streaming } as? ChatMessage.Assistant)
            ?.takeUnless { streamed || reply.finishedAfter(it) }
        ?: return reply
    return reply.takeUnless {
        !old.streaming && old.text == it.text && old.outcome == it.outcome && old.error == it.error
    }
}

/**
 * Whether this reply finished well after [other]. A live reply's time is this device's clock and a stored row's the
 * gateway's, so the two may be a little apart for the same reply.
 */
private fun ChatMessage.Assistant.finishedAfter(other: ChatMessage.Assistant): Boolean {
    val at = timestamp ?: return false
    val then = other.timestamp ?: return false
    return at > then + SAME_REPLY_CLOCK_SLACK_SECONDS
}

private const val SAME_REPLY_CLOCK_SLACK_SECONDS = 60.0
