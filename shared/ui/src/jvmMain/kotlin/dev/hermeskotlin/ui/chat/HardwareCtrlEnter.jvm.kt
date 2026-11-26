package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

internal actual fun Modifier.sendOnHardwareCtrlEnter(
    state: TextFieldState,
    onSend: (() -> Unit)?,
): Modifier = this
