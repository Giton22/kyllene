package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

/** A desktop input method finishes its own word on Enter, so the key needs nothing here. */
actual fun Modifier.commitComposingOnEnter(state: TextFieldState): Modifier = this
