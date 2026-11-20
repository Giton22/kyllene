package dev.hermeskotlin.desktop

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** A private connection and finite worker lifetime; portal availability never blocks Compose. */
internal class PortalNotificationDelivery(
    private val onAction: (String, String) -> Unit,
    private val onFailure: (String, String) -> Unit,
    private val onUnavailable: () -> Unit = {},
    private val address: String? = null,
) : NotificationDelivery {
    private data class Update(val id: String, val notice: PortalNotice?)
    private class Binding(val owner: String, val portal: NotificationPortal) {
        val valid = AtomicBoolean(true)
        var subscription: AutoCloseable? = null
        val sent = linkedSetOf<String>()
    }
    private val alive = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Binding?>()
    private val updates = ArrayBlockingQueue<Update>(128)
    private val worker = thread(name = "kyllene-notification-portal", isDaemon = true) { run() }

    override fun add(id: String, notice: PortalNotice) = submit(Update(id, notice))
    override fun remove(id: String) = submit(Update(id, null))

    private fun submit(update: Update) {
        if (!alive.get()) {
            if (!closed.get() && update.notice != null) fail(update.id, update.notice)
            return
        }
        if (alive.get() && !updates.offer(update)) {
            // A stalled backend must not accumulate an unbounded queue of private message text.
            alive.set(false)
            SwingUtilities.invokeLater { if (!closed.get()) onUnavailable() }
            worker.interrupt()
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        alive.set(false)
        worker.interrupt()
        worker.join(1_000)
    }

    private fun fail(id: String, notice: PortalNotice) {
        SwingUtilities.invokeLater { if (!closed.get()) onFailure(id, notice.defaultAction) }
    }

    private fun run() {
        if (System.getProperty("os.name") != "Linux") return
        var bus: DBusConnection? = null
        var binding: Binding? = null
        var owners: AutoCloseable? = null
        try {
            while (alive.get()) {
                val update = updates.take()
                try {
                    if (update.notice == null && binding?.sent?.contains(update.id) != true) continue
                    if (bus == null) {
                        // Authentication needs this module even when a trimmed runtime otherwise starts normally.
                        Class.forName("com.sun.security.auth.module.UnixSystem")
                        val builder = if (address == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(address)
                        builder.withShared(false).transportConfig().withTimeout(2_000)
                        bus = builder.build()
                        owners = bus.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                            if (signal.source == "org.freedesktop.DBus" && signal.path == "/org/freedesktop/DBus" && signal.name == NAME) {
                                active.get()?.takeIf { it.owner == signal.oldOwner && it.owner != signal.newOwner }?.valid?.set(false)
                            }
                        }
                    }
                    val connection = bus
                    if (binding?.valid?.get() != true) {
                        binding?.valid?.set(false)
                        binding?.subscription?.close()
                        binding?.let { withdraw(connection, it) }
                        binding = bind(connection)
                    }
                    val target = checkNotNull(binding)
                    if (update.notice == null) {
                        call<Void>(connection, target.portal, "RemoveNotification", update.id)
                        target.sent.remove(update.id)
                    } else {
                        // Retain ownership before sending: even an interrupted reply needs withdrawal on shutdown.
                        target.sent.add(update.id)
                        call<Void>(connection, target.portal, "AddNotification", update.id, notificationPayload(update.notice))
                    }
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (_: Exception) {
                    if (update.notice != null) fail(update.id, update.notice)
                } catch (_: LinkageError) {
                    if (update.notice != null) fail(update.id, update.notice)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            updates.clear()
            binding?.valid?.set(false)
            active.getAndSet(null)?.valid?.set(false)
            runCatching { owners?.close() }
            runCatching { binding?.subscription?.close() }
            val connection = bus
            val target = binding
            if (connection != null && target != null) {
                withdraw(connection, target)
            }
            runCatching { bus?.close() }
        }
    }

    private fun withdraw(bus: DBusConnection, target: Binding) {
        // Dispatch all withdrawals before one shared deadline, rather than waiting once per notice.
        val interrupted = Thread.interrupted()
        try {
            val pending = target.sent.map { id -> runCatching { async<Void>(bus, target.portal, "RemoveNotification", id) }.getOrNull() }
            runCatching { CompletableFuture.allOf(*pending.filterNotNull().toTypedArray()).get(500, TimeUnit.MILLISECONDS) }
            target.sent.clear()
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun bind(bus: DBusConnection): Binding {
        val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        if (!call<Boolean>(bus, daemon, "NameHasOwner", NAME)) call<UInt32>(bus, daemon, "StartServiceByName", NAME, UInt32(0))
        val owner = call<String>(bus, daemon, "GetNameOwner", NAME)
        val properties = bus.getRemoteObject(owner, PATH, Properties::class.java)
        val version = call<Any>(bus, properties, "Get", "org.freedesktop.portal.Notification", "version")
        check(((version as? Variant<*>)?.value ?: version).let { it is UInt32 && it.toLong() >= 1 })
        val target = Binding(owner, bus.getRemoteObject(owner, PATH, NotificationPortal::class.java))
        active.set(target)
        target.subscription = bus.addSigHandler(NotificationPortal.ActionInvoked::class.java, owner) { signal ->
            if (signal.source == owner && signal.path == PATH && notificationParameters(signal.parameter)) {
                SwingUtilities.invokeLater {
                    if (alive.get() && target.valid.get()) onAction(signal.id, signal.action)
                }
            }
        }
        try {
            check(call<String>(bus, daemon, "GetNameOwner", NAME) == owner)
            return target
        } catch (failure: Exception) {
            target.valid.set(false)
            active.compareAndSet(target, null)
            target.subscription?.close()
            throw failure
        }
    }

    private fun <T> call(bus: DBusConnection, target: DBusInterface, method: String, vararg args: Any): T =
        async<T>(bus, target, method, *args).get(2, TimeUnit.SECONDS)

    private fun <T> async(bus: DBusConnection, target: DBusInterface, method: String, vararg args: Any): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        bus.callWithCallback(target, method, object : CallbackHandler<T> {
            override fun handle(value: T) { result.complete(value) }
            override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
        }, *args)
        return result
    }

    private companion object {
        const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
    }
}
