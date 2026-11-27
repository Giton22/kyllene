package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.update.LocalSupportOffer
import dev.hermeskotlin.ui.update.SupportOffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Support Kyllene: giving from Settings stops the sidebar card asking too. */
@OptIn(ExperimentalTestApi::class)
class SupportSectionTest {
    @Test
    fun aStarOrTipFromSettingsEndsTheCardButOpeningCreditsDoesNot() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var dismissed = 0
        var credits = 0
        setContent {
            ProvideAppLanguage("en") {
                HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) {
                                opened += uri
                            }
                        },
                        LocalSupportOffer provides SupportOffer(show = true) { dismissed++ },
                    ) {
                        SupportSection(onOpenCredits = { credits++ })
                    }
                }
            }
        }
        waitUntil { onAllNodes(hasText("Star on GitHub")).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Contributors and supporters").performClick()
        assertEquals(1, credits)
        assertEquals(0, dismissed)

        onNodeWithText("Star on GitHub").performClick()
        onNodeWithText("Sponsor on GitHub").performClick()
        assertEquals(listOf(REPO_URL, SPONSORS_URL), opened)
        assertEquals(2, dismissed)
    }
}
