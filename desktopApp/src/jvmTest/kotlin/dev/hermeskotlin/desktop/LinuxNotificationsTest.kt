package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LinuxNotificationsTest {
    private class Delivery : NotificationDelivery {
        val shown = linkedMapOf<String, PortalNotice>()
        val removed = mutableListOf<String>()
        override fun add(id: String, notice: PortalNotice) { shown[id] = notice }
        override fun remove(id: String) { removed += id; shown.remove(id) }
        override fun close() = Unit
    }

    private val delivery = Delivery()
    private var valid = true
    private var prefs = AppSettings()
    private var opened = 0
    private val answers = mutableListOf<ApprovalChoice>()
    private val owner = LinuxNoticeOwner("https://example.invalid", "work", { valid }, { opened++ }, { answers += it })
    private var labels = "Approve" to "Deny"
    private val notifications = LinuxNotifications(delivery, actionLabels = { labels }) { prefs }
    private val reply = DesktopNotice("Release", "```\r\nplain <b>text</b>", "s1", "Release")

    @Test
    fun repeatedNoticesUpdateOneScopedIdWithFreshActions() {
        notifications.post(reply, owner)
        val id = delivery.shown.keys.single()
        val first = delivery.shown.getValue(id)
        notifications.post(reply.copy(body = "updated"), owner)
        assertEquals(setOf(id), delivery.shown.keys)
        assertEquals("updated", delivery.shown.getValue(id).body)
        notifications.action(id, first.defaultAction)
        assertEquals(0, opened)
        notifications.action(id, delivery.shown.getValue(id).defaultAction)
        assertEquals(1, opened)
        notifications.action(id, first.defaultAction)
        assertEquals(1, opened)
    }

    @Test
    fun gatewayAndProfileAndSessionAreSeparateOwners() {
        notifications.post(reply, owner)
        val first = delivery.shown.keys.single()
        notifications.post(reply, owner.copy(profile = "other"))
        notifications.post(reply, owner.copy(gateway = "https://elsewhere.invalid"))
        notifications.post(reply.copy(sessionId = "s2"), owner)
        assertEquals(4, delivery.shown.size)
        assertTrue(delivery.shown.keys.all { it.matches(Regex("kyllene-[a-f0-9]{64}")) })
        assertNotEquals(first, delivery.shown.keys.last())
        assertEquals(reply.body, delivery.shown.getValue(first).body)
    }

    @Test
    fun obsoleteSourceCannotPostOrActivateEvenBeforeReconcile() {
        notifications.post(reply, owner)
        val (id, notice) = delivery.shown.entries.single()
        valid = false
        notifications.action(id, notice.defaultAction)
        notifications.post(reply, owner)
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun approvalCanOnlyBeAnsweredOnce() {
        val approval = InputRequest.Approval("q1", "echo hello", "Run command", null, listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        notifications.post(reply.copy(request = approval), owner)
        val (id, notice) = delivery.shown.entries.single()
        assertEquals(listOf("Approve", "Deny"), notice.buttons.map { it.first })
        notifications.action(id, notice.buttons.first().second)
        notifications.action(id, notice.buttons.last().second)
        assertEquals(listOf(ApprovalChoice.Once), answers)
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun denyingAnApprovalDoesNotOpenItsSource() {
        val request = InputRequest.Approval("deny", "example", "Run command", null, listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        notifications.post(reply.copy(request = request), owner)
        val (id, notice) = delivery.shown.entries.single()
        notifications.action(id, notice.buttons.last().second)
        notifications.action(id, notice.buttons.last().second)
        assertEquals(listOf(ApprovalChoice.Deny), answers)
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun deliveryAndOwnerExpiryDoNotOpenTheSource() {
        notifications.post(reply, owner)
        assertEquals(0, opened)
        valid = false
        notifications.reconcile()
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun approvalButtonsUseTheCurrentApplicationLanguage() {
        val approval = InputRequest.Approval("q1", "echo hello", "Run command", null, listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        labels = "Einmal freigeben" to "Ablehnen"
        notifications.post(reply.copy(request = approval), owner)
        assertEquals(listOf(labels.first, labels.second), delivery.shown.values.single().buttons.map { it.first })
    }

    @Test
    fun disablingPreferencesWithdrawsAndRejectsQueuedActions() {
        notifications.post(reply, owner)
        val (id, notice) = delivery.shown.entries.single()
        prefs = prefs.copy(notifyReplies = false)
        notifications.action(id, notice.defaultAction)
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
        notifications.post(reply, owner)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun reconcileAndCloseWithdrawOwnedNotices() {
        notifications.post(reply, owner)
        valid = false
        notifications.reconcile()
        assertTrue(delivery.shown.isEmpty())
        valid = true
        notifications.post(reply, owner)
        notifications.close()
        notifications.post(reply, owner)
        assertTrue(delivery.shown.isEmpty())
        assertEquals(2, delivery.removed.size)
    }

    @Test
    fun identityReplacementDoesNotReviveWhenProfileReturnsToItsOriginalName() {
        val original = Any()
        var active = original
        val captured = owner.copy(current = { active === original })
        notifications.post(reply, captured)
        val (id, notice) = delivery.shown.entries.single()
        active = Any() // Another profile's chat.
        active = Any() // A newly opened chat in the original profile.
        notifications.post(reply, captured) // Delayed producer from the original source.
        notifications.action(id, notice.defaultAction)
        assertEquals(0, opened)
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun answeredRequestsWithdrawWithoutSendingAnAnswer() {
        val approval = InputRequest.Approval("q1", "echo hello", "Run command", null, listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        notifications.post(reply.copy(request = approval), owner)
        val (id, notice) = delivery.shown.entries.single()
        valid = false // The exact request is no longer pending in the source.
        notifications.reconcile()
        notifications.action(id, notice.buttons.first().second)
        assertTrue(answers.isEmpty())
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun nullAndEmptyProfileCannotCollideAndRetainedNoticesAreBounded() {
        notifications.post(reply, owner.copy(profile = null))
        notifications.post(reply, owner.copy(profile = ""))
        assertEquals(2, delivery.shown.size)
        repeat(40) { notifications.post(reply.copy(sessionId = "s$it"), owner) }
        assertEquals(32, delivery.shown.size)
        notifications.close()
        assertTrue(delivery.shown.isEmpty())
    }

    @Test
    fun delayedFailureCannotWithdrawAReplacementWithTheSameId() {
        var fallback = 0
        val notices = LinuxNotifications(delivery, actionLabels = { labels }, fallback = { _, _ -> fallback++ }) { prefs }
        notices.post(reply, owner)
        val id = delivery.shown.keys.single()
        val first = delivery.shown.getValue(id)
        notices.post(reply.copy(body = "Replacement"), owner)
        val replacement = delivery.shown.getValue(id)
        notices.failed(id, first.defaultAction)
        assertEquals(replacement, delivery.shown.getValue(id))
        assertTrue(delivery.removed.isEmpty())
        assertEquals(0, fallback)
        notices.action(id, replacement.defaultAction)
        assertEquals(1, opened)
    }

    @Test
    fun failedDeliveryFallsBackOnlyWhileItsExactSourceIsStillCurrent() {
        var fallback = 0
        val notices = LinuxNotifications(delivery, actionLabels = { labels }, fallback = { _, captured -> if (captured.current()) fallback++ }) { prefs }
        notices.post(reply, owner)
        val (firstId, first) = delivery.shown.entries.single()
        notices.failed(firstId, first.defaultAction)
        assertEquals(1, fallback)
        notices.post(reply, owner)
        val id = delivery.shown.keys.single()
        val notice = delivery.shown.getValue(id)
        valid = false
        notices.failed(id, notice.defaultAction)
        assertEquals(1, fallback)
    }
}
