package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import org.junit.Before
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Samsung Keyboard keeps its predictive-text word in the IME composing buffer until the app restarts input or
 * finishes composing. Pressing soft-keyboard Enter on an active composing region must commit the region and
 * trigger [InputMethodManager.restartInput] so the previous word is not retyped on the new line (#430).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
    shadows = [ComposerEnterImeTest.TrackingInputMethodManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerEnterImeTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Before
    fun resetTracker() {
        TrackingInputMethodManager.restartCount = 0
    }

    private fun setupComposer(): Pair<TextFieldState, View> {
        val state = TextFieldState()
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.width(360.dp)) {
                    ComposerTextInput(
                        state = state,
                        modifier = Modifier.testTag("composer"),
                        placeholder = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer").performClick()
        composeRule.waitForIdle()
        return state to assertNotNull(hostView)
    }

    private fun sendSoftEnter(view: View) {
        val ic = assertNotNull(
            view.onCreateInputConnection(EditorInfo()),
            "expected active InputConnection once ComposerTextInput is focused",
        )
        val down = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ENTER,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
        val up = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_ENTER,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
        ic.sendKeyEvent(down)
        ic.sendKeyEvent(up)
        composeRule.waitForIdle()
    }

    @Test
    fun softEnterWhileComposingInsertsNewlineAndRestartsIme() {
        val (state, hostView) = setupComposer()
        val ic = assertNotNull(hostView.onCreateInputConnection(EditorInfo()))
        ic.setComposingText("world", 1)
        composeRule.waitForIdle()
        assertEquals("world", state.text.toString())
        assertEquals(TextRange(0, 5), state.composition)

        TrackingInputMethodManager.restartCount = 0
        sendSoftEnter(hostView)

        assertEquals("world\n", state.text.toString())
        assertNull(state.composition)
        assertEquals(TextRange(6), state.selection)
        assertTrue(
            TrackingInputMethodManager.restartCount > 0,
            "expected InputMethodManager.restartInput when Enter is pressed with an active composing region",
        )
    }

    @Test
    fun softEnterWithoutCompositionInsertsNewlineWithoutRestartingIme() {
        val (state, hostView) = setupComposer()
        val ic = assertNotNull(hostView.onCreateInputConnection(EditorInfo()))
        ic.commitText("world", 1)
        composeRule.waitForIdle()
        assertEquals("world", state.text.toString())
        assertNull(state.composition)

        TrackingInputMethodManager.restartCount = 0
        sendSoftEnter(hostView)

        assertEquals("world\n", state.text.toString())
        assertNull(state.composition)
        assertEquals(TextRange(6), state.selection)
        assertEquals(
            0,
            TrackingInputMethodManager.restartCount,
            "did not expect InputMethodManager.restartInput when no composing region is active",
        )
    }

    @Implements(InputMethodManager::class)
    class TrackingInputMethodManager : ShadowInputMethodManager() {
        @Implementation
        override fun restartInput(view: View?) {
            restartCount++
            super.restartInput(view)
        }

        companion object {
            var restartCount = 0
        }
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
