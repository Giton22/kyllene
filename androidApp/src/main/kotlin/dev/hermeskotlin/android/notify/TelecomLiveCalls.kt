package dev.hermeskotlin.android.notify

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.canUseAndroidSystemCalling
import dev.hermeskotlin.core.voice.LiveSystemCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Reports live voice to Telecom while Kyllene continues to own the phone UI. */
internal class TelecomLiveCalls(private val context: Context, private val settings: SettingsStore) {
    private val calls = CallsManager(context)
    private val manager = context.getSystemService(TelecomManager::class.java)
    val supported = manager != null && (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
        )
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var accountReady = false
    private var pending: Registration? = null
    private val _name = MutableStateFlow<String?>(null)
    val name = _name.asStateFlow()
    private val _incoming = MutableStateFlow<String?>(null)
    val incoming = _incoming.asStateFlow()

    private fun prepareAccount(): Boolean {
        if (accountReady) return true
        return runCatching {
            calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            accountReady = true
            // Retire the previous system-managed account for this installation after registration succeeds.
            val legacy = PhoneAccountHandle(ComponentName(context, LEGACY_SERVICE), "live-voice")
            runCatching { manager?.unregisterPhoneAccount(legacy) }
                .onFailure { Log.w(TAG, "Couldn't remove the previous Kyllene calling account", it) }
        }.onFailure { Log.w(TAG, "Couldn't register Kyllene with Telecom", it) }.isSuccess
    }

    private fun enabled(): Boolean =
        settings.settings.value?.canUseAndroidSystemCalling(supported) == true && prepareAccount()

    suspend fun register(name: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? {
        if (pending != null || !enabled()) return null
        val call = Registration(name).apply { attach(onMute, onEnd) }
        start(call)
        var registered = false
        try {
            registered = withTimeoutOrNull(5_000) { call.created.await() } == true
            return call.takeIf { registered }
        } finally {
            if (!registered) call.close()
        }
    }

    fun ring(id: String, name: String, onAnswer: () -> Unit, onReject: () -> Unit) {
        if (pending != null || !enabled()) return
        start(Registration(name, id, onAnswer, onReject))
    }

    private fun start(call: Registration) {
        pending = call
        call.job = scope.launch {
            try {
                calls.addCall(
                    call.attributes,
                    onAnswer = { call.answerFromTelecom() },
                    onDisconnect = { call.endedByTelecom() },
                    onSetActive = {},
                    onSetInactive = { error("Hold is unavailable") },
                ) {
                    call.control = this
                    if (pending !== call) return@addCall
                    if (call.incomingId != null) _incoming.value = call.incomingId
                    _name.value = call.attributes.displayName.toString()
                    call.created.complete(true)
                    launch { isMuted.collect(call::updateMute) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't register the Kyllene voice session with Telecom", error)
            } finally {
                call.created.complete(false)
                if (pending === call) call.registrationEnded()
            }
        }
    }

    /** A tap on Kyllene's incoming notification uses the same pending call as Android Auto. */
    fun answerFromPhone(id: String): Boolean {
        val call = pending?.takeIf { it.incomingId == id && it.control != null } ?: return false
        call.markAccepted()
        return true
    }

    fun attachIncoming(id: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? =
        answeredIncoming(id)?.apply { attach(onMute, onEnd) }

    fun handOffIncomingCallToApp(id: String): Boolean {
        val call = answeredIncoming(id) ?: return false
        call.close()
        return true
    }

    private fun answeredIncoming(id: String): Registration? =
        pending?.takeIf { it.incomingId == id && it.accepted }

    fun stopRinging(id: String) {
        pending?.takeIf { it.incomingId == id && !it.accepted }?.close()
    }

    fun finishIncoming(id: String) {
        pending?.takeIf { it.incomingId == id }?.close()
    }

    private inner class Registration(
        name: String,
        val incomingId: String? = null,
        private val answer: () -> Unit = {},
        private val rejection: () -> Unit = {},
    ) : LiveSystemCall {
        val attributes = kylleneCallAttributes(name, incomingId != null)
        val created = CompletableDeferred<Boolean>()
        var job: Job? = null
        var control: CallControlScope? = null
        var accepted = false
            private set
        private var answeredByTelecom = false
        private var closed = false
        private var activated = false
        private var muted = false
        private var mute: (Boolean) -> Unit = {}
        private var end: (() -> Unit)? = null
        private var pickupTimeout: Job? = null

        fun attach(onMute: (Boolean) -> Unit, onEnd: () -> Unit) {
            pickupTimeout?.cancel()
            mute = onMute
            end = onEnd
            mute(muted)
        }

        fun updateMute(value: Boolean) {
            muted = value
            if (!closed) mute(value)
        }

        fun markAccepted() {
            if (closed || accepted) return
            accepted = true
            pickupTimeout = scope.launch {
                delay(20_000)
                close()
                rejection()
            }
        }

        fun answerFromTelecom() {
            check(incomingId != null && !closed)
            answeredByTelecom = true
            if (accepted) return
            markAccepted()
            answer()
        }

        override suspend fun activate() {
            val call = checkNotNull(control)
            check(!closed) { "The system call has ended" }
            if (activated || answeredByTelecom) return
            val result = if (incomingId != null) call.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            else call.setActive()
            check(result !is CallControlResult.Error) { "Android rejected call activation" }
            activated = true
        }

        override fun close() {
            if (closed) return
            clear()
            val call = control
            if (call == null) job?.cancel()
            else scope.launch {
                try {
                    withTimeoutOrNull(5_500) { call.disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                } finally {
                    job?.cancel()
                }
            }
        }

        fun endedByTelecom() {
            if (closed) return
            clear()
            end?.invoke() ?: rejection()
        }

        fun registrationEnded() {
            val wasRegistered = control != null
            clear()
            // If Telecom refused registration, Kyllene's ordinary voice or ringing UI can carry on.
            if (wasRegistered) end?.invoke() ?: rejection()
        }

        private fun clear() {
            closed = true
            pickupTimeout?.cancel()
            if (pending === this) {
                pending = null
                _name.value = null
                _incoming.value = null
            }
        }
    }

    private companion object {
        const val TAG = "KylleneTelecom"
        const val LEGACY_SERVICE = "dev.hermeskotlin.android.notify.ManagedCallService"
    }
}

internal fun kylleneCallAttributes(name: String, incoming: Boolean): CallAttributesCompat {
    val label = name.ifBlank { "Kyllene" }
    return CallAttributesCompat(
        displayName = label,
        // Some surfaces show the address while dialing. Telecom assigns the actual call a separate ID.
        address = "sip:${Uri.encode(label)}@kyllene.invalid".toUri(),
        direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
        callCapabilities = 0,
        isLogExcluded = true,
    )
}
