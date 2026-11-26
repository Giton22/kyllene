package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange

actual fun Modifier.commitComposingOnEnter(state: TextFieldState, inputTransformation: InputTransformation?): Modifier = onPreviewKeyEvent { event ->
    val plainEnter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
        !event.isShiftPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed
    if (!plainEnter || state.composition == null) return@onPreviewKeyEvent false
    state.edit {
        val start = selection.min
        replace(start, selection.max, "\n")
        selection = TextRange(start + 1)
        inputTransformation?.run { transformInput() }
    }
    true
}
