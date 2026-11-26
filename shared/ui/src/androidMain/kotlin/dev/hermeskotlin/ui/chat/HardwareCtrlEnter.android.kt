package dev.hermeskotlin.ui.chat

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
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

internal actual fun Modifier.sendOnHardwareCtrlEnter(
    state: TextFieldState,
    onSend: (() -> Unit)?,
): Modifier = composed {
    if (onSend == null) return@composed this
    val view = LocalView.current
    onPreviewKeyEvent { event ->
        val isCtrlEnter = (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
            event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed
        if (!isCtrlEnter) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    if (state.composition != null) {
                        // Force an edit without changing the text; Compose clears composition.
                        state.edit {
                            val end = length
                            append(" ")
                            replace(end, end + 1, "")
                        }
                        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .restartInput(view)
                    }
                    onSend()
                }
                true
            }
            KeyEventType.KeyUp -> true
            else -> false
        }
    }
}
