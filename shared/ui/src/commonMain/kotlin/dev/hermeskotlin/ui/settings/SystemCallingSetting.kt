package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.settings_system_calling
import dev.hermeskotlin.ui.resources.settings_system_calling_detail
import org.jetbrains.compose.resources.stringResource

@Composable
internal expect fun androidCallControlsAvailable(): Boolean

@Composable
internal fun SystemCallingSetting(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val title = stringResource(Res.string.settings_system_calling)
    val detail = stringResource(Res.string.settings_system_calling_detail)
    if (!settingShown(title, detail)) return

    SwitchRow(title, detail, enabled, onCheckedChange = onEnabledChange)
    Divider()
}
