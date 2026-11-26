package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier

/** The iOS keyboard finishes its own word on Return, through UIKit's text input, so the key needs nothing here. */
actual fun Modifier.commitComposingOnEnter(state: TextFieldState, inputTransformation: InputTransformation?): Modifier = this
