package dev.hermeskotlin.desktop

import dev.hermeskotlin.desktop.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.Background")
internal interface BackgroundWire : DBusInterface {
    fun RequestBackground(parent: String, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
internal interface BackgroundRequest : DBusInterface {
    fun Close()
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) :
        DBusSignal(path, response, results)
}

/** The portal, not a host helper, owns the login entry. Each operation owns its connection and request. */
internal class BackgroundPortal(private val address: String? = null, private val responseTimeoutMillis: Long = 300_000) {
    suspend fun available(): Boolean = try {
        if (!supported()) false else runInterruptible(Dispatchers.IO) {
            connection { bus ->
                val properties = bus.getRemoteObject(owner(bus), PATH, Properties::class.java)
                val result = bus.call<Any>(properties, "Get", "org.freedesktop.portal.Background", "version")
                val version = (result as? Variant<*>)?.value ?: result
                version is UInt32 && version.toLong() >= 1
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }

    suspend fun request(enabled: Boolean): StartupGrant? {
        val reason = getString(if (enabled) Res.string.startup_enable_reason else Res.string.startup_disable_reason)
        return runInterruptible(Dispatchers.IO) {
            check(supported()) { "Background startup requires Linux Flatpak." }
            connection { bus ->
                val owner = owner(bus)
                val token = "kyllene" + UUID.randomUUID().toString().replace("-", "")
                val expected = "$PATH/request/${bus.uniqueName.substring(1).replace('.', '_')}/$token"
                var path = expected
                var dispatched = false
                var terminal = false
                val result = CompletableFuture<BackgroundRequest.Response>()
                val subscription = bus.addSigHandler(BackgroundRequest.Response::class.java, owner) {
                    if (it.path == expected) result.complete(it)
                }
                try {
                    val portal = bus.getRemoteObject(owner, PATH, BackgroundWire::class.java)
                    val options = mapOf(
                        "handle_token" to Variant(token),
                        "reason" to Variant(reason),
                        "autostart" to Variant(enabled),
                        "commandline" to Variant(listOf("kyllene", "--background"), "as"),
                        "dbus-activatable" to Variant(false),
                    )
                    dispatched = true
                    path = bus.call<DBusPath>(portal, "RequestBackground", "", options).path
                    check(path == expected) { "Unexpected background request handle." }
                    val response = result.get(responseTimeoutMillis, TimeUnit.MILLISECONDS)
                    terminal = true
                    if (response.response.toLong() != 0L) null else {
                        val background = response.results["background"]?.value
                        val autostart = response.results["autostart"]?.value
                        check(background is Boolean && autostart is Boolean) { "Incomplete background response." }
                        StartupGrant(background, autostart)
                    }
                } finally {
                    val interrupted = Thread.interrupted()
                    try {
                        if (dispatched && !terminal) runCatching {
                            val request = bus.getRemoteObject(owner, path, BackgroundRequest::class.java)
                            bus.call<Void>(request, "Close", timeout = 500)
                        }
                    } finally {
                        subscription.close()
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    private fun supported() = System.getProperty("os.name") == "Linux" && (address != null || File("/.flatpak-info").isFile)
    private fun <T> connection(block: (DBusConnection) -> T): T {
        val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
        builder.withShared(false).transportConfig().withTimeout(3_000)
        return builder.build().use(block)
    }
    private fun owner(bus: DBusConnection): String {
        val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        if (!bus.call<Boolean>(daemon, "NameHasOwner", NAME)) bus.call<UInt32>(daemon, "StartServiceByName", NAME, UInt32(0))
        return bus.call(daemon, "GetNameOwner", NAME)
    }
    private fun <T> DBusConnection.call(target: DBusInterface, method: String, vararg args: Any, timeout: Long = 3_000): T {
        val result = CompletableFuture<T>()
        callWithCallback(target, method, object : CallbackHandler<T> {
            override fun handle(value: T) { result.complete(value) }
            override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
        }, *args)
        return result.get(timeout, TimeUnit.MILLISECONDS)
    }
    private companion object {
        const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
    }
}
