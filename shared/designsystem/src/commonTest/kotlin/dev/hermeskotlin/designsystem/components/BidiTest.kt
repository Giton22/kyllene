package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BidiTest {

    private val lri = Bidi.LRI
    private val pdi = Bidi.PDI

    @Test
    fun inlineCodeIsIsolatedInsideArabicText() {
        val input = "شغّل `hermes update` بعد النسخ الاحتياطي."
        val out = Bidi.isolateInlineCode(input)
        assertEquals("شغّل `${lri}hermes update$pdi` بعد النسخ الاحتياطي.", out)
    }

    @Test
    fun theArabicAroundAnIsolatedSpanIsUntouched() {
        val input = "المسار هو `~/config.yaml` وهو صحيح."
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.startsWith("المسار هو `"))
        assertTrue(out.endsWith("` وهو صحيح."))
    }

    @Test
    fun everySpanOnALineIsIsolated() {
        val input = "`one` و `two` و `three`"
        val out = Bidi.isolateInlineCode(input)
        assertEquals("`${lri}one$pdi` و `${lri}two$pdi` و `${lri}three$pdi`", out)
    }

    @Test
    fun fencedCodeBlocksAreLeftAlone() {
        val input = "قبل:\n```bash\nhermes sessions prune\n```\nوبعد `x`"
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.contains("```bash\nhermes sessions prune\n```"))
        assertTrue(out.endsWith("وبعد `${lri}x$pdi`"))
    }

    @Test
    fun indentedCodeLinesAreLeftAlone() {
        val input = "    hermes sessions prune\nسطر `y`"
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.startsWith("    hermes sessions prune\n"))
        assertTrue(out.endsWith("`${lri}y$pdi`"))
    }

    @Test
    fun arabicInsideBackticksIsNotForcedToTheLeft() {
        val input = "الامر `حذف` هنا"
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun textWithoutBackticksIsReturnedIdentical() {
        val input = "لا يوجد كود هنا: 409 MB و v0.22.5."
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun isolatingTwiceChangesNothing() {
        val once = Bidi.isolateInlineCode("قبل `git status` بعد")
        assertEquals(once, Bidi.isolateInlineCode(once))
    }

    @Test
    fun anUnclosedBacktickIsLeftAsWritten() {
        val input = "قبل `hermes وبعد"
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun aDoubleBacktickSpanIsIsolatedAsOneRun() {
        val input = "هنا ``a ` b`` نهاية"
        assertTrue(Bidi.isolateInlineCode(input).contains("``${lri}a ` b$pdi``"))
    }

    @Test
    fun aPathAndAVersionNumberAreIsolated() {
        val out = Bidi.isolateInlineCode("الملف `/root/.hermes/config.yaml` والنسخة `v0.22.5`.")
        assertTrue(out.contains("`${lri}/root/.hermes/config.yaml$pdi`"))
        assertTrue(out.contains("`${lri}v0.22.5$pdi`"))
    }

    @Test
    fun arabicTextPrefersRightToLeft() {
        assertTrue(Bidi.prefersRightToLeft("شكراً على الشرح"))
        assertTrue(Bidi.prefersRightToLeft("409 MB مكتوبة صراحةً، فالتحديث لن يغيّرها"))
    }

    @Test
    fun latinTextDoesNot() {
        assertFalse(Bidi.prefersRightToLeft("Thanks for the explanation"))
        assertFalse(Bidi.prefersRightToLeft("auto_prune is false in both paths, and v0.22.5 is optional"))
        assertFalse(Bidi.prefersRightToLeft("12345 — "))
        assertFalse(Bidi.prefersRightToLeft(""))
    }

    @Test
    fun aMostlyArabicLineStaysRightToLeftAroundCodeAndLatin() {
        assertTrue(Bidi.prefersRightToLeft("`hermes update` بعد النسخ الاحتياطي وقبل أي تعديل آخر"))
    }

    @Test
    fun lineBreaksAndSpacingSurvive() {
        val input = "سطر اول `a`\n\nسطر ثاني `b`\n"
        val out = Bidi.isolateInlineCode(input)
        assertEquals("سطر اول `${lri}a$pdi`\n\nسطر ثاني `${lri}b$pdi`\n", out)
    }
}
