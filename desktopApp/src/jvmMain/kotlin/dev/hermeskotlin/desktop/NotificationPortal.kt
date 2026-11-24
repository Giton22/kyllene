package dev.hermeskotlin.desktop

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.Notification")
internal interface NotificationPortal : DBusInterface {
    fun AddNotification(id: String, notification: Map<String, Variant<*>>)
    fun RemoveNotification(id: String)

    class ActionInvoked(path: String, val id: String, val action: String, val parameter: List<Variant<*>>) :
        DBusSignal(path, id, action, parameter)

    class Icon(@field:Position(0) val kind: String, @field:Position(1) val value: Variant<*>) : Struct()
}

internal fun notificationPayload(notice: PortalNotice, appId: String = System.getenv("FLATPAK_ID") ?: "app.kyllene.Kyllene"): Map<String, Variant<*>> = mapOf(
    "title" to Variant(notice.title),
    "body" to Variant(notice.body),
    "icon" to Variant(NotificationPortal.Icon("themed", Variant(listOf(appId, "dialog-information"), "as"))),
    "default-action" to Variant(notice.defaultAction),
    "buttons" to Variant(notice.buttons.map { (label, action) -> mapOf("label" to Variant(label), "action" to Variant(action)) }, "aa{sv}"),
)

/** No action targets or inline replies are sent; version 2 may still attach its platform-data dictionary. */
internal fun notificationParameters(parameters: List<Variant<*>>): Boolean {
    if (parameters.isEmpty()) return true
    if (parameters.size != 1 || parameters.single().sig != "a{sv}") return false
    val data = parameters.single().value as? Map<*, *> ?: return false
    return data.all { (key, value) ->
        key == "activation-token" && value is Variant<*> && value.value is String && (value.value as String).length <= 4096
    }
}
