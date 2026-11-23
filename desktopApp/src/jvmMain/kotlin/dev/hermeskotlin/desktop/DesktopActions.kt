package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatSession

/** The desktop-only launcher action; other links retain their existing chat/notification routing. */
internal fun handleQuickEntryLink(link: String, onOpen: () -> Unit): Boolean {
    if (link.any { it.code > 0x7f }) return false
    if (link.lowercase() != "${AppLink.DESKTOP_SCHEME}://quick-entry") return false
    onOpen()
    return true
}

/** Finds the active session waiting on [answer] across the main window and any popped-out chat windows. */
internal fun findApprovalSession(sessions: Sequence<ChatSession?>, answer: NoticeAnswer): ChatSession? =
    findMatchingApprovalSession(
        sessions = sessions.filterNotNull(),
        sessionId = answer.sessionId,
        requestId = answer.request.id,
        storedSessionId = { it.state.value.storedSessionId },
        hasRequest = { session, id -> session.state.value.inputRequests.any { it.id == id } },
    )

internal fun <S> findMatchingApprovalSession(
    sessions: Sequence<S>,
    sessionId: String?,
    requestId: String,
    storedSessionId: (S) -> String?,
    hasRequest: (S, String) -> Boolean,
): S? = sessions.firstOrNull { session ->
    storedSessionId(session) == sessionId && hasRequest(session, requestId)
}

/** Index of the popped-out chat window currently showing [sessionId] (either by initial target or live session). */
internal fun <W> findPoppedChatIndex(
    windows: List<W>,
    sessionId: String,
    targetSessionId: (W) -> String?,
    liveSessionId: (W) -> String?,
): Int = windows.indexOfFirst { window ->
    targetSessionId(window) == sessionId || liveSessionId(window) == sessionId
}
