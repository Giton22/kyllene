package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.bots.BotsRoster
import dev.hermeskotlin.ui.bots.BotsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FoldDrawerTest {
    @Test
    fun chatsAndBotsUseTheSameToolsAccordion() = runComposeUiTest {
        var mode by mutableStateOf(SidebarMode.Chats)
        var opened: String? = null
        setContent {
            HermesTheme {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    Box(Modifier.size(360.dp, 600.dp)) {
                        DrawerNavigation(mode, listOf(
                            NavLink(Lucide.Inbox, "Inbox", badge = 2) { opened = "inbox" },
                            NavLink(Lucide.CalendarClock, "Scheduled") { opened = "scheduled" },
                        ))
                    }
                }
            }
        }

        onNodeWithText("Inbox").assertIsDisplayed().performClick()
        runOnIdle { assertEquals("inbox", opened) }
        onNodeWithText("Scheduled").assertDoesNotExist()
        onNodeWithText("Tools").performClick()
        onNodeWithText("Inbox").assertIsDisplayed()
        onNodeWithText("Scheduled").performClick()
        runOnIdle { assertEquals("scheduled", opened); mode = SidebarMode.Bots }
        onNodeWithText("Tools").assertIsDisplayed()
        onNodeWithText("Inbox").assertIsDisplayed().performClick()
        runOnIdle { assertEquals("inbox", opened) }
        onNodeWithText("Scheduled").assertDoesNotExist()
        onNodeWithText("More").assertDoesNotExist()
        onNodeWithText("Tools").performClick()
        onNodeWithText("Scheduled").assertIsDisplayed().performClick()
        runOnIdle { assertEquals("scheduled", opened) }
        onNodeWithText("Tools").performClick()
        onNodeWithText("Scheduled").assertDoesNotExist()
        onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun statusRowsSwitchSelectionAndHideEmptyCategories() = runComposeUiTest {
        var filter by mutableStateOf(AttentionFilter.All)
        var counts by mutableStateOf(AttentionCounts(running = 2, needsYou = 3))
        setContent {
            HermesTheme {
                Box(Modifier.size(360.dp, 600.dp)) {
                    SidebarAttentionRows(filter, counts) { filter = it }
                }
            }
        }

        onNodeWithText("All chats").assertIsSelected()
        onNodeWithText("Needs you").assertIsDisplayed().performClick().assertIsSelected()
        onNodeWithText("Running").assertIsDisplayed().performClick().assertIsSelected()
        runOnIdle { counts = AttentionCounts(running = 0, needsYou = 3) }
        onNodeWithText("Running").assertDoesNotExist()
        onNodeWithText("All chats").assertIsSelected()
        runOnIdle { counts = AttentionCounts(running = 0, needsYou = 0) }
        onNodeWithText("Needs you").assertDoesNotExist()
        onNodeWithText("All chats").assertIsSelected()
        runOnIdle { counts = AttentionCounts(running = 2, needsYou = 3) }
        onNodeWithText("Running").assertIsSelected()
        onNodeWithText("Needs you").performClick().assertIsSelected()
        runOnIdle { counts = AttentionCounts(running = 2, needsYou = 0) }
        onNodeWithText("All chats").assertIsSelected()
        runOnIdle { counts = AttentionCounts(running = 2, needsYou = 1) }
        onNodeWithText("Needs you").assertIsSelected()
    }

    @Test
    fun compactRosterUsesOneRowPerBotEvenInAWideDrawer() = runComposeUiTest {
        setContent {
            HermesTheme {
                Box(Modifier.size(500.dp, 600.dp)) {
                    BotsRoster(
                        state = BotsUiState(
                            bots = listOf(Bot("mimi", displayName = "Mimi"), Bot("rook", displayName = "Rook")),
                            loading = false,
                        ),
                        avatars = emptyMap(),
                        selectedId = null,
                        selectedRunning = false,
                        nowSeconds = 0.0,
                        actions = PreviewBotActions,
                        onRetry = {},
                        onDismissNotice = {},
                        compactRows = true,
                    )
                }
            }
        }

        val mimi = onNodeWithText("Mimi").fetchSemanticsNode().boundsInRoot
        val rook = onNodeWithText("Rook").fetchSemanticsNode().boundsInRoot
        assertEquals(mimi.left, rook.left)
        assertTrue(rook.top >= mimi.bottom)
    }
}
