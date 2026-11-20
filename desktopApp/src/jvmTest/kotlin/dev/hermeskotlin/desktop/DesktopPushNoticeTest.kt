package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.core.push.PushMessage
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
            assertEquals("Push notifications are working.", desktopPushNotice("g", ping, { _, _ -> }, { true })!!.notice.body)
            Locale.setDefault(Locale.forLanguageTag("es"))
            assertEquals("Las notificaciones push funcionan.", desktopPushNotice("g", ping, { _, _ -> }, { true })!!.notice.body)
            val cron = PushMessage("c", 100, PushMessage.CRON_RUN, job = "job1", run = "2026-11-01_12-00-00", status = "failed", notify = true)
            assertEquals("La ejecución ha fallado.", desktopPushNotice("g", cron, { _, _ -> }, { true })!!.notice.body)
            Locale.setDefault(Locale.FRENCH)
            assertEquals("Les notifications push fonctionnent.", desktopPushNotice("g", ping, { _, _ -> }, { true })!!.notice.body)
        } finally { Locale.setDefault(locale) }
    }

    @Test fun aLocaleReadCannotReturnANoticeToAnOwnerRevokedAfterItsFirstCheck() = runTest {
        var current = true
        val shown = desktopPushNotice("g", PushMessage("p", 100, PushMessage.PING), { _, _ -> }, {
            current.also { current = false }
        })
        assertNull(shown)
    }

    private val bot = PushMessage("m", 100, PushMessage.BOT_MESSAGE, bot = "worker", label = " Worker\u0000 ",
        session = "session:1", text = "\r\n```report```\r\n" + "w".repeat(700))

    @Test fun botRequiresASafeNamedDestinationAndClipsPlainText() = runTest {
        val notice = desktopPushNotice("https://gateway.example", bot, { _, _ -> }, { true })!!
        assertEquals("Worker", notice.notice.title)
        assertEquals("```report```", notice.notice.body)
        assertEquals("worker", notice.profile)
        assertEquals(PushDestination.Bot("worker", "Worker", "session:1"), notice.destination)
        assertNull(notice.notice.origin)
        assertNull(notice.notice.request)
        assertEquals(200, desktopPushNotice("g", bot.copy(text = "w".repeat(700)), { _, _ -> }, { true })!!.notice.body.length)
        for (bad in listOf(bot.copy(bot = "../x"), bot.copy(bot = ""), bot.copy(session = "x/y"), bot.copy(session = null))) {
            assertNull(desktopPushNotice("g", bad, { _, _ -> }, { true }))
        }
    }

    @Test fun cronPersistsUnderItsCapturedGatewayAndHonorsNotify() = runTest {
        val saved = mutableListOf<Pair<String, InboxRun>>()
        val cron = PushMessage("c", 100, PushMessage.CRON_RUN, bot = "worker", job = "job1",
            run = "2026-11-01_12-00-00", label = "Report", text = "First line\r\nnext", notify = false)
        assertNull(desktopPushNotice("old-gateway", cron, { url, run -> saved += url to run }, { true }))
        val shown = desktopPushNotice("old-gateway", cron.copy(notify = true), { url, run -> saved += url to run }, { true })!!
        assertEquals(listOf("old-gateway", "old-gateway"), saved.map { it.first })
        assertEquals("First line", shown.notice.body)
        assertEquals(PushDestination.Inbox("worker/job1/2026-11-01_12-00-00"), shown.destination)
        assertNull(desktopPushNotice("g", cron.copy(job = "../bad"), { _, _ -> error("invalid run saved") }, { true }))
    }

    @Test fun switchingOwnerDuringPersistenceDropsTheNotice() = runTest {
        var current = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cron = PushMessage("c", 100, PushMessage.CRON_RUN, job = "job1", run = "2026-11-01_12-00-00", notify = true)
        val result = async {
            desktopPushNotice("captured-gateway", cron, { url, _ ->
                assertEquals("captured-gateway", url)
                entered.complete(Unit)
                release.await()
            }, { current })
        }
        entered.await()
        current = false
        release.complete(Unit)
        assertNull(result.await())
    }

    @Test fun pingAndUnsupportedTypesNeverFetchOrActOnPhoneRequests() = runTest {
        var saved = false
        val persist: suspend (String, InboxRun) -> Unit = { _, _ -> saved = true }
        val ping = desktopPushNotice("g", PushMessage("p", 100, PushMessage.PING), persist, { true })!!
        assertTrue(ping.notice.body.isNotBlank())
        assertNull(ping.destination)
        for (type in listOf(PushMessage.PHONE_CALL, PushMessage.PHONE_REQUEST, "unknown")) {
            assertNull(desktopPushNotice("g", bot.copy(type = type), persist, { true }))
        }
        assertNull(desktopPushNotice("g", bot, persist, { false }))
        assertTrue(!saved)
    }
}
