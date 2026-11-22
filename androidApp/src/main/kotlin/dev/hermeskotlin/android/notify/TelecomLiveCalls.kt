package dev.hermeskotlin.android.notify

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.canUseAndroidSystemCalling
import dev.hermeskotlin.core.voice.LiveSystemCall
import dev.hermeskotlin.core.voice.SystemCallingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import java.util.UUID

/** Calls belong to this installation and are routed only against a pending Kyllene request. */
internal class TelecomLiveCalls(private val context: Context, private val settings: SettingsStore) {
    private val manager = context.getSystemService(TelecomManager::class.java)
    private val supported = manager != null && (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)
        )
    private val account = PhoneAccountHandle(ComponentName(context, ManagedCallService::class.java), "live-voice")
    private val _name = MutableStateFlow<String?>(null)
    val name = _name.asStateFlow()
    private var pending: ManagedCall? = null
    private val _incoming = MutableStateFlow<String?>(null)
    val incoming = _incoming.asStateFlow()

    fun setupStatus(): SystemCallingStatus = when {
        !supported -> SystemCallingStatus.Unavailable
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED ->
            SystemCallingStatus.NeedsPermissions
        runCatching { manager?.getPhoneAccount(account)?.isEnabled == true }.getOrDefault(false) ->
            SystemCallingStatus.Ready
        else -> SystemCallingStatus.NeedsAccount
    }

    fun prepareAccount(): Boolean = when (setupStatus()) {
        SystemCallingStatus.Ready -> true
        SystemCallingStatus.NeedsAccount -> runCatching {
            requireNotNull(manager).registerPhoneAccount(
                PhoneAccount.builder(account, "Kyllene")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Couldn't register Kyllene's calling account", it) }.isSuccess
        SystemCallingStatus.Unavailable, SystemCallingStatus.NeedsPermissions -> false
    }

    private fun enabled(): Boolean =
        settings.settings.value?.canUseAndroidSystemCalling(setupStatus() == SystemCallingStatus.Ready) == true

    suspend fun register(name: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? {
        if (pending != null || !enabled()) return null
        val call = ManagedCall(name).apply { attach(onMute, onEnd) }
        pending = call
        return try {
            manager.placeCall(call.sessionAddress, Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) })
            if (withTimeoutOrNull(5_000) { call.created.await() } == true) call else null
        } catch (error: SecurityException) {
            Log.w(TAG, "Couldn't place the Kyllene internet call", error)
            null
        } finally {
            if (!call.bound) call.close()
        }
    }

    fun ring(id: String, name: String, onAnswer: () -> Unit, onReject: () -> Unit): Boolean {
        if (pending != null || !enabled()) return false
        val call = ManagedCall(name, id, onAnswer, onReject)
        pending = call
        _incoming.value = id
        return try {
            manager.addNewIncomingCall(account, Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, call.sessionAddress)
            })
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Couldn't report the incoming Kyllene call", error)
            call.close()
            false
        }
    }

    fun attachIncoming(id: String, onMute: (Boolean) -> Unit, onEnd: () -> Unit): LiveSystemCall? =
        answeredIncoming(id)?.apply { attach(onMute, onEnd) }

    fun handOffIncomingCallToApp(id: String): Boolean {
        val call = answeredIncoming(id) ?: return false
        call.close()
        return true
    }

    private fun answeredIncoming(id: String): ManagedCall? =
        pending?.takeIf { it.incomingId == id && it.accepted }

    fun stopRinging(id: String) {
        pending?.takeIf { it.incomingId == id && !it.accepted }?.close()
    }

    fun finishIncoming(id: String) {
        pending?.takeIf { it.incomingId == id }?.close()
    }

    fun create(handle: PhoneAccountHandle?, request: ConnectionRequest, incoming: Boolean): Connection {
        val call = pending?.takeIf {
            handle == account && request.address == it.sessionAddress && incoming == (it.incomingId != null) && !it.bound
        } ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        call.bound = true
        if (incoming) call.setRinging() else call.setDialing()
        _name.value = call.caller
        call.created.complete(true)
        return call
    }

    private inner class ManagedCall(
        val caller: String,
        val incomingId: String? = null,
        private val answer: () -> Unit = {},
        private val rejection: () -> Unit = {},
    ) : Connection(), LiveSystemCall {
        val sessionAddress = Uri.fromParts(PhoneAccount.SCHEME_SIP, "${UUID.randomUUID()}@kyllene.invalid", null)
        val created = CompletableDeferred<Boolean>()
        var bound = false
        var accepted = false
        private var closed = false
        private var muted = false
        private var mute: (Boolean) -> Unit = {}
        private var end: (() -> Unit)? = null

        init {
            setAudioModeIsVoip(true)
            setAddress(sessionAddress, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(caller, TelecomManager.PRESENTATION_ALLOWED)
        }

        fun attach(onMute: (Boolean) -> Unit, onEnd: () -> Unit) {
            mute = onMute
            end = onEnd
            mute(muted)
        }

        override fun onAnswer() {
            if (closed || accepted) return
            accepted = true
            answer()
            // Allow the existing call activity to request unlock and hand the call off to live audio.
            android.os.Handler(context.mainLooper).postDelayed({
                if (end == null && !closed) {
                    close()
                    rejection()
                }
            }, 20_000)
        }

        override fun onAnswer(videoState: Int) = onAnswer()
        override fun onReject() = remoteEnd(DisconnectCause.REJECTED)
        override fun onDisconnect() = remoteEnd(DisconnectCause.LOCAL)
        override fun onAbort() = remoteEnd(DisconnectCause.CANCELED)

        @Suppress("DEPRECATION")
        override fun onCallAudioStateChanged(state: CallAudioState) {
            muted = state.isMuted
            mute(muted)
        }

        override suspend fun activate() {
            if (!closed) setActive()
        }

        override fun close() = finish(DisconnectCause.LOCAL)

        private fun remoteEnd(cause: Int) {
            if (closed) return
            finish(cause)
            end?.invoke() ?: rejection()
        }

        private fun finish(cause: Int) {
            if (closed) return
            closed = true
            if (pending === this) {
                pending = null
                _name.value = null
                _incoming.value = null
            }
            created.complete(false)
            setDisconnected(DisconnectCause(cause))
            destroy()
        }
    }

    private companion object {
        const val TAG = "KylleneTelecom"
    }
}

class ManagedCallService : ConnectionService() {
    private val keeper: VoiceCallKeeper by inject()

    override fun onCreateOutgoingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        keeper.telecom.create(handle, request, incoming = false)

    override fun onCreateIncomingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        keeper.telecom.create(handle, request, incoming = true)
}
