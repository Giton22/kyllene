package dev.hermeskotlin.android.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.WatchedTurnEnd
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
    fun aReplyThatIsOnlyAPictureSaysItFinished() {
        notifications.postReply("chat", "Bot chat", "![](https://gateway.test/image.png)", failed = false)

        assertEquals(context.getString(R.string.notification_finished), preview())
    }

    @Test
    fun aBotMessageThatIsOnlyAPictureStillPosts() {
        notifications.postBotMessage(Bot("work"), picture = null, storedSessionId = "chat", text = "[](/document)")
        assertEquals(0, posted.activeNotifications.size)

        notifications.postBotMessage(Bot("work"), picture = null, storedSessionId = "chat", text = "![](https://gateway.test/image.png)")
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted.activeNotifications.single().notification)
        assertEquals(context.getString(R.string.notification_finished), messages?.messages?.last()?.text?.toString())
    }

    @Test
    fun twoPicturesFromTwoPathsAreTwoMessages() {
        notifications.postBotMessage(Bot("work"), picture = null, storedSessionId = "chat", text = "![](https://gateway.test/a.png)")
        notifications.postBotMessage(Bot("work"), picture = null, storedSessionId = "chat", text = "![](https://gateway.test/b.png)", viaPush = true)
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted.activeNotifications.single().notification)
        assertEquals(2, messages?.messages?.size)

        // The same picture over both paths is one message.
        notifications.postBotMessage(Bot("work"), picture = null, storedSessionId = "chat", text = "![](https://gateway.test/b.png)")
        val after = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted.activeNotifications.single().notification)
        assertEquals(2, after?.messages?.size)
    }

    @Test
    fun aFailureWithoutDetailsStillPosts() {
        notifications.postReply("chat", "Bot chat", "", failed = true)

        assertEquals(context.getString(R.string.notification_turn_failed_text), preview())
    }

    @Test
    fun aWatchedTurnWhoseReplyWasntAtHandSaysItFinished() {
        val end = WatchedTurnEnd("chat", "Bot chat", text = null, outcome = TurnOutcome.Complete, error = null)
        notifications.postReply("chat", "Bot chat", end.replyText(notifications.finished), failed = false)

        assertEquals(context.getString(R.string.notification_finished), preview())
    }

    @Test
    fun aWatchedTurnThatEndedInSilenceStaysUntold() {
        val end = WatchedTurnEnd("chat", "Bot chat", text = "", outcome = TurnOutcome.Complete, error = null)
        notifications.postReply("chat", "Bot chat", end.replyText(notifications.finished), failed = false)

        assertEquals(0, posted.activeNotifications.size)
    }

    private fun preview(): String? =
        posted.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}
