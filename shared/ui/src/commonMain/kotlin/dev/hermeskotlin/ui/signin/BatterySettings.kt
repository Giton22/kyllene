package dev.hermeskotlin.ui.signin

import androidx.compose.runtime.Composable

/** Opens the app's page in the system settings, where its battery usage is set; null where there's none (desktop). */
@Composable
internal expect fun rememberBatterySettingsOpener(): (() -> Unit)?
