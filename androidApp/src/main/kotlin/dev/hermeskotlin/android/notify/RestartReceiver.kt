package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermeskotlin.android.phone.Geofences
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Wakes the app after a restart of the phone or an update of Herald, so Notifications anywhere picks its
 * ntfy stream up again ([dev.hermeskotlin.android.push.PushListener]) and watched places are re-armed
 * ([Geofences]). Holds the broadcast with [goAsync] until stored settings load and startup kicks run.
 */
class RestartReceiver : BroadcastReceiver(), KoinComponent {

    private val settings: SettingsStore by inject()
    private val notifier: ChatNotifier by inject()
    private val geofences: Geofences by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    val prefs = settings.settings.filterNotNull().first()
                    if (prefs.pushAnywhere) {
                        withContext(Dispatchers.Main.immediate) { notifier.startServiceNow() }
                    }
                    geofences.syncNow()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 5_000L
    }
}
