package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.desktop.resources.*
import org.jetbrains.compose.resources.getString

internal sealed interface PushDestination {
    data class Bot(val name: String, val label: String, val session: String) : PushDestination
    data class Inbox(val key: String) : PushDestination
}

internal data class PushNotice(val notice: DesktopNotice, val profile: String?, val destination: PushDestination?)

/** Maps authenticated news only. Phone tools, incoming calls and remote content fetches are outside this receiver. */
internal suspend fun desktopPushNotice(
    message: PushMessage,
    current: () -> Boolean,
    jobTitle: String? = null,
    foreground: Boolean = false,
    notifyReplies: Boolean = true,
    hiddenBots: Set<String>? = emptySet(),
): PushNotice? {
    if (!current()) return null
    return when (message.type) {
        PushMessage.BOT_MESSAGE -> {
            val bot = message.bot?.takeIf { PROFILE.matches(it) } ?: return null
            if (foreground || !notifyReplies || hiddenBots == null || bot in hiddenBots) return null
            val session = message.session?.takeIf { SESSION.matches(it) } ?: return null
            val label = cleanLabel(message.label).ifBlank { bot }
            val reply = desktopReplyPreview(message.text)
            PushNotice(DesktopNotice(label, reply ?: getString(Res.string.notice_reply_in), session, label, replyPreview = reply),
                bot.takeUnless { it == "default" }, PushDestination.Bot(bot, label, session))
        }
        PushMessage.CRON_RUN -> {
            val run = InboxRun.from(message) ?: return null
            if (foreground || !message.notify) return null
            val title = cleanLabel(jobTitle ?: run.title).ifBlank { getString(Res.string.notice_scheduled_job) }
            PushNotice(DesktopNotice(title, preview(run.text, getString(if (run.failed) Res.string.notice_run_failed else Res.string.notice_run_finished)),
                run.key, title), run.bot.takeUnless { it == "default" }, PushDestination.Inbox(run.key))
        }
        PushMessage.PING -> PushNotice(DesktopNotice("Kyllene", getString(Res.string.notice_push_working), null, null), null, null)
        else -> null
    }?.takeIf { current() }
}

private fun cleanLabel(value: String?): String = value.orEmpty().filterNot { it.isISOControl() }.trim().take(64)
private fun preview(value: String?, fallback: String): String = desktopReplyPreview(value) ?: fallback

internal fun desktopReplyPreview(value: String?): String? = value.orEmpty().lineSequence()
    .map { line -> line.filterNot { it.isISOControl() }.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)
private val PROFILE = Regex("^[A-Za-z0-9_.-]{1,64}$")
private val SESSION = Regex("^[A-Za-z0-9_.:-]{1,128}$")
