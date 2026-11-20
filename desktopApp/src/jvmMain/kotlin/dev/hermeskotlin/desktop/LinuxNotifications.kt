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
    val answer: (ApprovalChoice) -> Unit,
)

/** The open chat's Linux notices. All methods run on the same UI dispatcher as chat ownership changes. */
internal class LinuxNotifications(
    private val delivery: NotificationDelivery,
    private val actionLabels: () -> Pair<String, String>,
    private val fallback: (DesktopNotice, LinuxNoticeOwner) -> Unit = { _, _ -> },
    private val settings: () -> AppSettings,
) : AutoCloseable {
    private data class Entry(val notice: DesktopNotice, val owner: LinuxNoticeOwner, val actions: Map<String, ApprovalChoice?>)
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    fun post(notice: DesktopNotice, owner: LinuxNoticeOwner) {
        reconcile()
        if (closed || !owner.current() || !enabled(notice)) return
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
        if (closed || !entry.owner.current() || !enabled(entry.notice)) {
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
        entries.filterValues { !it.owner.current() || !enabled(it.notice) }.keys.toList().forEach(::withdraw)
    }

    fun failed(id: String, defaultAction: String) {
        if (defaultAction !in (entries[id]?.actions ?: return)) return
        fallback(id)
    }

    private fun fallback(id: String) {
        val entry = entries[id] ?: return
        withdraw(id)
        if (!closed && entry.owner.current() && enabled(entry.notice)) fallback(entry.notice, entry.owner)
    }

    fun unavailable() = entries.keys.toList().forEach(::fallback)

    override fun close() {
        if (closed) return
        closed = true
        entries.keys.toList().forEach(::withdraw)
        delivery.close()
    }

    private fun enabled(notice: DesktopNotice): Boolean = settings().let {
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
