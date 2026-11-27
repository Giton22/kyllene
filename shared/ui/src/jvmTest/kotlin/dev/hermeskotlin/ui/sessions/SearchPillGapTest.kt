package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.update.LocalSupportOffer
import dev.hermeskotlin.ui.update.SupportBanner
import dev.hermeskotlin.ui.update.SupportOffer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What follows the sidebar's search pill (the support card here, or the Chats / Bots switch) keeps a gap from it on
 * the desktop too, whose 36dp touch target is shorter than the 40dp pill and once let the two touch.
 */
@OptIn(ExperimentalTestApi::class)
class SearchPillGapTest {

    private fun gapUnderPill(desktop: Boolean): Float {
        var gap = 0f
        runComposeUiTest {
            setContent {
                ProvideAppLanguage("en") {
                    CompositionLocalProvider(
                        LocalDesktopLayout provides desktop,
                        LocalSupportOffer provides SupportOffer(show = true) {},
                    ) {
                        HermesTheme(ColorScheme.Dark) {
                            Column(Modifier.width(300.dp)) {
                                MainHeader(gatewayLabel = "homelab", connection = null, onAccount = {}, onSettings = {}, onSearch = {})
                                SupportBanner(Modifier.testTag("card").padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                            }
                        }
                    }
                }
            }
            waitUntil { onAllNodes(hasText("Search chats")).fetchSemanticsNodes().isNotEmpty() }
            // The pill is drawn 40dp tall, centred in the search row.
            val row = onNodeWithText("Search chats").getBoundsInRoot()
            val pillBottom = (row.top + row.bottom) / 2 + 20.dp
            gap = (onNodeWithTag("card").getBoundsInRoot().top - pillBottom).value
        }
        return gap
    }

    @Test
    fun theCardKeepsClearOfTheSearchPillOnTheDesktop() {
        val gap = gapUnderPill(desktop = true)
        assertTrue(gap >= 4f, "the card is ${gap}dp under the search pill")
    }

    @Test
    fun aPhoneKeepsItsGap() {
        val gap = gapUnderPill(desktop = false)
        assertTrue(gap in 3.5f..4.5f, "the card is ${gap}dp under the search pill")
    }
}
