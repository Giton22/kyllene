package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Lets go of a focused field's focus when the on-screen keyboard is put away. Back hides the keyboard but leaves the
 * field focused, and a focused field brings the keyboard back on the next relayout (opening a tool card, the menu),
 * so put away means done typing.
 *
 * Not while a hardware keyboard is attached: some on-screen keyboards (Samsung Keyboard) hide themselves on the first
 * physical key, and the typing goes on from the hardware keyboard (#472).
 */
@Composable
internal fun ReleaseFocusWhenKeyboardPutAway(focused: Boolean) =
    ReleaseFocusWhenKeyboardPutAway(focused, keyboardUp = imeVisible, hardwareKeyboard = hardwareKeyboard)

@Composable
internal fun ReleaseFocusWhenKeyboardPutAway(focused: Boolean, keyboardUp: Boolean, hardwareKeyboard: Boolean) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(keyboardUp) { if (!keyboardUp && focused && !hardwareKeyboard) focusManager.clearFocus() }
}
