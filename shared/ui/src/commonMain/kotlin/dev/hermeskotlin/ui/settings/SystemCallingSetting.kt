package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.voice.SystemCallingStatus
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.title
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
    val ready = enabled && access.status == SystemCallingStatus.Ready
    val subtitle = if (ready) stringResource(Res.string.settings_system_calling_ready) else detail
    if (!settingShown(title, subtitle)) return

    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun openSetup() {
        focus.clearFocus()
        setupOpen = true
    }

    SwitchRow(title, subtitle, enabled, onCheckedChange = { on ->
        onEnabledChange(on)
        if (on && access.status != SystemCallingStatus.Ready) openSetup() else setupOpen = false
    })
    if (enabled && !ready) {
        Button(
            stringResource(Res.string.settings_system_calling_finish),
            onClick = ::openSetup,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            trailingIcon = Lucide.ChevronRight,
        )
    }
    Divider()
    SystemCallingSetupSheet(access, visible = enabled && setupOpen, onDismiss = { setupOpen = false })
}

@Composable
private fun SystemCallingSetupSheet(access: SystemCallingAccess, visible: Boolean, onDismiss: () -> Unit) {
    val needsPermissions = access.status == SystemCallingStatus.NeedsPermissions
    val needsAccount = access.status == SystemCallingStatus.NeedsAccount
    val ready = access.status == SystemCallingStatus.Ready
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.settings_system_calling),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = Theme[typography][title],
                    color = Theme[colors][text],
                )
                IconButton(Lucide.X, stringResource(Res.string.chat_close), onClick = onDismiss)
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CallingSetupStep(
                    Res.string.settings_system_calling_step_permissions,
                    Lucide.ShieldCheck,
                    active = needsPermissions,
                    done = needsAccount || ready,
                    detail = Res.string.settings_system_calling_permissions_detail,
                )
                CallingSetupStep(
                    Res.string.settings_system_calling_step_account,
                    Lucide.Phone,
                    active = needsAccount,
                    done = ready,
                    detail = access.accountHelp ?: Res.string.settings_system_calling_account_detail,
                )
            }
            when (access.status) {
                SystemCallingStatus.NeedsPermissions -> {
                    Button(
                        stringResource(Res.string.settings_system_calling_allow),
                        onClick = access.requestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    access.openAppSettings?.let { open ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(Res.string.settings_system_calling_permissions_manual),
                                style = Theme[typography][bodySmall],
                                color = Theme[colors][textSecondary],
                            )
                            Button(
                                stringResource(Res.string.settings_system_calling_app_settings),
                                onClick = open,
                                modifier = Modifier.fillMaxWidth(),
                                variant = ButtonVariant.Ghost,
                            )
                        }
                    }
                }
                SystemCallingStatus.NeedsAccount -> Button(
                    stringResource(
                        if (access.openPhoneApp == null) Res.string.settings_system_calling_open_accounts
                        else Res.string.settings_system_calling_open_phone,
                    ),
                    onClick = access.openPhoneApp ?: access.openAccounts,
                    modifier = Modifier.fillMaxWidth(),
                )
                SystemCallingStatus.Ready -> Button(
                    stringResource(Res.string.app_done),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
                SystemCallingStatus.Unavailable -> Unit
            }
        }
    }
}

@Composable
private fun CallingSetupStep(title: StringResource, icon: ImageVector, active: Boolean, done: Boolean, detail: StringResource) {
    val accent = Theme[colors][accentText]
    val secondary = Theme[colors][textSecondary]
    val doneDescription = stringResource(Res.string.app_done)
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Theme[colors][accentSoft] else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) { if (done) stateDescription = doneDescription },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).background(Theme[colors][surface2], CircleShape), contentAlignment = Alignment.Center) {
            UnstyledIcon(
                if (done) Lucide.Check else icon,
                contentDescription = null,
                tint = if (active || done) accent else secondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(title),
                style = Theme[typography][body].copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                color = if (active || done) Theme[colors][text] else secondary,
            )
            if (active) {
                Text(stringResource(detail), style = Theme[typography][bodySmall], color = secondary)
            }
        }
    }
}
