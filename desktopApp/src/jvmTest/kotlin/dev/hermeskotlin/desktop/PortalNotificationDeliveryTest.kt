package dev.hermeskotlin.desktop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The delivery against a stand-in portal on a bus of its own (Linux with dbus-daemon, as [FlatpakPortalFixture] says). */
class PortalNotificationDeliveryTest {
    private class Portal(val bus: DBusConnection) : NotificationPortal, Properties {
        val added = LinkedBlockingQueue<Pair<String, Map<String, Variant<*>>>>()
        val removed = LinkedBlockingQueue<String>()
        override fun getObjectPath() = PATH
        override fun AddNotification(id: String, notification: Map<String, Variant<*>>) { added += id to notification }
        override fun RemoveNotification(id: String) { removed += id }
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(iface: String, property: String): A = UInt32(2) as A
        override fun <A> Set(iface: String, property: String, value: A) = error("Not supported")
        override fun GetAll(iface: String): Map<String, Variant<*>> = emptyMap()
    }

    /** A bus, the portals standing on it and the delivery under test, all closed whatever the test did. */
    private class Scene(val fixture: FlatpakPortalFixture) {
        val actions = LinkedBlockingQueue<Pair<String, String>>()
        val delivery = PortalNotificationDelivery(onAction = { id, action -> actions += id to action }, onFailure = { _, _ -> }, address = fixture.address)
        private val buses = mutableListOf<DBusConnection>()

        /** A portal taking the name: the first, or the one that comes up after [Portal.bus] of the last was closed. */
        fun portal(): Portal = fixture.connect().also { buses += it }.let { bus ->
            Portal(bus).also { bus.exportObject(it); bus.requestBusName(NAME) }
        }

        fun close() {
            delivery.close()
            buses.forEach { runCatching { it.close() } }
        }
    }

    private fun scene(test: Scene.() -> Unit) = FlatpakPortalFixture().use { fixture ->
        val scene = Scene(fixture)
        try { scene.test() } finally { scene.close() }
    }

    private val notice = PortalNotice("Release", "Done", "open-1", listOf("Approve" to "once-1"))

    private fun <T> LinkedBlockingQueue<T>.next(): T? = poll(3, TimeUnit.SECONDS)

    @Test
    fun aNoticeIsShownItsActionComesBackAndClosingWithdrawsIt() = scene {
        val portal = portal()
        delivery.add("one", notice)
        val (id, payload) = checkNotNull(portal.added.next())
        assertEquals("one", id)
        assertEquals("Release", payload.getValue("title").value)
        assertEquals("open-1", payload.getValue("default-action").value)
        portal.bus.sendMessage(NotificationPortal.ActionInvoked(PATH, "one", "once-1", emptyList()))
        assertEquals("one" to "once-1", actions.next())
        // One never shown has nothing to take down.
        delivery.remove("never")
        delivery.close()
        assertEquals("one", portal.removed.next())
        assertNull(portal.removed.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aNoticeShownBeforeThePortalRestartedIsStillWithdrawn() = scene {
        val first = portal()
        delivery.add("approval", notice)
        assertEquals("approval", first.added.next()?.first)
        // The portal goes and another takes its name; the desktop still shows the notice.
        first.bus.close()
        val restarted = portal()
        // Another notice binds the delivery to the new portal (again, should the first try race the owner change).
        var bound = false
        for (attempt in 1..10) {
            delivery.add("reply", notice)
            if (restarted.added.poll(500, TimeUnit.MILLISECONDS) != null) { bound = true; break }
        }
        assertTrue(bound)
        // The approval was answered in the app: its notice comes down through the new portal.
        delivery.remove("approval")
        assertEquals("approval", restarted.removed.next())
    }

    @Test
    fun quittingAfterThePortalRestartedWithdrawsThroughTheNewOne() = scene {
        val first = portal()
        delivery.add("approval", notice)
        assertEquals("approval", first.added.next()?.first)
        first.bus.close()
        val restarted = portal()
        delivery.close()
        assertEquals("approval", restarted.removed.next())
    }

    private companion object {
        const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
    }
}
