package dev.hermeskotlin.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hermeskotlin.core.voice.SystemCallingStatus
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject

@Composable
internal actual fun rememberSystemCallingAccess(): SystemCallingAccess? {
    val context = LocalContext.current
    val voice = koinInject<VoiceKeepAlive>()
    var refresh by remember { mutableIntStateOf(0) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var accountHelp by remember { mutableStateOf<StringResource?>(null) }
    val permissionPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        refresh++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val status = remember(voice, refresh) { voice.systemCallingStatus() }
    if (status == SystemCallingStatus.Unavailable) return null

    fun open(intent: Intent): Boolean = runCatching { context.startActivity(intent) }.isSuccess

    val appSettings: () -> Unit = {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    val phoneApp: () -> Unit = { open(Intent(Intent.ACTION_DIAL)) }
    return SystemCallingAccess(
        status = status,
        requestPermissions = {
            val missing = callingPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) permissionPrompt.launch(missing.toTypedArray()) else refresh++
        },
        openAccounts = {
            accountHelp = when {
                !voice.prepareSystemCallingAccount() -> Res.string.settings_system_calling_account_failed
                !open(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) -> Res.string.settings_system_calling_manual
                else -> null
            }
            refresh++
        },
        openAppSettings = appSettings.takeIf { asked },
        accountHelp = accountHelp,
        openPhoneApp = phoneApp.takeIf { accountHelp == Res.string.settings_system_calling_manual },
    )
}

private val callingPermissions = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_NUMBERS)
