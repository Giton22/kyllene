package dev.hermeskotlin.ui.signin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** App info: Android has no public link to an app's battery usage, which is one tap in from there ("App battery usage"). */
@Composable
internal actual fun rememberBatterySettingsOpener(): (() -> Unit)? {
    val context = LocalContext.current
    return remember<() -> Unit>(context) {
        {
            // In Kyllene's own task, so the app's page opens fresh and Back comes back to the sign-in. A device that
            // won't open it (or the settings at all) leaves the button doing nothing rather than crashing.
            fun open(intent: Intent): Boolean {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching { context.startActivity(intent) }.isSuccess
            }
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) ||
                open(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
