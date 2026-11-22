package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.phone.requiresPhoneToolPermission
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The parent grants tool access; its companion controls whether existing write actions must ask first. */
@Composable
internal fun PhoneToolPermissionRow(tool: PhoneTool, settings: AppSettings, onPermission: (PhoneTool, Boolean) -> Unit) {
    val title = tool.approvalTitle ?: return
    Box(Modifier.padding(start = 46.dp)) {
        SwitchRow(
            title = stringResource(title),
            titleColor = Theme[colors][textSecondary],
            detail = null,
            checked = settings.requiresPhoneToolPermission(tool),
            enabled = tool in settings.enabledPhoneTools(),
            onCheckedChange = { onPermission(tool, it) },
        )
    }
}

private val PhoneTool.approvalTitle: StringResource?
    get() = when (this) {
        PhoneTool.Sound -> Res.string.phone_approval_sound
        PhoneTool.Alarms -> Res.string.phone_approval_alarms
        PhoneTool.Open -> Res.string.phone_approval_open
        PhoneTool.Clipboard -> Res.string.phone_approval_clipboard
        PhoneTool.Geofences -> Res.string.phone_approval_geofences
        PhoneTool.Calendar -> Res.string.phone_approval_calendar
        else -> null
    }
