package dev.hermeskotlin.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.SidebarState
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * On a phone, system Back in the open chat goes back to the chat list (the drawer), and on the list leaves the app;
 * whatever is open in front of the chat (a sheet, the sidebar's search) takes Back first (#560).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatListBackTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private lateinit var sidebar: SidebarState
    private lateinit var scope: CoroutineScope

    /** The home's layout: the list in the sidebar, the chat beside it, Back handled as [Home] does. */
    private fun showHome(
        docksFrom: Dp = 10_000.dp,
        chatShown: Boolean = true,
        desktop: Boolean = false,
        inFront: @Composable () -> Unit = {},
    ) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                sidebar = rememberSidebarState()
                scope = rememberCoroutineScope()
                SidebarLayout(state = sidebar, sidebar = { Text("Chats") }, dockedBreakpoint = docksFrom) {
                    Box(Modifier.fillMaxSize()) { Text("Chat") }
                }
                ChatListBackHandler(sidebar, chatShown, desktop)
                inFront()
            }
        }
        composeRule.waitForIdle()
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun backInTheChatOpensTheChatList() {
        showHome()

        back()

        assertTrue(sidebar.isOpen)
        assertEquals(1f, sidebar.fraction)
        assertFalse(composeRule.activity.isFinishing)
    }

    @Test
    fun backOnTheChatListLeavesTheApp() {
        showHome()
        back()

        back()

        assertTrue(composeRule.activity.isFinishing)
    }

    @Test
    fun aHandlerInFrontTakesBackFirst() {
        var searching by mutableStateOf(true)
        showHome { PlatformBackHandler(enabled = searching) { searching = false } }

        back()

        assertFalse(searching)
        assertFalse(sidebar.isOpen)

        back()

        assertTrue(sidebar.isOpen)
    }

    @Test
    fun anOpenSheetClosesBeforeTheChatList() {
        var sheetOpen by mutableStateOf(true)
        showHome { BottomSheet(visible = sheetOpen, onDismiss = { sheetOpen = false }) { Text("Model") } }

        // Back on a phone goes to the window in front, the sheet's own.
        composeRule.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertFalse(sheetOpen)
        assertFalse(sidebar.isOpen)

        back()

        assertTrue(sidebar.isOpen)
    }

    @Test
    fun backLeavesThePageOverTheChatAlone() {
        showHome(chatShown = false)

        back()

        assertFalse(sidebar.isOpen)
        assertTrue(composeRule.activity.isFinishing)
    }

    @Test
    fun aDockedSidebarKeepsBackAsItWas() {
        showHome(docksFrom = 0.dp)
        // Folded away beside the chat, as a closed drawer would be.
        composeRule.runOnIdle { scope.launch { sidebar.close() } }
        composeRule.waitForIdle()
        assertFalse(sidebar.isOpen)

        back()

        assertFalse(sidebar.isOpen)
        assertTrue(composeRule.activity.isFinishing)
    }

    @Test
    fun aDesktopKeepsBackAsItWas() {
        showHome(desktop = true)

        back()

        assertFalse(sidebar.isOpen)
        assertTrue(composeRule.activity.isFinishing)
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
