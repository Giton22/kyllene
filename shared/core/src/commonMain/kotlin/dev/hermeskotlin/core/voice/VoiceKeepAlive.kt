package dev.hermeskotlin.core.voice

/**
 * Keeps a voice chat going while Herald is out of sight (the screen off, another app in front), the way a
 * phone call does. On Android, a microphone foreground service with an ongoing notification.
 */
interface VoiceKeepAlive {
    /**
     * Holds the voice chat up until [release]. [onEnd] runs when the person ends it from outside the app (the
     * notification's End). False when the platform refused at once, and [onLost] runs when it refuses later;
     * either way the chat should end when the app goes away.
     */
    fun hold(onEnd: () -> Unit, onLost: () -> Unit = {}): Boolean

    suspend fun registerLiveCall(name: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? = null

    fun attachIncomingLiveCall(id: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? = null

    /** Ends the matching answered system call so it can continue in-app. False if the call is gone. */
    fun handOffIncomingCallToApp(id: String): Boolean = false

    /** Whether this device can register calls with Android Telecom. */
    fun supportsAndroidCallControls(): Boolean = false

    fun release()
}

interface LiveSystemCall {
    suspend fun activate()
    fun close()
}
