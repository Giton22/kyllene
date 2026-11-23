package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeColorsTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    // Every color token in Tokens.kt. A lookup of a token a scheme lacks crashes at runtime, so all must be in each one.
    private val allTokens = setOf(
        background, surface, surface2, surface3, well, thumb, surfaceElevated, input, sidebar,
        userBubble, userBubbleStroke, onUserBubble,
        text, textSecondary, textTertiary, textMuted,
        accent, onAccent, accentText, accentSoft, inverse, onInverse,
        stroke, strokeStrong,
        danger, onDanger, success, warning, dangerSoft, successSoft, warningSoft,
    )

    @Test
    fun everySchemeDefinesEveryColorToken() {
        for (palette in AccentPalette.all) {
            assertEquals(allTokens, lightColors(palette.light).keys, "${palette.name} light")
            assertEquals(allTokens, darkColors(palette.dark).keys, "${palette.name} dark")
            assertEquals(allTokens, pureBlackColors(palette.black).keys, "${palette.name} pure black")
        }
    }

    @Test
    fun theUsersBubbleCarriesItsOwnTextColor() {
        for (palette in AccentPalette.all) {
            for (scheme in listOf(lightColors(palette.light), darkColors(palette.dark), pureBlackColors(palette.black))) {
                assertEquals(scheme.getValue(accent), scheme.getValue(userBubble), palette.name)
                assertEquals(scheme.getValue(onAccent), scheme.getValue(onUserBubble), palette.name)
            }
        }
    }

    @Test
    fun textOnDangerIsReadableInEveryScheme() {
        val palette = AccentPalette.Blue
        for ((name, scheme) in listOf(
            "light" to lightColors(palette.light),
            "dark" to darkColors(palette.dark),
            "pure black" to pureBlackColors(palette.black),
        )) {
            val fill = scheme.getValue(danger)
            val label = scheme.getValue(onDanger)
            val ratio = contrast(label, fill)
            assertTrue(ratio >= 4.5f, "$name: onDanger on danger contrast is $ratio:1")
            // Danger is also used as text on surfaces; ensure it stays readable there too.
            for (ground in listOf(background, surface, surfaceElevated)) {
                val textRatio = contrast(fill, scheme.getValue(ground))
                assertTrue(textRatio >= 4.5f, "$name: danger text on $ground contrast is $textRatio:1")
            }
        }
    }
}
