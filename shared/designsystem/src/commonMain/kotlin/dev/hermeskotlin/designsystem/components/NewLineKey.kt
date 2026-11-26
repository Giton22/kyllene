package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

/**
 * Starts a new line on a phone's Enter key without the keyboard typing the last word again (#430). Compose's
 * own soft-keyboard Enter ends the word being typed but doesn't restart the keyboard, so Samsung Keyboard keeps
 * that word and puts it on the new line. On Android, while a word is being typed, this inserts the line break
 * with [TextFieldState.edit], which does restart it. Otherwise, and on the desktop, Enter goes on as before.
 * Goes on a multi-line field's own modifier; [TextField] has it already. Give it the field's [inputTransformation]
 * too, since an edit made this way skips it.
 */
expect fun Modifier.commitComposingOnEnter(state: TextFieldState, inputTransformation: InputTransformation? = null): Modifier
