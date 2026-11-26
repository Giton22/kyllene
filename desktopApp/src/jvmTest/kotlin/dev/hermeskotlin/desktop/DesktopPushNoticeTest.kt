package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPushNoticeTest {
    @Test fun pushFallbacksUseTheAppLanguage() = runTest {
        val locale = Locale.getDefault()
        try {
            val ping = PushMessage("p", 100, PushMessage.PING)
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Push notifications are working.", desktopPushNotice(ping, { true })!!.notice.body)
            Locale.setDefault(Locale.forLanguageTag("es"))
            assertEquals("Las notificaciones push funcionan.", desktopPushNotice(ping, { true })!!.notice.body)
            val cron = PushMessage("c", 100, PushMessage.CRON_RUN, job = "job1", run = "2026-11-01_12-00-00", status = "failed", notify = true)
            assertEquals("La ejecución ha fallado.", desktopPushNotice(cron, { true })!!.notice.body)
            Locale.setDefault(Locale.FRENCH)
            assertEquals("Les notifications push fonctionnent.", desktopPushNotice(ping, { true })!!.notice.body)
        } finally { Locale.setDefault(locale) }
    }

    @Test fun aLocaleReadCannotReturnANoticeToAnOwnerRevokedAfterItsFirstCheck() = runTest {
        var current = true
        val shown = desktopPushNotice(PushMessage("p", 100, PushMessage.PING), {
            current.also { current = false }
        })
        assertNull(shown)
    }

    private val bot = PushMessage("m", 100, PushMessage.BOT_MESSAGE, bot = "worker", label = " Worker\u0000 ",
        session = "session:1", text = "\r\n```report```\r\n" + "w".repeat(700))

    @Test fun botRequiresASafeNamedDestinationAndClipsPlainText() = runTest {
        val notice = desktopPushNotice(bot, { true })!!
        assertEquals("Worker", notice.notice.title)
        assertEquals("```report```", notice.notice.body)
        assertEquals("worker", notice.profile)
        assertEquals(PushDestination.Bot("worker", "Worker", "session:1"), notice.destination)
        assertNull(notice.notice.origin)
        assertNull(notice.notice.request)
        assertEquals(200, desktopPushNotice(bot.copy(text = "w".repeat(700)), { true })!!.notice.body.length)
        for (bad in listOf(bot.copy(bot = "../x"), bot.copy(bot = ""), bot.copy(session = "x/y"), bot.copy(session = null))) {
            assertNull(desktopPushNotice(bad, { true }))
        }
    }

    @Test fun cronUsesTheSavedTitleAndRespectsNotifyAndForeground() = runTest {
        val cron = PushMessage("c", 100, PushMessage.CRON_RUN, bot = "worker", job = "job1",
            run = "2026-11-01_12-00-00", label = "Report", text = "First line\r\nnext", notify = true)
        val shown = desktopPushNotice(cron, { true }, jobTitle = "My job", notifyReplies = false)!!
        assertEquals("My job", shown.notice.title)
        assertEquals("First line", shown.notice.body)
        assertEquals(PushDestination.Inbox("worker/job1/2026-11-01_12-00-00"), shown.destination)
        assertNull(desktopPushNotice(cron.copy(notify = false), { true }))
        assertNull(desktopPushNotice(cron, { true }, foreground = true))
        assertNull(desktopPushNotice(cron.copy(job = "../bad"), { true }))
    }

    @Test fun hiddenOrForegroundRepliesAndDisabledReplyNoticesAreQuiet() = runTest {
        assertNull(desktopPushNotice(bot, { true }, hiddenBots = setOf("worker")))
        assertNull(desktopPushNotice(bot, { true }, foreground = true))
        assertNull(desktopPushNotice(bot, { true }, notifyReplies = false))
    }

    @Test fun unavailableRosterDoesNotRevealReplyAndAnEmptyRosterStillAllowsIt() = runTest {
        val url = "https://example.invalid"
        val store = InMemoryKeyValueStore()
        val failing = object : KeyValueStore by store {
            override suspend fun get(key: String): String? = error("Store unavailable")
        }
        assertNull(desktopPushNotice(bot, { true }, hiddenBots = DesktopPushBots(store).names(url)))
        assertNull(desktopPushNotice(bot, { true }, hiddenBots = DesktopPushBots(failing).names(url)))

        val key = "desktop.push.hidden." +
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        store.put(key, "")
        val known = DesktopPushBots(store).names(url)
        assertEquals(emptySet(), known)
        assertTrue(desktopPushNotice(bot, { true }, hiddenBots = known) != null)
        assertNull(DesktopPushBots(store).names("https://elsewhere.invalid"))
    }

    @Test fun pingAndUnsupportedTypesNeverFetchOrActOnPhoneRequests() = runTest {
        val ping = desktopPushNotice(PushMessage("p", 100, PushMessage.PING), { true },
            foreground = true, notifyReplies = false)!!
        assertTrue(ping.notice.body.isNotBlank())
        assertNull(ping.destination)
        for (type in listOf(PushMessage.PHONE_CALL, PushMessage.PHONE_REQUEST, "unknown")) {
            assertNull(desktopPushNotice(bot.copy(type = type), { true }))
        }
        assertNull(desktopPushNotice(bot, { false }))
    }
}
