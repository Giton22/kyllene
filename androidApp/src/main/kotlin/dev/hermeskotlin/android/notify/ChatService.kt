package dev.hermeskotlin.android.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.connection.ForegroundHold
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.settings.SettingsStore
import org.koin.android.ext.android.inject

/**
 * Keeps the process in the foreground while a turn runs (or all the time with Notifications anywhere), so
 * Android doesn't cut the gateway socket when the app is in the background. Its notification is one
 * constant, quiet line ([ChatNotifications.ongoing]), kept current by [ChatNotifier].
 *
 * A special-use service: data sync, the closest standard type, is capped at six hours a day.
 */
class ChatService : Service() {

    private val notifications: ChatNotifications by inject()
    private val connection: GatewayConnection by inject()
    private val settings: SettingsStore by inject()
    private val keeper: ChatServiceKeeper by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Into the foreground first, always: a service started this way and stopped before it is a crash.
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.WORKING_ID,
                notifications.ongoing(connection.state.value, settings.settings.value?.pushAnywhere == true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            Log.w("ChatService", "Couldn't keep the connection up", e)
            keeper.hold.lost()
            stopSelf()
            return START_NOT_STICKY
        }
        // Nothing needs it any more (the turn ended while it was starting): it was left to stop here.
        if (!keeper.hold.started()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keeper.hold.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Holds [ChatService] up for [ChatNotifier]. All on the main thread, as the service's callbacks and the notifier are. */
class ChatServiceKeeper(context: Context) {
    val hold = ForegroundHold(
        startService = {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChatService::class.java))
                true
            } catch (e: Exception) {
                // Starting from the background is refused (e.g. a turn another client began while we
                // were away); the turn still runs, just without keeping the socket alive.
                Log.w("ChatService", "Couldn't start the foreground service", e)
                false
            }
        },
        stopService = { context.stopService(Intent(context, ChatService::class.java)) },
    )
}
