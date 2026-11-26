package dev.hermeskotlin.desktop

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundPortalTest {
    @Test fun `availability checks version without requesting permission`() = fixture { service, portal ->
        assertTrue(portal.available())
        service.version = UInt32(0)
        assertEquals(false, portal.available())
        assertTrue(service.options.isEmpty())
    }

    @Test fun `early response needs the actual owner and path and explicit startup command`() = withLocale("en") { fixture { service, portal ->
        assertEquals(StartupGrant(true, true), portal.request(true))
        assertEquals(true, service.options["autostart"]?.value)
        assertEquals(listOf("kyllene", "--background"), service.options["commandline"]?.value)
        assertEquals(false, service.options["dbus-activatable"]?.value)
        assertEquals(StartupGrant(true, true), portal.request(false))
        assertEquals(false, service.options["autostart"]?.value)
        assertEquals("Stop starting Kyllene automatically when you sign in.", service.options["reason"]?.value)
    } }

    @Test fun `decline returns no grant and missing result fields never imply disable`() = fixture { service, portal ->
        service.code = 1
        assertNull(portal.request(true))
        service.code = 0
        service.results = mapOf("background" to Variant(true))
        assertFails { portal.request(false) }
    }

    @Test fun `changed request handle is closed without accepting its grant`() = fixture { service, portal ->
        service.changedHandle = true
        assertFails { portal.request(true) }
        service.closed.get(3, TimeUnit.SECONDS)
    }

    @Test fun `cancellation closes a pending request`() = fixture { service, portal ->
        service.pending = true
        val request = async(Dispatchers.IO) { portal.request(true) }
        service.started.get(3, TimeUnit.SECONDS)
        request.cancelAndJoin()
        service.closed.get(3, TimeUnit.SECONDS)
        assertTrue(request.isCancelled)
    }

    @Test fun `response timeout closes the owned request`() = fixture { service, portal ->
        service.pending = true
        assertFails { portal.request(true) }
        service.closed.get(3, TimeUnit.SECONDS)
    }

    @Test fun `permission reasons follow the system language`() = withLocale("es") {
        fixture { service, portal ->
            portal.request(true)
            assertEquals("Iniciar Kyllene en la bandeja al iniciar sesión.", service.options["reason"]?.value)
            portal.request(false)
            assertEquals("Dejar de iniciar Kyllene automáticamente al iniciar sesión.", service.options["reason"]?.value)
        }
    }

    private fun fixture(test: suspend kotlinx.coroutines.CoroutineScope.(Fixture, BackgroundPortal) -> Unit) {
        assumeTrue("Background portal integration requires Linux", System.getProperty("os.name") == "Linux")
        assumeTrue("Background portal integration requires dbus-daemon", java.io.File("/usr/bin/dbus-daemon").canExecute())
        val config = Files.createTempFile("background-portal-", ".conf")
        Files.writeString(config, """<busconfig><type>session</type><listen>unix:tmpdir=/tmp</listen>
            <auth>EXTERNAL</auth><policy context="default"><allow own="*"/>
            <allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>""")
        val daemon = try {
            ProcessBuilder("/usr/bin/dbus-daemon", "--config-file=$config", "--nofork", "--print-address=1").start()
        } catch (failure: Throwable) { Files.deleteIfExists(config); throw failure }
        try {
            val address = daemon.inputStream.bufferedReader().readLine()
            DBusConnectionBuilder.forAddress(address).withShared(false).build().use { bus ->
                val names = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).ListActivatableNames()
                assertTrue(names.all { it == "org.freedesktop.DBus" }, "Private bus must not activate desktop services")
                bus.requestBusName("org.freedesktop.portal.Desktop")
                val service = Fixture(bus, address)
                bus.exportObject(service)
                runBlocking { test(service, BackgroundPortal(address, 750)) }
            }
        } finally {
            daemon.destroy()
            try {
                if (!daemon.waitFor(3, TimeUnit.SECONDS)) { daemon.destroyForcibly(); daemon.waitFor() }
            } finally { Files.deleteIfExists(config) }
        }
    }

    class Fixture(private val bus: DBusConnection, private val address: String) : BackgroundWire, BackgroundRequest, Properties {
        var version = UInt32(1)
        var options = emptyMap<String, Variant<*>>()
        var results = mapOf("background" to Variant(true), "autostart" to Variant(true))
        var code = 0
        var pending = false
        var changedHandle = false
        val started = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        override fun getObjectPath() = "/org/freedesktop/portal/desktop"
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(iface: String, property: String): A {
            assertEquals("org.freedesktop.portal.Background", iface)
            assertEquals("version", property)
            return version as A
        }
        override fun <A> Set(iface: String, property: String, value: A) { error("Unexpected property write") }
        override fun GetAll(iface: String): Map<String, Variant<*>> = mapOf("version" to Variant(version))
        override fun RequestBackground(parent: String, options: Map<String, Variant<*>>): DBusPath {
            this.options = options
            assertEquals("", parent)
            val sender = AbstractConnection.getCallInfo().source.substring(1).replace('.', '_')
            val expected = "$objectPath/request/$sender/${options.getValue("handle_token").value}"
            val returned = if (changedHandle) expected + "_changed" else expected
            bus.exportObject(returned, this)
            if (!pending) {
                bus.sendMessage(BackgroundRequest.Response(expected + "_other", UInt32(1), emptyMap()))
                DBusConnectionBuilder.forAddress(address).withShared(false).build().use { other ->
                    other.sendMessage(BackgroundRequest.Response(expected, UInt32(1), emptyMap()))
                    other.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetId()
                }
                bus.sendMessage(BackgroundRequest.Response(expected, UInt32(code.toLong()), results))
            }
            started.complete(Unit)
            return DBusPath(returned)
        }
        override fun Close() { closed.complete(Unit) }
    }
}
