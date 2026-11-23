package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsToastsTest {

    private fun approval(id: String) = InputRequest.Approval(
        id = id,
        command = "rm -rf /tmp/demo",
        description = "Clean temporary folder",
        toolName = "terminal",
        choices = listOf(ApprovalChoice.Once, ApprovalChoice.Deny),
    )

    @Test
    fun xmlEscapingStripsIllegalControlCharsAndKeepsLegalWhitespace() {
        val illegal = buildString {
            for (c in 0x00..0x08) append(c.toChar())
            append('\u000B')
            append('\u000C')
            for (c in 0x0E..0x1F) append(c.toChar())
            append('\uFFFE')
            append('\uFFFF')
        }
        val input = "Hello${illegal}\t\n\r<tag> & \"quoted\" 🚀"
        assertEquals("Hello\t\n\r&lt;tag&gt; &amp; \"quoted\" 🚀", escapeXmlText(input))
        assertEquals("Hello\t\n\r&lt;tag&gt; &amp; &quot;quoted&quot; 🚀", escapeXmlAttr(input))
    }

    @Test
    fun toastXmlWithControlCharactersStillParsesAsValidXml() {
        val notice = DesktopNotice(
            title = "Approval\u0000 \u001B[31mred\u001B[0m & <check>",
            body = "Run \u0007cmd\u000Bwith\u000Ccontrols\tand\nnewlines",
            sessionId = "s-1",
            chatTitle = "Demo",
            request = approval("req-1"),
        )
        val xml = buildToastXml(notice, "Approve\u0001 \"once\"", "Deny\u001F") { ans ->
            "herald://answer/${ans.choice.name.lowercase()}"
        }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals("toast", doc.documentElement.tagName)
        val texts = doc.getElementsByTagName("text")
        assertEquals(2, texts.length)
        assertEquals("Approval [31mred[0m & <check>", texts.item(0).textContent)
        assertEquals("Run cmdwithcontrols\tand\nnewlines", texts.item(1).textContent)
        val actions = doc.getElementsByTagName("action")
        assertEquals(2, actions.length)
        assertEquals("Approve \"once\"", actions.item(0).attributes.getNamedItem("content").nodeValue)
        assertEquals("Deny", actions.item(1).attributes.getNamedItem("content").nodeValue)
    }

    @Test
    fun answerRegistryConsumesTokenAndInvalidatesSiblingButton() {
        var clock = 1_000L
        val registry = AnswerRegistry(maxEntries = 10, ttlMs = 60_000L, nowMs = { clock })
        val req = approval("req-1")
        val approveToken = registry.register(NoticeAnswer("s1", req, ApprovalChoice.Once))
        val denyToken = registry.register(NoticeAnswer("s1", req, ApprovalChoice.Deny))
        assertEquals(2, registry.size())

        val taken = assertNotNull(registry.take(approveToken))
        assertEquals(ApprovalChoice.Once, taken.choice)
        assertNull(registry.take(approveToken))
        assertNull(registry.take(denyToken))
        assertEquals(0, registry.size())
    }

    @Test
    fun answerRegistryPrunesExpiredEntries() {
        var clock = 1_000L
        val registry = AnswerRegistry(maxEntries = 10, ttlMs = 5_000L, nowMs = { clock })
        val oldToken = registry.register(NoticeAnswer("s1", approval("old"), ApprovalChoice.Once))

        clock += 6_000L
        assertNull(registry.take(oldToken))
        assertEquals(0, registry.size())
    }

    @Test
    fun answerRegistryEvictsOldestEntriesPastCapacity() {
        var clock = 1_000L
        val registry = AnswerRegistry(maxEntries = 3, ttlMs = 60_000L, nowMs = { clock })
        val t1 = registry.register(NoticeAnswer("s1", approval("r1"), ApprovalChoice.Once))
        clock += 1
        val t2 = registry.register(NoticeAnswer("s1", approval("r2"), ApprovalChoice.Once))
        clock += 1
        val t3 = registry.register(NoticeAnswer("s1", approval("r3"), ApprovalChoice.Once))
        clock += 1
        val t4 = registry.register(NoticeAnswer("s1", approval("r4"), ApprovalChoice.Once))

        assertEquals(3, registry.size())
        assertNull(registry.take(t1))
        assertNotNull(registry.take(t2))
        assertNotNull(registry.take(t3))
        assertNotNull(registry.take(t4))
    }

    @Test
    fun takeAnswerIgnoresNonAnswerLinks() {
        assertNull(WindowsToasts.takeAnswer("herald://session/s1"))
        assertNull(WindowsToasts.takeAnswer("https://example.com/answer/abc"))
        assertNull(WindowsToasts.takeAnswer("herald://answer/unknown-token"))
        assertTrue(escapeXmlText("plain").isNotEmpty())
    }
}
