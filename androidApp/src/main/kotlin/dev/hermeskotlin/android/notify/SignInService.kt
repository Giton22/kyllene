package dev.hermeskotlin.android.notify

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.auth.SignInKeepAlive
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps Kyllene answering while the browser is in front during a sign-in in the browser. The browser comes back
 * through a listener on 127.0.0.1 inside the app; without this, Android freezes the app in the background and the
 * browser's redirect to it never gets an answer (#555). Up only from just before the browser opens until the sign-in
 * ends, and never longer than the gateway waits for one.
 *
 * A special-use service, like [ChatService]: none of the standard types fits, and a short service is cut off after
 * three minutes, less than a sign-in with a second factor can take.
 */
class SignInService : Service() {

    private val notifications: ChatNotifications by inject()
    private val keeper: SignInKeeper by inject()
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        Log.w("SignInService", "A sign-in held the app up longer than the gateway waits for it")
        keeper.release()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Into the foreground first, always: a service started this way and stopped before it is a crash.
        try {
            ServiceCompat.startForeground(
                this,
                ChatNotifications.SIGN_IN_ID,
                notifications.signIn(),
                // The type is Android 14+; before it, the manifest's is enough.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Android refuses a foreground service started from the background.
            Log.w("SignInService", "Couldn't keep the app up for the sign-in", e)
            keeper.lost()
            stopSelf()
            return START_NOT_STICKY
        }
        // The sign-in ended while it was starting: it was left to stop here.
        if (!keeper.started()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Should the sign-in never let go, the service still doesn't outlive the gateway's 10 minutes by much.
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, MAX_HOLD_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        keeper.stopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val MAX_HOLD_MS = 11 * 60_000L
    }
}

/**
 * [SignInKeepAlive] on Android: holds [SignInService] up while a sign-in waits for the browser. With battery usage
 * Restricted, Android drops the browser's connection to the app however it is held, so the service isn't started then
 * and [restricted] tells the sign-in screen to say so; it is checked again whenever Kyllene comes back in sight, and a
 * sign-in still waiting is held up once the restriction is lifted. All on the main thread, as the service's callbacks
 * and the sign-in are.
 */
class SignInKeeper(private val context: Context, visibility: AppVisibility) : SignInKeepAlive {

    private val _restricted = MutableStateFlow(backgroundRestricted())
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    /** A sign-in waits for the browser. */
    private var wanted = false
    /** The service was asked to start and hasn't stopped. */
    private var running = false
    /** The service is in the foreground, so it may be stopped. */
    private var foreground = false
    /** Android refused the service for this sign-in: it isn't asked again until the app is back in sight. */
    private var refused = false

    init {
        MainScope().launch {
            visibility.visible.collect { visible ->
                if (!visible) return@collect
                _restricted.value = backgroundRestricted()
                // Back from the battery settings with the restriction lifted: hold up the sign-in still waiting.
                if (wanted && !running && !_restricted.value) {
                    refused = false
                    start()
                }
            }
        }
    }

    override fun hold(): Boolean {
        wanted = true
        refused = false
        _restricted.value = backgroundRestricted()
        return !_restricted.value && (running || start())
    }

    override fun release() {
        wanted = false
        // One still starting stops itself once it's in the foreground (started).
        if (foreground) context.stopService(Intent(context, SignInService::class.java))
    }

    private fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, SignInService::class.java))
        running = true
        true
    } catch (e: Exception) {
        // Refused, e.g. from the background; the sign-in still goes on, and works if the app isn't frozen meanwhile.
        Log.w("SignInService", "Couldn't keep the app up for the sign-in", e)
        false
    }

    /** The service is in the foreground; false when the sign-in ended meanwhile, so it should stop. */
    internal fun started(): Boolean {
        foreground = wanted
        return wanted
    }

    /** The service couldn't go into the foreground after all. */
    internal fun lost() {
        refused = true
    }

    internal fun stopped() {
        foreground = false
        running = false
        // Stopped for a sign-in that ended, just as the next one started (held while this one was still up).
        if (wanted && !refused && !_restricted.value) start()
    }

    private fun backgroundRestricted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
}
