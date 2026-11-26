package dev.hermeskotlin.android.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import dev.hermeskotlin.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ChatNotificationsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val posted = context.getSystemService(NotificationManager::class.java)
    private val notifications = ChatNotifications(context)

    @Test
    fun blankSilentAndMarkupOnlyCompletionsDoNotPost() {
        listOf("", "  ", "[SILENT]", "[](/document)").forEachIndexed { index, text ->
            notifications.postReply("chat-$index", "Bot chat", text, failed = false)
        }
        assertEquals(0, posted.activeNotifications.size)
    }

    @Test
    fun aReadableReplyPostsItsPreview() {
        notifications.postReply("chat", "Bot chat", "**Ready.**", failed = false)

        assertEquals("Ready.", preview())
    }

    @Test
    fun aFailureWithoutDetailsStillPosts() {
        notifications.postReply("chat", "Bot chat", "", failed = true)

        assertEquals(context.getString(R.string.notification_turn_failed_text), preview())
    }

    private fun preview(): String? =
        posted.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}
