package dev.hermeskotlin.ui.inbox

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.preview.InboxSamples
import dev.hermeskotlin.ui.preview.PreviewInboxActions
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
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxRowsTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun folderRowsKeepStatusAndOpenTheirReports() {
        var state by mutableStateOf(InboxSamples.folders())
        val expectedRun = state.runs.single { it.failed }
        var opened: InboxRun? = null
        val actions = object : InboxActions by PreviewInboxActions {
            override fun setFilter(filter: InboxFilter) { state = state.copy(filter = filter) }
            override fun open(run: InboxRun) { opened = run }
        }
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                InboxView(state, actions, onBack = {}, onOpenSettings = {})
            }
        }

        composeRule.onNodeWithText("1 failed", substring = true).assertExists()
        assertEquals(2, composeRule.onAllNodes(hasContentDescription("1 unread")).fetchSemanticsNodes().size)
        val excerpt = "The site didn't answer"
        composeRule.onNodeWithText(excerpt, substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Failed runs").performClick()
        composeRule.onNodeWithText("Failed ·", substring = true).assertExists()
        composeRule.onNodeWithText(excerpt, substring = true).performClick()
        composeRule.runOnIdle { assertEquals(expectedRun, opened) }
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
