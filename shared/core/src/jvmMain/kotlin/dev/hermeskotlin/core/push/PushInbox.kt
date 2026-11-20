package dev.hermeskotlin.core.push

/** A public subscription snapshot; private keys remain inside their encrypted store. */
data class PushSubscription(val gatewayUrl: String, val deviceId: String, val server: String,
    val topic: String, val gateway: PushGateway, val sinceSeconds: Long?)

/** Storage and crypto run on the caller's worker dispatcher; receive commits replay state before returning a message. */
interface PushInbox {
    fun subscription(gatewayUrl: String): PushSubscription?
    fun current(subscription: PushSubscription): Boolean
    fun receive(subscription: PushSubscription, event: NtfyEvent): PushMessage?
}

/** Only the secure Linux identity adapter can decrypt and commit replay state. */
fun linuxPushInbox(keys: PushKeys): PushInbox? = keys as? LinuxPushKeys

/** Corrupt or unsupported replay data is preserved, never reset to accept old messages again. */
class PushInboxException : IllegalStateException(
    "Kyllene couldn't open its push replay state. Existing data has been preserved; restore a known-good backup before retrying.",
)
