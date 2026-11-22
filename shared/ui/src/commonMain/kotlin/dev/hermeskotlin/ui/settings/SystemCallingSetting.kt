package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import dev.hermeskotlin.core.voice.SystemCallingStatus
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal data class SystemCallingAccess(
    val status: SystemCallingStatus,
    val requestPermissions: () -> Unit = {},
    val openAccounts: () -> Unit = {},
    val openAppSettings: (() -> Unit)? = null,
    val accountHelp: StringResource? = null,
    val openPhoneApp: (() -> Unit)? = null,
)

@Composable
internal expect fun rememberSystemCallingAccess(): SystemCallingAccess?

@Composable
internal fun SystemCallingSetting(access: SystemCallingAccess, enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    if (access.status == SystemCallingStatus.Unavailable) return
    val title = stringResource(Res.string.settings_system_calling)
    val detail = stringResource(Res.string.settings_system_calling_detail)
    if (!settingShown(title, detail)) return
    var followingSetup by remember { mutableStateOf(false) }
    val guide = remember { BringIntoViewRequester() }
    SwitchRow(title, detail, enabled, onCheckedChange = { on ->
        followingSetup = on
        onEnabledChange(on)
    })
    if (enabled) {
        LaunchedEffect(followingSetup, access.status) {
            if (followingSetup && access.status != SystemCallingStatus.Ready) guide.bringIntoView()
        }
        Column(
            Modifier.bringIntoViewRequester(guide).padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (access.status) {
                SystemCallingStatus.NeedsPermissions -> {
                    SetupStep(Res.string.settings_system_calling_step_permissions, Res.string.settings_system_calling_permissions_detail)
                    Button(stringResource(Res.string.settings_system_calling_allow), onClick = access.requestPermissions)
                    access.openAppSettings?.let { open ->
                        Text(
                            stringResource(Res.string.settings_system_calling_permissions_manual),
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textSecondary],
                        )
                        Button(stringResource(Res.string.settings_system_calling_app_settings), onClick = open, variant = ButtonVariant.Secondary)
                    }
                }
                SystemCallingStatus.NeedsAccount -> {
                    SetupStep(Res.string.settings_system_calling_step_account, Res.string.settings_system_calling_account_detail)
                    Button(stringResource(Res.string.settings_system_calling_open_accounts), onClick = access.openAccounts)
                    access.accountHelp?.let { help ->
                        Text(stringResource(help), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                    }
                    access.openPhoneApp?.let { open ->
                        Button(stringResource(Res.string.settings_system_calling_open_phone), onClick = open, variant = ButtonVariant.Secondary)
                    }
                }
                SystemCallingStatus.Ready -> Text(
                    stringResource(Res.string.settings_system_calling_ready),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][accentText],
                )
                SystemCallingStatus.Unavailable -> Unit
            }
        }
    }
    Divider()
}

@Composable
private fun SetupStep(title: StringResource, detail: StringResource) {
    Text(stringResource(title), style = Theme[typography][body], color = Theme[colors][accentText])
    Text(stringResource(detail), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
}
