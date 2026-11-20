package dev.hermeskotlin.desktop

import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPortalTest {
    @Test
    fun portalMethodsAndSignalUseThePublishedWireSignatures() {
        val add = NotificationPortal::class.java.methods.single { it.name == "AddNotification" }
        val remove = NotificationPortal::class.java.methods.single { it.name == "RemoveNotification" }
        assertEquals("sa{sv}", Marshalling.getDBusType(add.genericParameterTypes))
        assertEquals("s", Marshalling.getDBusType(remove.genericParameterTypes))
        assertEquals(Void.TYPE, add.returnType)
        assertEquals(Void.TYPE, remove.returnType)
        val signal = NotificationPortal.ActionInvoked::class.java.constructors.single()
        assertEquals("ssav", Marshalling.getDBusType(signal.genericParameterTypes.drop(1).toTypedArray()))
    }

    @Test
    fun payloadUsesPlainBodyThemedIconAndVersionOneButtons() {
        val text = "<b>plain</b>\r\n```code```"
        val payload = notificationPayload(PortalNotice("Title", text, "open-123", listOf("Approve" to "once-123")))
        assertEquals(text, payload.getValue("body").value)
        assertFalse("markup-body" in payload)
        assertEquals("open-123", payload.getValue("default-action").value)
        assertEquals("(sv)", payload.getValue("icon").sig)
        assertEquals("aa{sv}", payload.getValue("buttons").sig)
        assertFalse("sound" in payload)
        assertFalse("category" in payload)
    }

    @Test
    fun themedIconUsesTheInstalledApplicationIdentity() {
        val appId = "app.kyllene.Kyllene.NotificationFixture"
        val payload = notificationPayload(PortalNotice("Title", "Body", "open", emptyList()), appId)
        val icon = payload.getValue("icon").value as NotificationPortal.Icon
        assertEquals(listOf(appId, "dialog-information"), icon.value.value)
    }

    @Test
    fun onlyEmptyOrVersionTwoPlatformDataCanAccompanyOurTargetlessActions() {
        assertTrue(notificationParameters(emptyList()))
        assertTrue(notificationParameters(listOf(Variant(mapOf("activation-token" to Variant("token")), "a{sv}"))))
        assertTrue(notificationParameters(listOf(Variant(emptyMap<String, Variant<*>>(), "a{sv}"))))
        assertFalse(notificationParameters(listOf(Variant("unexpected target"))))
        assertFalse(notificationParameters(listOf(Variant(mapOf("response" to Variant("answer")), "a{sv}"))))
        assertFalse(notificationParameters(listOf(Variant(emptyMap<String, Variant<*>>(), "a{sv}"), Variant("response"))))
    }
}
