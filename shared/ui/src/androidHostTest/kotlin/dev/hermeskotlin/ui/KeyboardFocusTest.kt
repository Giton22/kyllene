package dev.hermeskotlin.ui

import android.content.ComponentName
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
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

/**
 * The composer lets go of its focus when the on-screen keyboard is put away, but not while a hardware keyboard is
 * attached: Samsung Keyboard hides itself on the first physical key, and the next keys must still type (#472).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardFocusTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private var keyboardUp by mutableStateOf(true)

    /** Shows a focused field with the keyboard up, then puts the keyboard away. */
    private fun putKeyboardAway(hardwareKeyboard: Boolean) {
        composeRule.setContent {
            var focused by remember { mutableStateOf(false) }
            Column {
                // Robolectric's window never enters touch mode, and out of it Android hands a cleared focus back to
                // the first focusable thing. This takes it, so the field's own focus shows what the helper did.
                Box(Modifier.size(1.dp).focusable())
                BasicTextField(rememberTextFieldState(), Modifier.testTag(FIELD).onFocusChanged { focused = it.isFocused })
            }
            ReleaseFocusWhenKeyboardPutAway(focused, keyboardUp = keyboardUp, hardwareKeyboard = hardwareKeyboard)
        }
        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.onNodeWithTag(FIELD).assertIsFocused()

        keyboardUp = false
        composeRule.waitForIdle()
    }

    @Test
    fun withoutAHardwareKeyboardPuttingTheKeyboardAwayLetsGoOfTheFocus() {
        putKeyboardAway(hardwareKeyboard = false)

        composeRule.onNodeWithTag(FIELD).assertIsNotFocused()
    }

    @Test
    fun withAHardwareKeyboardTheFieldKeepsTheFocusWhenTheOnScreenKeyboardHides() {
        putKeyboardAway(hardwareKeyboard = true)

        composeRule.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun aConnectedKeyboardCountsAsAHardwareKeyboard() {
        assertEquals(true, readHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
    }

    @Test
    fun aPhoneWithNoKeysHasNoHardwareKeyboard() {
        assertEquals(false, readHardwareKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun aKeyboardFoldedAwayIsNotAHardwareKeyboard() {
        assertEquals(false, readHardwareKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    private fun readHardwareKeyboard(keyboard: Int, hidden: Int): Boolean? {
        val configuration = Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply {
            this.keyboard = keyboard
            hardKeyboardHidden = hidden
        }
        var attached: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) { attached = hardwareKeyboard }
        }
        composeRule.waitForIdle()
        return attached
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

    private companion object {
        const val FIELD = "field"
    }
}
