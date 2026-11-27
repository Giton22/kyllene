package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.bots.BotsUiState
import dev.hermeskotlin.ui.inbox.Words
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The desktop sidebar's scrollbar shows only while something is under the floating footer (#556). The room kept
 * below a list's last row matches the footer as measured, not the phone's 96dp, which once drew a bar over a list
 * that fits and made it flash as a chat's "Running" line came and went.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarFooterInsetTest {

    private val base = 1_800_000_000.0

    private fun chats(count: Int, order: List<String> = (0 until count).map { "c$it" }) =
        order.mapIndexed { i, id -> SessionSummary(id, "Sample chat $id", lastActive = base - i * 60) }

    private fun bots(count: Int) = BotsUiState(
        bots = (0 until count).map { Bot(name = "bot$it", displayName = "Sample bot $it") },
        loading = false,
    )

    private val both = mapOf("c0" to RowStatus(running = true), "c1" to RowStatus(running = true))
    private val one = mapOf("c0" to RowStatus(running = true))

    private fun sidebar(density: Float, heightDp: Int, test: SkikoComposeUiTest.() -> Unit) =
        runSkikoComposeUiTest(size = Size(WIDTH * density, heightDp * density), density = Density(density)) { test() }

    @Composable
    private fun Desktop(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDesktopLayout provides true) {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) { content() }
        }
    }

    /** Where the footer starts: its 16dp fade and the account row's 2dp above the account button. */
    private fun SkikoComposeUiTest.footerTop(density: Float): Float =
        onNode(hasText("user")).fetchSemanticsNode().boundsInRoot.top - 18 * density

    private fun SkikoComposeUiTest.bounds(text: String): Rect = onNode(hasText(text)).fetchSemanticsNode().boundsInRoot

    /** Where [text]'s row ends, or past the window when the list hasn't laid it out, being further down. */
    private fun SkikoComposeUiTest.bottomOf(text: String): Float =
        onAllNodes(hasText(text)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.bottom ?: Float.MAX_VALUE

    /**
     * Whether a scrollbar thumb is drawn on the right edge between [top] and [bottom]: the bar's 6dp strip, 1dp in
     * from the edge, against the edge's own column, which only the ground shows.
     */
    private fun PixelMap.thumb(density: Float, top: Float, bottom: Float): Boolean {
        val edge = width - 1
        val xs = (width - (7 * density).toInt()) until (width - (1 * density).toInt())
        var marked = 0
        for (y in top.toInt().coerceAtLeast(0) until bottom.toInt().coerceAtMost(height)) {
            val ground = this[edge, y]
            for (x in xs) if (this[x, y].differs(ground)) marked++
        }
        return marked > xs.count() * 8 * density
    }

    private fun Color.differs(o: Color) = abs(red - o.red) + abs(green - o.green) + abs(blue - o.blue) > 0.06f

    /**
     * Over window heights around where the list just fits, with both chats running and with one: no bar while the
     * last row ends above the footer, a bar once it reaches under it.
     */
    @Test
    fun theBarShowsOnlyWhenARowIsUnderTheFooter() {
        for (density in DENSITIES) for (statuses in listOf(both, one)) {
            var height by mutableIntStateOf(0)
            sidebar(density, 760) {
                setContent {
                    Desktop {
                        Box(Modifier.fillMaxWidth().height(height.dp)) {
                            SessionsSidebarSample(chats(5), selectedId = "c0", userLabel = "user", statuses = statuses)
                        }
                    }
                }
                for (h in 560..700 step 2) {
                    height = h
                    waitForIdle()
                    checkBar(density, "Sample chat c4", "density=$density height=$h running=${statuses.keys}")
                }
            }
        }
    }

    /**
     * A turn ending in one chat as one starts in the other, a frame apart, so for a frame both rows carry a "Running"
     * line while they swap the top: no frame draws a bar over a list that fits.
     */
    @Test
    fun noFlashWhileTwoRunningChatsSwapTheTop() {
        for (density in DENSITIES) {
            var height by mutableIntStateOf(0)
            var order by mutableStateOf(listOf("c0", "c1", "c2", "c3", "c4"))
            var statuses by mutableStateOf(one)
            sidebar(density, 760) {
                setContent {
                    Desktop {
                        Box(Modifier.fillMaxWidth().height(height.dp)) {
                            SessionsSidebarSample(chats(5, order), selectedId = "c0", userLabel = "user", statuses = statuses)
                        }
                    }
                }
                for (h in 600..640 step 2) {
                    height = h
                    order = listOf("c0", "c1", "c2", "c3", "c4")
                    statuses = one
                    waitForIdle()
                    // Only heights where the list fits even with both lines; the sweep above covers the others.
                    statuses = both
                    waitForIdle()
                    if (bottomOf("Sample chat c4") > footerTop(density) - 1) continue
                    mainClock.autoAdvance = false
                    repeat(3) { swap ->
                        val top = if (swap % 2 == 0) "c1" else "c0"
                        val other = if (top == "c1") "c0" else "c1"
                        statuses = mapOf("c0" to RowStatus(running = true), "c1" to RowStatus(running = true))
                        order = listOf(top, other, "c2", "c3", "c4")
                        repeat(2) { frame -> mainClock.advanceTimeByFrame(); checkFrame(density, "density=$density height=$h swap=$swap frame=$frame") }
                        statuses = mapOf(top to RowStatus(running = true))
                        repeat(2) { frame -> mainClock.advanceTimeByFrame(); checkFrame(density, "density=$density height=$h swap=$swap after=$frame") }
                    }
                    mainClock.autoAdvance = true
                }
            }
        }
    }

    /** A long list still scrolls its last row all the way out from under the footer, and shows its bar. */
    @Test
    fun aLongListScrollsItsLastRowClearOfTheFooter() {
        for (density in DENSITIES) sidebar(density, 620) {
            setContent { Desktop { SessionsSidebarSample(chats(30), selectedId = "c0", userLabel = "user", statuses = both) } }
            waitForIdle()
            assertTrue(onRoot().captureToImage().toPixelMap().thumb(density, 0f, footerTop(density)), "no bar over 30 chats at $density")
            scrollToEnd()
            val last = bounds("Sample chat c29")
            val footer = footerTop(density)
            assertTrue(last.bottom <= footer + 1, "the last row ends at ${last.bottom}px, under the footer at ${footer}px (density $density)")
        }
    }

    /** The Bots side: no bar while its cards, and the gap the last row keeps below them, fit above the footer. */
    @Test
    fun theBotsBarShowsOnlyWhenACardIsUnderTheFooter() {
        for (density in DENSITIES) {
            var height by mutableIntStateOf(0)
            sidebar(density, 760) {
                setContent {
                    Desktop {
                        Box(Modifier.fillMaxWidth().height(height.dp)) {
                            BotsSidebarSample(bots(3), userLabel = "user", nowSeconds = base)
                        }
                    }
                }
                for (h in 560..720 step 2) {
                    height = h
                    waitForIdle()
                    checkBar(density, "Sample bot 2", "bots density=$density height=$h", gapDp = 8)
                }
            }
        }
    }

    /** A long roster scrolls its last card out from under the footer. */
    @Test
    fun aLongRosterScrollsItsLastCardClearOfTheFooter() {
        for (density in DENSITIES) sidebar(density, 620) {
            setContent { Desktop { BotsSidebarSample(bots(40), userLabel = "user", nowSeconds = base) } }
            waitForIdle()
            scrollToEnd()
            val last = bounds("Sample bot 39")
            val footer = footerTop(density)
            assertTrue(last.bottom <= footer + 1, "the last card ends at ${last.bottom}px, under the footer at ${footer}px (density $density)")
        }
    }

    /** A failed bot action's notice sits just above the desktop's footer, as it does above the phone's. */
    @Test
    fun theBotsNoticeSitsJustAboveTheFooter() {
        for (density in DENSITIES) sidebar(density, 620) {
            setContent { Desktop { BotsSidebarSample(bots(3).copy(notice = Words.Raw("Sample notice")), userLabel = "user", nowSeconds = base) } }
            waitForIdle()
            val gap = (footerTop(density) - bounds("Sample notice").bottom) / density
            assertTrue(gap in 0f..16f, "the notice ends ${gap}dp above the footer (density $density)")
        }
    }

    private fun SkikoComposeUiTest.scrollToEnd() {
        repeat(10) {
            onRoot().performMouseInput { moveTo(center); scroll(4000f) }
            waitForIdle()
        }
    }

    /**
     * No bar while [lastRow] ends above the footer; a bar while it is clearly under it. [gapDp] is the space the
     * list keeps below the row as part of it, as the roster's 8dp between card rows.
     */
    private fun SkikoComposeUiTest.checkBar(density: Float, lastRow: String, at: String, gapDp: Int = 0) {
        val footer = footerTop(density)
        val last = bottomOf(lastRow) + gapDp * density
        val shown = onRoot().captureToImage().toPixelMap().thumb(density, 0f, footer)
        when {
            last <= footer - 1 && shown -> fail("a bar over a list that fits ($at): last row ends ${(footer - last) / density}dp above the footer")
            last > footer + 4 * density && !shown -> fail("no bar with a row under the footer ($at)")
        }
    }

    private fun SkikoComposeUiTest.checkFrame(density: Float, at: String) {
        if (onRoot().captureToImage().toPixelMap().thumb(density, 0f, footerTop(density))) fail("the bar flashed ($at)")
    }

    private companion object {
        const val WIDTH = 280f
        val DENSITIES = listOf(1f, 1.25f, 1.5f)
    }
}
