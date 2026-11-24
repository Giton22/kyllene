package dev.hermeskotlin.android.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.voice.LiveSystemCall
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject

/**
 * Keeps a voice chat going with the screen off or another app in front, like a call: a microphone
 * foreground service, which is the only way Android lets an app keep listening out of sight. Its
 * notification's End ends the chat.
 */
class VoiceCallService : Service() {

    private val notifications: ChatNotifications by inject()
    private val keeper: VoiceCallKeeper by inject()

    private var callNotification: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            keeper.end()
            return START_NOT_STICKY
        }
        val end = PendingIntent.getService(
            this, 0, Intent(this, VoiceCallService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE,
        )
        // Enter the foreground immediately, then update the notification when Telecom registers the call.
        if (!foreground(end, keeper.telecomName.value)) return START_NOT_STICKY
        if (!keeper.started()) {
            stopSelf()
        } else {
            callNotification?.cancel()
            callNotification = CoroutineScope(Dispatchers.Main.immediate).launch {
                keeper.telecomName.collect { name -> if (keeper.holding) foreground(end, name) }
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground(end: PendingIntent, name: String?): Boolean = try {
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (name != null && Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        ServiceCompat.startForeground(this, ChatNotifications.VOICE_ID, notifications.voiceCall(end, name), type)
        true
    } catch (e: Exception) {
        Log.w("VoiceCallService", "Couldn't keep the voice chat up", e)
        keeper.lost()
        stopSelf()
        false
    }

    override fun onDestroy() {
        callNotification?.cancel()
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_END = "dev.hermeskotlin.voice.END"
    }
}

/**
 * [VoiceKeepAlive] on Android: holds [VoiceCallService] up for the voice chat. All on the main thread, as the
 * service's callbacks and the voice chat are.
 */
class VoiceCallKeeper(private val context: Context, settings: SettingsStore) : VoiceKeepAlive {

    internal val telecom = TelecomLiveCalls(context, settings)
    internal val telecomName = telecom.name
    override val incomingLiveCall = telecom.incoming

    override fun supportsAndroidCallControls() = telecom.supported

    override suspend fun registerLiveCall(name: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? =
        if (holding) telecom.register(name, onMute, onEnd) else null

    override fun attachIncomingLiveCall(id: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? =
        if (holding) telecom.attachIncoming(id, onMute, onEnd) else null

    override fun handOffIncomingCallToApp(id: String): Boolean = telecom.handOffIncomingCallToApp(id)

    private var onEnd: (() -> Unit)? = null
    private var onLost: (() -> Unit)? = null
    private val _held = MutableStateFlow(false)
    /** A voice chat holds it. */
    private var wanted = false
        set(value) {
            field = value
            _held.value = value
        }

    /** A voice chat or a call is on, holding the microphone. */
    val holding: Boolean get() = wanted
    /** [holding], to follow. */
    val held: StateFlow<Boolean> = _held.asStateFlow()
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean = try {
        this.onEnd = onEnd
        this.onLost = onLost
        wanted = true
        ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the chat then ends when the app goes away.
        Log.w("VoiceCallService", "Couldn't keep the voice chat up", e)
        forget()
        false
    }

    override fun release() {
        forget()
        // One still starting stops itself once it's in the foreground (started).
        if (foreground) context.stopService(Intent(context, VoiceCallService::class.java))
    }

    /** The service is in the foreground; false when the chat ended meanwhile, so it should stop. */
    internal fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    internal fun stopped() {
        foreground = false
    }

    /** The service couldn't go into the foreground after all. */
    internal fun lost() {
        val lost = onLost
        forget()
        lost?.invoke()
    }

    /** The notification's End. */
    fun end() {
        val end = onEnd
        if (end == null) release() else end()
    }

    private fun forget() {
        wanted = false
        onEnd = null
        onLost = null
    }
}
