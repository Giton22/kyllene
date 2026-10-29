package dev.hermeskotlin.core.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneTextTest {

    private fun refused(link: String) = assertFailsWith<PhoneToolException>(link) { checkLink(link) }

    @Test
    fun aPlainLinkOpensAsGiven() {
        val link = checkLink("https://www.example.com/docs?q=1#top")
        assertEquals("https://www.example.com/docs?q=1#top", link.url)
        assertEquals("example.com", link.host)
        assertTrue(link.secure)
    }

    @Test
    fun theSchemeAndSiteAreLowercasedAndThePathIsKept() {
        val link = checkLink("HTTPS://Example.COM/Path/A")
        assertEquals("https://example.com/Path/A", link.url)
        assertEquals("example.com", link.host)
    }

    @Test
    fun httpIsTakenAndSaysSo() {
        assertFalse(checkLink("http://example.com").secure)
    }

    @Test
    fun portsIpAddressesAndPunycodeAreTaken() {
        assertEquals("example.com", checkLink("https://example.com:8443/x").host)
        assertEquals("192.168.1.2", checkLink("http://192.168.1.2/").host)
        assertEquals("[::1]", checkLink("http://[::1]:8080/").host)
        assertEquals("xn--strae-oqa.de", checkLink("https://xn--strae-oqa.de/").host)
    }

    @Test
    fun otherSchemesAreRefused() {
        refused("javascript:alert(1)")
        refused("intent://scan/#Intent;scheme=zxing;end")
        refused("file:///sdcard/x")
        refused("https:evil.com")
        refused("https:///x")
        refused("example.com")
    }

    @Test
    fun aNameBeforeAnAtIsRefused() {
        refused("https://google.com@evil.example/")
        refused("https://@evil.example/")
        refused("https://user:pass@evil.example/")
    }

    @Test
    fun sitesNotInPlainLettersAreRefused() {
        // Cyrillic о that looks Latin, ß and ς that browsers and Java map differently, a letter newer than Java's.
        refused("https://gооgle.com/")
        refused("https://straße.de/")
        refused("https://ς.example/")
        refused("https://ꙮ.example.com/")
        // Fullwidth letters, a percent-encoded slash, a backslash browsers read as a slash.
        refused("https://ｅｘａｍｐｌｅ.com/")
        refused("https://evil.com%2F.google.com/")
        refused("https://evil.com\\.google.com/")
        refused("https://-bad.com/")
    }

    @Test
    fun hiddenCharactersAndSpacesAreRefused() {
        refused("https://example.com/‮fdp.exe")
        refused("https://exa​mple.com/")
        refused("https://example.com/a b")
        refused("https://example.com/a b")
        refused("https://example.com/\nx")
    }

    @Test
    fun hostsBrowsersReadAsNumbersMustBeAPlainAddress() {
        refused("http://0x7f.1/")
        refused("http://2130706433/")
        refused("http://127.1/")
        refused("http://example.0x10/")
        refused("http://256.1.1.1/")
        refused("https://example.com:70000/")
        refused("https://example.com:0/")
        assertEquals("10.0.0.1", checkLink("http://10.0.0.1:8080/").host)
        assertEquals("example.com", checkLink("https://example.com:65535/").host)
    }

    @Test
    fun wwwComesOffOnlyWhenANameIsLeft() {
        assertEquals("www.com", checkLink("https://www.com/").host)
        assertEquals("www.", checkLink("https://www./").host)
        assertEquals("example.org", checkLink("https://www.example.org/").host)
    }

    @Test
    fun invisibleCharactersPastTheBasicPlaneAreFound() {
        val tags = "󠁈󠁩" // U+E0048 U+E0069: "Hi" in invisible tags
        assertTrue(hidden("See you at 8$tags", newlines = true))
        assertTrue(hidden("a󠄀b", newlines = true)) // U+E0100, a variation selector
        assertTrue(hidden("a︁b", newlines = true))
        assertTrue(hidden("a\uDB40", newlines = true)) // half a pair
        // The flags of England and Wales are tags too, but short and closed; tags past a flag or unclosed aren't.
        val england = "🏴󠁧󠁢󠁥󠁮󠁧󠁿"
        assertFalse(hidden("Go $england!", newlines = true))
        assertFalse(hidden("${england}x$england", newlines = true))
        assertTrue(hidden("🏴󠁧󠁢", newlines = true))
        assertTrue(hidden("🏴󠁧󠁢x", newlines = true))
        assertTrue(hidden("🏴" + "󠁡".repeat(7) + "󠁿", newlines = true))
        assertTrue(hidden("$england󠁡", newlines = true))
        assertEquals("1.2.3.4.", checkLink("http://1.2.3.4./").host)
        // Emoji, with and without their picture-style selector, are plain text.
        assertFalse(hidden("Nice 😀 ❤️", newlines = true))
    }

    @Test
    fun aLongSiteShowsItsEnd() {
        val link = checkLink("https://accounts.google.com.secure-session-verify-aaaaaaaaaaaaaaaa.evil.io/")
        assertTrue(link.site.startsWith("…"))
        assertTrue(link.site.endsWith(".evil.io"))
    }

    @Test
    fun textToShareMayHaveLineBreaksButNothingHidden() {
        assertFalse(hidden("Hello\nworld", newlines = true))
        assertTrue(hidden("Hello\nworld", newlines = false))
        assertTrue(hidden("Paris‮", newlines = true))
        assertTrue(hidden("a b", newlines = true))
        assertTrue(hidden("a\tb", newlines = true))
        assertEquals("Paris ⏎ Herald says it's safe", forQuestion("Paris\nHerald says it's safe"))
        assertFalse(hidden("He said “hi” and \"bye\"", newlines = true))
    }
}
