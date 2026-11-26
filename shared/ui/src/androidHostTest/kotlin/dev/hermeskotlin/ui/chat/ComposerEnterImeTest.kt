package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
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
import dev.hermeskotlin.designsystem.components.TextField
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

    /**
     * Shows [field] (the chat's composer unless given), focuses it, and returns its state and the keyboard's
     * connection to it.
     */
    private fun setupComposer(
        onSend: (() -> Unit)? = null,
        field: @Composable (TextFieldState, Modifier) -> Unit = { state, modifier ->
            ComposerTextInput(state = state, modifier = modifier, onSend = onSend, placeholder = {})
        },
    ): Pair<TextFieldState, InputConnection> {
        val state = TextFieldState()
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.width(360.dp)) {
                    field(state, Modifier.testTag("composer"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("composer").performClick()
        composeRule.waitForIdle()
        val ic = assertNotNull(
            assertNotNull(hostView).onCreateInputConnection(EditorInfo()),
            "expected an InputConnection once the composer is focused",
        )
        return state to ic
    }

    /** Enter as a phone keyboard sends it: a key down and up flagged as coming from the soft keyboard. */
    private fun InputConnection.sendSoftEnter() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            sendKeyEvent(
                KeyEvent(
                    0L, 0L, action, KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                ),
            )
        }
        composeRule.waitForIdle()
    }

    /** Hardware key event as a physical keyboard sends it. */
    private fun InputConnection.sendHardwareKey(
        action: Int,
        keyCode: Int,
        repeatCount: Int = 0,
        metaState: Int = 0,
    ) {
        sendKeyEvent(
            KeyEvent(
                0L, 0L, action, keyCode, repeatCount, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            ),
        )
        composeRule.waitForIdle()
    }

    private fun InputConnection.sendHardwareCtrlEnter(repeatCount: Int = 0) {
        sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeatCount, KeyEvent.META_CTRL_ON)
        sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_CTRL_ON)
    }

    private fun InputConnection.sendHardwareCtrlNumPadEnter(repeatCount: Int = 0) {
        sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER, repeatCount, KeyEvent.META_CTRL_ON)
        sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER, 0, KeyEvent.META_CTRL_ON)
    }

    @Test
    fun hardwareCtrlEnterSendsOnceAndDoesNotInsertNewline() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.commitText("hello", 1)
        composeRule.waitForIdle()
        assertEquals("hello", state.text.toString())

        ic.sendHardwareCtrlEnter()

        assertEquals(1, sends, "expected Ctrl+Enter to trigger send once")
        assertEquals("hello", state.text.toString(), "Ctrl+Enter should not insert a newline or text")
    }

    @Test
    fun hardwareCtrlNumPadEnterSendsOnceAndDoesNotInsertNewline() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.commitText("hello", 1)
        composeRule.waitForIdle()
        assertEquals("hello", state.text.toString())

        ic.sendHardwareCtrlNumPadEnter()

        assertEquals(1, sends, "expected Ctrl+NumPadEnter to trigger send once")
        assertEquals("hello", state.text.toString(), "Ctrl+NumPadEnter should not insert a newline or text")
    }

    @Test
    fun hardwareCtrlEnterRepeatAndKeyUpDoNotDoubleSendOrInsertText() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.commitText("hello", 1)
        composeRule.waitForIdle()

        // Initial KeyDown (repeat = 0)
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeatCount = 0, metaState = KeyEvent.META_CTRL_ON)
        assertEquals(1, sends, "initial keydown should send once")
        assertEquals("hello", state.text.toString())

        // Repeated KeyDown (repeat = 1, 2)
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeatCount = 1, metaState = KeyEvent.META_CTRL_ON)
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeatCount = 2, metaState = KeyEvent.META_CTRL_ON)
        assertEquals(1, sends, "repeated keydown should not double-send")
        assertEquals("hello", state.text.toString(), "repeated keydown should not insert text")

        // KeyUp
        ic.sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, repeatCount = 0, metaState = KeyEvent.META_CTRL_ON)
        assertEquals(1, sends, "keyup should not double-send")
        assertEquals("hello", state.text.toString(), "keyup should not insert text")
    }

    @Test
    fun extraModifiersDoNotSend() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.commitText("hello", 1)
        composeRule.waitForIdle()

        // Ctrl + Shift + Enter
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        ic.sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals(0, sends, "Ctrl+Shift+Enter must not send")

        // Ctrl + Alt + Enter
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        ic.sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertEquals(0, sends, "Ctrl+Alt+Enter must not send")

        // Alt + Enter
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_ALT_ON)
        ic.sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, metaState = KeyEvent.META_ALT_ON)
        assertEquals(0, sends, "Alt+Enter must not send")
    }

    @Test
    fun hardwareCtrlEnterSendsComposingKoreanOnceAndNextInputIsClean() {
        val sent = mutableListOf<String>()
        lateinit var state: TextFieldState
        val (composer, ic) = setupComposer(onSend = {
            assertNull(state.composition, "composition must be committed before sending")
            sent += state.text.toString()
            state.edit { replace(0, length, "") }
        })
        state = composer
        ic.commitText("안녕", 1)
        ic.setComposingText("하세", 1)
        ic.setComposingText("하세요", 1)
        composeRule.waitForIdle()
        assertEquals("안녕하세요", state.text.toString())
        assertNotNull(state.composition)
        TrackingInputMethodManager.restartCount = 0

        ic.sendHardwareCtrlEnter()

        assertEquals(listOf("안녕하세요"), sent)
        assertEquals("", state.text.toString())
        assertNull(state.composition)
        assertTrue(TrackingInputMethodManager.restartCount > 0, "IME must restart after finishing composition")
        ic.setComposingText("다음", 1)
        composeRule.waitForIdle()
        assertEquals("다음", state.text.toString())
        assertEquals(listOf("안녕하세요"), sent)
    }

    @Test
    fun hardwareCtrlEnterCommitsCompositionEvenWhenSendDoesNotClear() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.setComposingText("한글", 1)
        composeRule.waitForIdle()
        TrackingInputMethodManager.restartCount = 0
        ic.sendHardwareCtrlEnter()
        assertEquals(1, sends)
        assertEquals("한글", state.text.toString())
        assertNull(state.composition)
        assertTrue(TrackingInputMethodManager.restartCount > 0)
    }

    @Test
    fun plainHardwareEnterInsertsNewlineAndDoesNotSend() {
        var sends = 0
        val (state, ic) = setupComposer(onSend = { sends++ })
        ic.commitText("hello", 1)
        composeRule.waitForIdle()

        // Hardware enter without modifiers
        ic.sendHardwareKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, metaState = 0)
        ic.sendHardwareKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, metaState = 0)

        assertEquals(0, sends, "plain hardware Enter must not send")
        assertEquals("hello\n", state.text.toString(), "plain hardware Enter should insert newline")
    }

    @Test
    fun softEnterWhileComposingInsertsNewlineAndRestartsIme() {
        val (state, ic) = setupComposer()
        assertEnterWhileComposingRestartsIme(state, ic)
    }

    /** The other multi-line fields, like a clarify answer or a job's prompt, use the design system's field. */
    @Test
    fun multiLineTextFieldRestartsImeOnEnterWhileComposing() {
        val (state, ic) = setupComposer { state, modifier -> TextField(state, modifier, singleLine = false) }
        assertEnterWhileComposingRestartsIme(state, ic)
    }

    private fun assertEnterWhileComposingRestartsIme(state: TextFieldState, ic: InputConnection) {
        // The issue's steps: "hello " is committed, "world" is still the keyboard's word in progress.
        ic.commitText("hello ", 1)
        ic.setComposingText("world", 1)
        composeRule.waitForIdle()
        assertEquals("hello world", state.text.toString())
        assertEquals(TextRange(6, 11), state.composition)

        TrackingInputMethodManager.restartCount = 0
        ic.sendSoftEnter()

        assertEquals("hello world\n", state.text.toString())
        assertNull(state.composition)
        assertEquals(TextRange(12), state.selection)
        assertTrue(
            TrackingInputMethodManager.restartCount > 0,
            "expected InputMethodManager.restartInput when Enter is pressed with an active composing region",
        )
    }

    @Test
    fun softEnterWithoutCompositionInsertsNewlineWithoutRestartingIme() {
        val (state, ic) = setupComposer()
        ic.commitText("world", 1)
        composeRule.waitForIdle()
        assertEquals("world", state.text.toString())
        assertNull(state.composition)

        TrackingInputMethodManager.restartCount = 0
        ic.sendSoftEnter()

        assertEquals("world\n", state.text.toString())
        assertNull(state.composition)
        assertEquals(TextRange(6), state.selection)
        assertEquals(
            0,
            TrackingInputMethodManager.restartCount,
            "did not expect InputMethodManager.restartInput when no composing region is active",
        )
    }

    // A list goes on whichever way the keyboard sends the line break.

    @Test
    fun softEnterWhileComposingContinuesAList() {
        val (state, ic) = setupComposer()
        ic.commitText("1. blah ", 1)
        ic.setComposingText("balh", 1)
        composeRule.waitForIdle()
        ic.sendSoftEnter()
        assertEquals("1. blah balh\n2. ", state.text.toString())
        assertEquals(TextRange(16), state.selection)
    }

    @Test
    fun softEnterWithoutCompositionContinuesAList() {
        val (state, ic) = setupComposer()
        ic.commitText("- milk", 1)
        composeRule.waitForIdle()
        ic.sendSoftEnter()
        assertEquals("- milk\n- ", state.text.toString())
        assertEquals(TextRange(9), state.selection)
    }

    @Test
    fun aCommittedLineBreakContinuesAListAndASecondEndsIt() {
        val (state, ic) = setupComposer()
        ic.commitText("1. eggs", 1)
        // Some keyboards finish the word and send the line break as one commit.
        ic.setComposingText("brea", 1)
        ic.commitText(" bread\n", 1)
        composeRule.waitForIdle()
        assertEquals("1. eggs bread\n2. ", state.text.toString())
        assertEquals(TextRange(17), state.selection)

        ic.commitText("\n", 1)
        composeRule.waitForIdle()
        assertEquals("1. eggs bread\n", state.text.toString())
        assertEquals(TextRange(14), state.selection)
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
