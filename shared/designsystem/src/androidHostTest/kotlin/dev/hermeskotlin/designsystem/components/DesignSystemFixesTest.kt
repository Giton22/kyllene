package dev.hermeskotlin.designsystem.components

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h360dp")
class DesignSystemFixesTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun haloWithZeroSizeDoesNotThrow() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                Column {
                    Box(Modifier.size(0.dp).halo())
                    Box(Modifier.width(0.dp).height(80.dp).halo())
                    Box(Modifier.width(80.dp).height(0.dp).halo())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun phoneActionSheetScrollsInAShortLandscapeWindow() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                ActionSheet(visible = true, onDismiss = {}) {
                    SheetHeader("Actions")
                    for (i in 1..14) {
                        SheetAction("Action $i", Lucide.Check, onClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Action 14").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dialogKeepsActionsVisibleWhenBodyOverflows() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                Dialog(
                    visible = true,
                    onDismissRequest = {},
                    title = "Long dialog",
                    actions = { Button("Confirm", onClick = {}) },
                ) {
                    for (i in 1..12) {
                        Box(Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Row $i")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Row 12").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dialogSupportsCallersWithNestedWeightedScroll() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                Dialog(
                    visible = true,
                    onDismissRequest = {},
                    title = "Import certificate",
                    actions = { Button("Import", onClick = {}) },
                ) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text("Enter the password to import this certificate.")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Import").assertIsDisplayed()
        composeRule.onNodeWithText("Enter the password to import this certificate.").assertIsDisplayed()
    }

    @Test
    fun segmentedControlDesktopArrowKeysRespectDirectionFocusAndBoundaries() {
        var selected by mutableStateOf("A")
        var direction by mutableStateOf(LayoutDirection.Ltr)
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(
                    LocalDesktopLayout provides true,
                    LocalLayoutDirection provides direction,
                ) {
                    Column {
                        SegmentedControl(
                            options = listOf("A", "B", "C"),
                            selected = selected,
                            onSelect = { selected = it },
                            optionLabel = { it },
                        )
                        Box(Modifier.size(20.dp).testTag("after").focusable())
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("A").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithText("A").assertIsFocused()

        // At the leading boundary in LTR, Left is consumed and keeps focus inside the control.
        composeRule.onNodeWithText("A").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        assertEquals("A", selected)
        composeRule.onNodeWithText("A").assertIsFocused()

        // Right in LTR moves selection and focus to B.
        composeRule.onNodeWithText("A").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals("B", selected)
        composeRule.onNodeWithText("B").assertIsFocused()

        // In RTL, Left advances to C and Right steps back to B.
        direction = LayoutDirection.Rtl
        composeRule.waitForIdle()
        composeRule.onNodeWithText("B").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        assertEquals("C", selected)
        composeRule.onNodeWithText("C").assertIsFocused()

        composeRule.onNodeWithText("C").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals("B", selected)
        composeRule.onNodeWithText("B").assertIsFocused()
    }

    @Test
    fun codeViewUnwrappedShortFileSpansViewportWidth() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                CodeView(
                    text = "val x = 1",
                    language = "kotlin",
                    modifier = Modifier.width(360.dp).height(120.dp),
                    focusLine = 1,
                    wrap = false,
                )
            }
        }
        composeRule.waitForIdle()

        val listBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .getUnclippedBoundsInRoot()
        val width = listBounds.right - listBounds.left
        assertTrue(width >= 359.dp, "expected unwrapped CodeView list to span 360dp viewport, got $width")
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
