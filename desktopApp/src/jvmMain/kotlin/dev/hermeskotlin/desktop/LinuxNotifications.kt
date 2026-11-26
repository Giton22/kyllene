package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.settings.AppSettings
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal data class PortalNotice(
    val title: String,
    val body: String,
    val defaultAction: String,
    val buttons: List<Pair<String, String>>,
)

/** Delivery queues portal calls off the UI thread; actions come back onto the owner's UI thread. */
internal interface NotificationDelivery : AutoCloseable {
    fun add(id: String, notice: PortalNotice)
    fun remove(id: String)
}

/** Captured from the original chat, never reconstructed from the account selected when a click arrives. */
internal data class LinuxNoticeOwner(
    val gateway: String,
    val profile: String?,
    val current: () -> Boolean,
    val open: () -> Unit,
    val usesReplyPreference: Boolean = true,
    val answer: (ApprovalChoice) -> Unit,
)

/** The open chat's Linux notices. All methods run on the same UI dispatcher as chat ownership changes. */
internal class LinuxNotifications(
    private val delivery: NotificationDelivery,
    private val actionLabels: () -> Pair<String, String>,
    private val fallback: (DesktopNotice, LinuxNoticeOwner) -> Unit = { _, _ -> },
    private val nowNanos: () -> Long = System::nanoTime,
    private val settings: () -> AppSettings,
) : AutoCloseable {
    private data class Entry(val notice: DesktopNotice, val owner: LinuxNoticeOwner, val actions: Map<String, ApprovalChoice?>)
    private data class Reply(val gateway: String, val profile: String?, val session: String, val preview: String)
    private data class RecentReply(val reply: Reply, val fromPush: Boolean, val at: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val recentReplies = mutableListOf<RecentReply>()
    private var closed = false

    fun post(notice: DesktopNotice, owner: LinuxNoticeOwner, fromPush: Boolean = false) {
        reconcile()
        if (closed || !owner.current() || !enabled(notice, owner)) return
        if (matchesOtherReplySource(notice, owner, fromPush)) return
        val id = idFor(owner, notice)
        val nonce = UUID.randomUUID().toString()
        val open = "open-$nonce"
        val actions = linkedMapOf<String, ApprovalChoice?>(open to null)
        val approval = notice.request as? InputRequest.Approval
        val (approveOnce, deny) = actionLabels()
        val buttons = listOf(ApprovalChoice.Once to approveOnce, ApprovalChoice.Deny to deny)
            .filter { (choice, _) -> approval?.choices?.contains(choice) == true }
            .map { (choice, label) ->
                val action = "${choice.wire}-$nonce"
                actions[action] = choice
                label to action
            }
        entries[id] = Entry(notice, owner, actions)
        delivery.add(id, PortalNotice(notice.title, notice.body, open, buttons))
        while (entries.size > 32) withdraw(entries.keys.first())
    }

    fun action(id: String, action: String) {
        val entry = entries[id] ?: return
        if (closed || !entry.owner.current() || !enabled(entry.notice, entry.owner)) {
            withdraw(id)
            return
        }
        if (action !in entry.actions) return
        val choice = entry.actions.getValue(action)
        // Consume before opening UI or sending an answer: a second click cannot repeat either operation.
        withdraw(id)
        if (choice == null) entry.owner.open() else entry.owner.answer(choice)
    }

    fun reconcile() {
        entries.filterValues { !it.owner.current() || !enabled(it.notice, it.owner) }.keys.toList().forEach(::withdraw)
    }

    fun failed(id: String, defaultAction: String) {
        if (defaultAction !in (entries[id]?.actions ?: return)) return
        fallback(id)
    }

    private fun fallback(id: String) {
        val entry = entries[id] ?: return
        withdraw(id)
        if (!closed && entry.owner.current() && enabled(entry.notice, entry.owner)) fallback(entry.notice, entry.owner)
    }

    fun unavailable() = entries.keys.toList().forEach(::fallback)

    // The push payload and live events do not share a reply ID. Pair only matching previews
    // from opposite sources in a short window. Repeated replies from one source still notify.
    private fun matchesOtherReplySource(notice: DesktopNotice, owner: LinuxNoticeOwner, fromPush: Boolean): Boolean {
        val preview = notice.replyPreview ?: return false
        val session = notice.sessionId ?: return false
        val reply = Reply(owner.gateway, owner.profile?.takeUnless { it == "default" }, session, preview)
        val now = nowNanos()
        recentReplies.removeAll { now - it.at >= 15_000_000_000L }
        val match = recentReplies.indexOfFirst { it.fromPush != fromPush && it.reply == reply }
        if (match >= 0) {
            recentReplies.removeAt(match)
            return true
        }
        recentReplies += RecentReply(reply, fromPush, now)
        if (recentReplies.size > 32) recentReplies.removeAt(0)
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        recentReplies.clear()
        entries.keys.toList().forEach(::withdraw)
        delivery.close()
    }

    private fun enabled(notice: DesktopNotice, owner: LinuxNoticeOwner): Boolean = !owner.usesReplyPreference || settings().let {
        if (notice.request == null) it.notifyReplies else it.notifyRequests
    }

    private fun withdraw(id: String) {
        if (entries.remove(id) != null) delivery.remove(id)
    }

    private fun idFor(owner: LinuxNoticeOwner, notice: DesktopNotice): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length prefixes distinguish null, empty and unusual delimiters in gateway/profile/session ids.
        listOf(owner.gateway, owner.profile, notice.sessionId, notice.request?.id).forEach { part ->
            val bytes = part?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            bytes?.let(digest::update)
        }
        return "kyllene-" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
