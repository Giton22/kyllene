package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

// Return starts a new line and Send is the button, as on the iOS keyboard; no Ctrl+Enter yet.
internal actual fun Modifier.sendOnHardwareCtrlEnter(
    state: TextFieldState,
    onSend: (() -> Unit)?,
): Modifier = this
