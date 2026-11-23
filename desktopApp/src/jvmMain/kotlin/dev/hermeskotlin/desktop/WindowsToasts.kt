package dev.hermeskotlin.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** An approval a notification's button answers: [request] in the chat [sessionId]. */
internal data class NoticeAnswer(val sessionId: String?, val request: InputRequest.Approval, val choice: ApprovalChoice)

/**
 * Bounded, expiring store of pending toast approval tokens so dismissed or in-app answered approvals don't accumulate.
 */
internal class AnswerRegistry(
    private val maxEntries: Int = MAX_ANSWERS,
    private val ttlMs: Long = ANSWER_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Entry(val answer: NoticeAnswer, val createdAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(answer: NoticeAnswer): String {
        val now = nowMs()
        pruneLocked(now)
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        entries[token] = Entry(answer, now)
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        return token
    }

    @Synchronized
    fun take(token: String): NoticeAnswer? {
        pruneLocked(nowMs())
        val taken = entries.remove(token)?.answer ?: return null
        // The other button of the same notification is spent too.
        entries.values.removeAll { it.answer.request.id == taken.request.id }
        return taken
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun pruneLocked(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAtMs > ttlMs) iterator.remove()
        }
    }

    companion object {
        const val MAX_ANSWERS = 100
        val ANSWER_TTL_MS: Long = TimeUnit.HOURS.toMillis(24)
    }
}

/**
 * Windows' own notifications for an installed Herald: they stay in the Action Center, name Herald with its icon, and
 * carry buttons, which the tray's balloons can't. A click opens a `herald://` link, which Windows hands to Herald
 * (registered here for the user, as the installer can't), and a running Herald takes over from the launch it starts.
 *
 * - The notification itself opens its chat: `herald://session/<id>`.
 * - An approval's Approve and Deny buttons answer it: `herald://answer/<token>`. The token is random and known only
 *   to this run of Herald, so no web page can approve a command by guessing a link.
 */
internal object WindowsToasts {
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    /** Only an installed Herald on Windows: one run from a build has no Herald.exe for Windows to start. */
    val available: Boolean = launcher != null && System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val answers = AnswerRegistry()

    /**
     * Registers the `herald://` scheme and Herald's name and [icon] for its notifications, for this user. Each launch
     * writes them again, so a Herald that moved or updated is the one they start.
     */
    fun register(icon: Image, iconDir: File) {
        val launcher = launcher?.takeIf { available } ?: return
        runCatching {
            val scheme = "Software\\Classes\\${AppLink.DESKTOP_SCHEME}"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$scheme\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, scheme, "", "URL:Kyllene")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, scheme, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$scheme\\shell\\open\\command", "", "\"${launcher.path}\" \"%1\"")

            val png = File(iconDir, "notification-icon.png")
            val pixels = icon as? BufferedImage ?: BufferedImage(icon.getWidth(null), icon.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                .also { it.createGraphics().apply { drawImage(icon, 0, 0, null) }.dispose() }
            ImageIO.write(pixels, "png", png)
            val app = "Software\\Classes\\AppUserModelId\\$APP_ID"
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, app)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, app, "DisplayName", "Kyllene")
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, app, "IconUri", png.path)
        }
    }

    /**
     * Shows [notice], an approval's with [approveLabel] and [denyLabel] as its buttons; false when Windows wouldn't,
     * and the tray's balloon should tell it instead.
     */
    fun show(notice: DesktopNotice, approveLabel: String, denyLabel: String): Boolean {
        if (!available) return false
        val xml = buildToastXml(notice, approveLabel, denyLabel, ::answerLink)
        val script = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > ${'$'}null
            ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml('${xml.replace("'", "''")}')
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$APP_ID').Show([Windows.UI.Notifications.ToastNotification]::new(${'$'}xml))
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        // Windows PowerShell, which every Windows 10 and 11 has, reaches the notification API; PowerShell 7 can't.
        return runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
    }

    /** The answer a notification's button stands for, taken once; null for any other link. */
    fun takeAnswer(link: String): NoticeAnswer? {
        val prefix = "${AppLink.DESKTOP_SCHEME}://answer/"
        if (!link.startsWith(prefix, ignoreCase = true)) return null
        return answers.take(link.substring(prefix.length).trimEnd('/'))
    }

    private fun answerLink(answer: NoticeAnswer): String =
        "${AppLink.DESKTOP_SCHEME}://answer/${answers.register(answer)}"

    /** The name Windows files Herald's notifications under. */
    private const val APP_ID = "dev.hermeskotlin.herald"
}

internal fun buildToastXml(
    notice: DesktopNotice,
    approveLabel: String,
    denyLabel: String,
    linkFor: (NoticeAnswer) -> String,
): String {
    val open = notice.sessionId?.let { "${AppLink.DESKTOP_SCHEME}://session/${encodeUrlSegment(it)}" } ?: "${AppLink.DESKTOP_SCHEME}://show"
    val approval = notice.request as? InputRequest.Approval
    val buttons = if (approval == null) {
        ""
    } else {
        "<actions>" +
            toastButton(approveLabel, linkFor(NoticeAnswer(notice.sessionId, approval, ApprovalChoice.Once))) +
            toastButton(denyLabel, linkFor(NoticeAnswer(notice.sessionId, approval, ApprovalChoice.Deny))) +
            "</actions>"
    }
    return "<toast activationType=\"protocol\" launch=\"${escapeXmlAttr(open)}\">" +
        "<visual><binding template=\"ToastGeneric\"><text>${escapeXmlText(notice.title)}</text><text>${escapeXmlText(notice.body)}</text></binding></visual>" +
        buttons + "</toast>"
}

private fun toastButton(label: String, link: String) =
    "<action content=\"${escapeXmlAttr(label)}\" activationType=\"protocol\" arguments=\"${escapeXmlAttr(link)}\"/>"

/** Escapes XML text and strips XML 1.0 illegal control characters that would cause `XmlDocument.LoadXml` to reject the toast. */
internal fun escapeXmlText(value: String): String = buildString(value.length) {
    for (ch in value) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            isIllegalXmlChar(ch) -> Unit
            else -> append(ch)
        }
    }
}

internal fun escapeXmlAttr(value: String): String = escapeXmlText(value).replace("\"", "&quot;")

private fun isIllegalXmlChar(ch: Char): Boolean {
    val code = ch.code
    return (code in 0x00..0x08) || code == 0x0B || code == 0x0C || (code in 0x0E..0x1F) ||
        code == 0xFFFE || code == 0xFFFF
}

private fun encodeUrlSegment(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
