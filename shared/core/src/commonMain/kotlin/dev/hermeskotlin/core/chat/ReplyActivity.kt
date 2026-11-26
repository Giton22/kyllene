package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SEEN_SLACK_SECONDS
import dev.hermeskotlin.core.sessions.SessionMessage

/** Reply notifications require words the user can read. */
fun String.replyTextOrNull(): String? = trim().takeIf { it.isNotEmpty() && !TranscriptRows.isSilence(it) }

/** A reply in stored history, excluding model-only rows and intermediate tool calls. */
fun SessionMessage.readableReplyText(): String? {
    if (role != "assistant" || isHidden || !active || compacted || displayKind == "failed_turn" ||
        storedToolCalls.isNotEmpty() || TranscriptRows.compaction(text) != null
    ) return null
    return text.replyTextOrNull()
}

/** A bot's written reply or a message addressed to it by another bot can make its chat unread. */
fun List<SessionMessage>.hasUnreadBotMessage(readUpTo: Double): Boolean = any { row ->
    val at = row.timestamp ?: return@any false
    at > readUpTo + SEEN_SLACK_SECONDS && (row.readableReplyText() != null ||
        row.role == "user" && row.active && !row.compacted && !row.isHidden && AgentMessage.parse(row.text.trim()) != null)
}

/** A silent completion can leave the previous, already finished reply at the end of the chat. */
fun ChatState.newFinishedReply(previous: ChatState?): ChatMessage.Assistant? {
    val reply = messages.lastOrNull() as? ChatMessage.Assistant ?: return null
    val old = previous?.messages?.lastOrNull() as? ChatMessage.Assistant ?: return reply
    return reply.takeUnless {
        !old.streaming && old.key == it.key && old.text == it.text &&
            old.outcome == it.outcome && old.error == it.error
    }
}
