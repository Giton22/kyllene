package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.wear.WatchCallLine
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The watch's call screen is told why a call couldn't start in the app's language, not core's English. */
class CatalogueWatchCallWordsTest {
    private val before = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(before)

    @Test
    fun englishCatalogueSaysWhatCoreSays() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        for (line in WatchCallLine.entries) {
            assertEquals(line.english, CatalogueWatchCallWords.say(line).replace('’', '\''), line.name)
        }
    }

    @Test
    fun aGermanPhoneTellsTheWatchInGerman() = runTest {
        Locale.setDefault(Locale.GERMAN)
        assertEquals("Das Standard-Anrufziel hat sich geändert. Tippe erneut auf Anrufen.",
            CatalogueWatchCallWords.say(WatchCallLine.TargetChanged))
        assertEquals("Melde dich zuerst in Kyllene auf deinem Telefon an.", CatalogueWatchCallWords.say(WatchCallLine.SignedOut))
    }

    @Test
    fun aHungarianPhoneTellsTheWatchInHungarian() = runTest {
        Locale.setDefault(Locale.forLanguageTag("hu"))
        assertEquals("A Kyllene most nem éri el a Gateway-edet.", CatalogueWatchCallWords.say(WatchCallLine.Unreachable))
    }
}
