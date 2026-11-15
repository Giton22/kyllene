package dev.hermeskotlin.android.widget

import android.content.ComponentName
import android.content.Intent
import dev.hermeskotlin.android.HeraldActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The widget tap contract (docs/WIDGETS-SPEC.md D19): every PendingIntent names HeraldActivity
 * explicitly with the launcher shape, carries the right extras or hermes:// data, and the refresh
 * buttons broadcast to the right provider.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetIntentsTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun onlyIntent(pending: android.app.PendingIntent): Intent =
        shadowOf(pending).savedIntents.single()

    private fun assertActivityShape(intent: Intent) {
        assertEquals(ComponentName(context, HeraldActivity::class.java), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories.contains(Intent.CATEGORY_LAUNCHER))
    }

    @Test
    fun taskRowCarriesBoardAndTask() {
        val intent = onlyIntent(WidgetIntents.openTask(context, "work", "t-1"))
        assertActivityShape(intent)
        assertEquals("work", intent.getStringExtra(WidgetIntents.EXTRA_OPEN_KANBAN_BOARD))
        assertEquals("t-1", intent.getStringExtra(WidgetIntents.EXTRA_OPEN_KANBAN_TASK))
    }

    @Test
    fun jobRowCarriesJobAndProfile() {
        val intent = onlyIntent(WidgetIntents.openJob(context, "job-9", "reviewer"))
        assertActivityShape(intent)
        assertEquals("job-9", intent.getStringExtra(WidgetIntents.EXTRA_OPEN_SCHEDULED_JOB))
        assertEquals("reviewer", intent.getStringExtra(WidgetIntents.EXTRA_OPEN_SCHEDULED_PROFILE))
    }

    @Test
    fun botRowCarriesProfileAndSession() {
        val intent = onlyIntent(WidgetIntents.openBot(context, "helper", "s-2", "Helper"))
        assertActivityShape(intent)
        assertEquals("helper", intent.getStringExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_BOT))
        assertEquals("s-2", intent.getStringExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_SESSION))
    }

    @Test
    fun chatRowCarriesSession() {
        val intent = onlyIntent(WidgetIntents.openChat(context, "s-1", "Chat"))
        assertActivityShape(intent)
        assertEquals("s-1", intent.getStringExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_SESSION))
    }

    @Test
    fun headerTapsCarryLinksAsData() {
        assertEquals("hermes://kanban", onlyIntent(WidgetIntents.openKanban(context)).data.toString())
        assertEquals("hermes://scheduled", onlyIntent(WidgetIntents.openScheduled(context)).data.toString())
        assertEquals("hermes://now", onlyIntent(WidgetIntents.openNow(context)).data.toString())
        assertEquals("hermes://new-chat", onlyIntent(WidgetIntents.newChat(context)).data.toString())
        assertEquals("hermes://new-chat-voice", onlyIntent(WidgetIntents.dictate(context)).data.toString())
    }

    @Test
    fun refreshBroadcastTargetsItsProvider() {
        val intent = onlyIntent(
            WidgetIntents.refresh(context, KanbanWidgetProvider::class.java, WidgetIntents.ACTION_REFRESH_KANBAN),
        )
        assertEquals(ComponentName(context, KanbanWidgetProvider::class.java), intent.component)
        assertEquals(WidgetIntents.ACTION_REFRESH_KANBAN, intent.action)
    }

    @Test
    fun switchBoardBroadcastTargetsTheKanbanProvider() {
        val intent = onlyIntent(WidgetIntents.switchBoard(context))
        assertEquals(ComponentName(context, KanbanWidgetProvider::class.java), intent.component)
        assertEquals(WidgetIntents.ACTION_SWITCH_KANBAN_BOARD, intent.action)
    }

    @Test
    fun dismissAlertBroadcastCarriesItsScopeToTheNowProvider() {
        val pending = WidgetIntents.dismissAlert(context, "https://gw", "cron:job-1:100.0")
        val intent = onlyIntent(pending)
        assertEquals(ComponentName(context, NowWidgetProvider::class.java), intent.component)
        assertEquals(WidgetIntents.ACTION_DISMISS_NOW_ALERT, intent.action)
        assertEquals("https://gw", intent.getStringExtra(WidgetIntents.EXTRA_DISMISS_GATEWAY))
        assertEquals("cron:job-1:100.0", intent.getStringExtra(WidgetIntents.EXTRA_DISMISS_KEY))
        // Two alerts on the same gateway must not share one PendingIntent: filterEquals ignores
        // extras, so the request code is what keeps the destinations apart.
        val other = WidgetIntents.dismissAlert(context, "https://gw", "cron:job-2:200.0")
        assertTrue(shadowOf(pending).requestCode != shadowOf(other).requestCode)
    }
}
