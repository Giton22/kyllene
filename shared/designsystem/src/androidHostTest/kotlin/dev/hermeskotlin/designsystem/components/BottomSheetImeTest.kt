package dev.hermeskotlin.designsystem.components

import android.content.ComponentName
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
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
import kotlin.math.abs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class BottomSheetImeTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun aTallSheetFitsAboveTheKeyboardAndMovesDownAsItCloses() = checkKeyboardTransition(700)

    @Test
    fun aShortSheetFollowsTheKeyboardDownWithoutJumpingUp() = checkKeyboardTransition(200)

    @Test
    fun theNavigationBarIsNotPaddedTwiceUnderTheKeyboard() {
        showSheet(200)
        val height = view.height
        val navigationBar = height / 20
        val keyboard = height / 2
        inset(0, navigationBar)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        val gapOverBar = height - bounds.bottom
        // As on a device, the keyboard's inset takes in the navigation bar it covers.
        inset(keyboard, navigationBar)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        val gapOverKeyboard = height - bounds.bottom
        assertTrue(
            abs(gapOverKeyboard - gapOverBar - (keyboard - navigationBar)) <= 1,
            "The sheet must sit on the keyboard, the bar under it not padded again: $gapOverBar -> $gapOverKeyboard " +
                "(bar $navigationBar, keyboard $keyboard)",
        )
    }

    private lateinit var view: View
    private var bounds = Rect.Zero

    private fun showSheet(contentHeight: Int) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                BottomSheet(visible = true, onDismiss = {}) {
                    val currentView = LocalView.current
                    SideEffect { view = currentView }
                    Box(Modifier.fillMaxWidth().height(contentHeight.dp).onGloballyPositioned { bounds = it.boundsInWindow() })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
    }

    private fun inset(bottom: Int, navigationBar: Int = 0) {
        composeRule.runOnIdle {
            view.dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .apply {
                        if (navigationBar > 0) {
                            setInsets(WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, navigationBar))
                            setVisible(WindowInsets.Type.navigationBars(), true)
                        }
                    }
                    .setInsets(WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsets.Type.ime(), bottom > 0)
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun checkKeyboardTransition(contentHeight: Int) {
        showSheet(contentHeight)
        val height = view.height
        val keyboard = height / 2
        inset(keyboard)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        assertTrue(bounds.height > 0, "The sheet must be measured")
        assertTrue(bounds.bottom <= height - keyboard + 1, "Sheet bottom $bounds must clear the keyboard ($keyboard of $height)")
        val keyboardTop = bounds.top
        var previous = keyboardTop
        for (bottom in listOf(keyboard * 3 / 4, keyboard / 2, keyboard / 4, 0)) {
            inset(bottom)
            assertTrue(bounds.top >= previous - 1, "Closing IME moved sheet upward: $previous -> ${bounds.top}")
            assertTrue(bounds.bottom <= height - bottom + 1, "Sheet bottom must clear each IME frame")
            previous = bounds.top
        }
        assertTrue(bounds.top > keyboardTop + 1, "The sheet must actually move down as the IME closes")
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
