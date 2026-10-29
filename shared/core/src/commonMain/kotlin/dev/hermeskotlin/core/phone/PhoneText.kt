package dev.hermeskotlin.core.phone

/**
 * A web link the Open tool may open: [url] as it will be opened, and its [host] as the user is asked about it.
 * Only links read the same way by everything are taken (see [checkLink]), so the site in the question is the site
 * the browser opens.
 */
data class CheckedLink(val url: String, val host: String, val secure: Boolean) {
    /** The host for the question: whole, or its end when long, since the site's own name is at the end. */
    val site: String
        get() = if (host.length <= SITE_LENGTH) host else "…" + host.takeLast(SITE_LENGTH)

    private companion object {
        const val SITE_LENGTH = 40
    }
}

/**
 * [value] as a link the user can trust the question about, or a [PhoneToolException] saying why not. Taken: http and
 * https, a host of plain letters, digits, dots and dashes (a name in another alphabet as its xn-- form, which every
 * browser reads the same; letters that only look Latin can't hide then) or a bracketed IPv6 address, an optional port,
 * and nothing hidden anywhere. Refused: a name before an @, which makes a link look like another site; percent signs
 * and backslashes in the host part, which browsers and Android read differently.
 */
fun checkLink(value: String): CheckedLink {
    if (hidden(value, newlines = false) || value.any { it == '\\' || it == ' ' }) {
        throw PhoneToolException("The link has spaces or hidden characters in it.")
    }
    val match = LINK.matchEntire(value) ?: throw PhoneToolException("Only http and https links open: give the full link.")
    val (scheme, authority, rest) = match.destructured
    if ('@' in authority) throw PhoneToolException("Links with a name before an @ don't open.")
    val parts = AUTHORITY.matchEntire(authority.lowercase())
        ?: throw PhoneToolException("The link's site must be written in plain letters (xn-- for other alphabets).")
    val host = parts.groupValues[1]
    val port = parts.groupValues[2].removePrefix(":")
    if (port.isNotEmpty() && port.toInt() !in 1..65535) throw PhoneToolException("The link's port isn't a real one.")
    // Browsers read a host ending in a number as an IP address (0x7f.1 is 127.0.0.1): only the usual a.b.c.d is taken.
    if (NUMBER.matches(host.removeSuffix(".").substringAfterLast('.')) && !IPV4.matches(host.removeSuffix("."))) {
        throw PhoneToolException("Write an IP address as four numbers, like 192.168.1.2.")
    }
    val lower = scheme.lowercase()
    // "www." off only when a whole name is left: www.com is the site www.com.
    val shown = host.removePrefix("www.").takeIf { it.trimEnd('.').contains('.') } ?: host
    return CheckedLink(url = "$lower://${authority.lowercase()}$rest", host = shown, secure = lower == "https")
}

/**
 * Whether [text] has characters a reader can't see or that change how it reads: controls, direction marks, zero-width
 * characters, spaces other than the plain one, and the invisible tags and variation selectors past U+FFFF that can
 * carry a whole hidden message. Line breaks are taken when [newlines] (in text to share).
 */
fun hidden(text: String, newlines: Boolean): Boolean {
    var i = 0
    var flagTags = -1 // Tags since a 🏴, or -1 outside a flag.
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val code = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            i += 2
            // England, Scotland, Wales: 🏴, a few lowercase letter tags, and the end tag. Too short to hide anything.
            when {
                code == BLACK_FLAG && flagTags <= 0 -> flagTags = 0
                flagTags in 0 until FLAG_TAGS && (code in 0xE0061..0xE007A || code in 0xE0030..0xE0039) -> flagTags++
                flagTags > 0 && code == 0xE007F -> flagTags = -1
                INVISIBLE_ABOVE.any { code in it } -> return true
                else -> flagTags = -1
            }
            continue
        }
        if (flagTags > 0) return true // A flag's tags without their end.
        flagTags = -1
        if (c.isSurrogate() || hiddenChar(c, newlines)) return true
        i++
    }
    return flagTags > 0
}

private fun hiddenChar(c: Char, newlines: Boolean): Boolean =
    when {
        // Emoji's own selectors (text or picture style); the other fifteen only hide bytes.
        c == '︎' || c == '️' -> false
        c in '︀'..'︍' -> true
        c == ' ' -> false
        c == '\n' && newlines -> false
        c.isISOControl() -> true
        c.isWhitespace() -> true
        c in NOT_PLAIN_SPACES -> true
        c.category == CharCategory.FORMAT -> true
        c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR -> true
        c.category == CharCategory.SPACE_SEPARATOR -> true
        else -> false
    }

/**
 * [text] for the end of a question: line breaks shown as ⏎, so it can't start a line that looks like Herald's. It goes
 * last, after a colon and without quotes, so nothing in it can close a quote and add words of Herald's after it.
 */
fun forQuestion(text: String): String = text.replace("\n", " ⏎ ")

private val LINK = Regex("""^([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]*)([/?#].*)?$""")

/** host (letters, digits, dots, dashes; or [IPv6]) and an optional port. */
private val AUTHORITY = Regex(
    """^((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\.?|\[[0-9a-f:.]+])(:[0-9]{1,5})?$""",
)

/** A last name part browsers read as a number: digits, or hex after 0x. */
private val NUMBER = Regex("""^(?:[0-9]+|0x[0-9a-f]*)$""")

private val IPV4 = Regex("""^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$""")

/** Invisible past U+FFFF: format controls, tags (hidden ASCII) and the variation selectors supplement. */
private const val BLACK_FLAG = 0x1F3F4

/** The most tags a flag has (gbsct is five, subdivision codes run to six). */
private const val FLAG_TAGS = 6

private val INVISIBLE_ABOVE =listOf(0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF)

private val NOT_PLAIN_SPACES = setOf(' ', ' ', ' ', '　', '᠎')
