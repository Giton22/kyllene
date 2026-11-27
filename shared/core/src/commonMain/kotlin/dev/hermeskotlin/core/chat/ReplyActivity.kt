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
 * reply this turn didn't stream, under a key the start doesn't have, is the old one when its words are the same,
 * unless the chat now has more such replies than it had at the start: a routine's "Nothing new." read back on a
 * reconnect is a new one. (Times can't tell them apart: the read dates the old reply by the turn's later steps.)
 */
fun ChatState.newFinishedReply(atStart: ChatState?, previous: ChatState? = atStart): ChatMessage.Assistant? {
    val reply = messages.lastOrNull() as? ChatMessage.Assistant ?: return null
    val before = atStart?.messages ?: return reply
    val streamed = previous?.messages?.any { it.key == reply.key && it is ChatMessage.Assistant && it.streaming } == true
    val old = before.lastOrNull { it.key == reply.key } as? ChatMessage.Assistant
        ?: (before.lastOrNull { it is ChatMessage.Assistant && !it.streaming } as? ChatMessage.Assistant)
            ?.takeUnless { old -> streamed || messages.count { it.sameReplyAs(old) } > before.count { it.sameReplyAs(old) } }
        ?: return reply
    return reply.takeUnless { it.sameReplyAs(old) }
}

/** A finished reply with [old]'s words and outcome. */
private fun ChatMessage.sameReplyAs(old: ChatMessage.Assistant): Boolean =
    this is ChatMessage.Assistant && !streaming && !old.streaming && text == old.text && outcome == old.outcome && error == old.error
