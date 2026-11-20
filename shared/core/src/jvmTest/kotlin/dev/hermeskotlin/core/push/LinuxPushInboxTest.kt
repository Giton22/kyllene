package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxPushInboxTest {
    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        var fail = false
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, value: String) {
            if (fail) throw IOException("Synthetic commit failure")
            values[key] = value
        }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private class Fixture {
        val store = Store()
        val keys = LinuxPushKeys(store)
        val encryption = PushCrypto.generateKeyPair()
        val signing = PushCrypto.generateKeyPair()
        val pin = PushGateway("00000000-0000-0000-0000-000000000001",
            PushCrypto.encodePublic(encryption.public), PushCrypto.encodePublic(signing.public))
        val registration = keys.registration("Synthetic desktop")
        val now = System.currentTimeMillis() / 1000
        val message = PushMessage("one", now, PushMessage.BOT_MESSAGE, bot = "work", session = "session-1",
            text = "a".repeat(700) + "\r\n```kotlin\nx\n```")
        init { keys.pin(URL, pin) }
        fun event(message: PushMessage = this.message, topic: String = registration.pushTopic,
            signer: java.security.PrivateKey = signing.private): NtfyEvent {
            val body = PushCrypto.encodeEnvelope(PushCrypto.seal(
                HermesJson.encodeToString(PushMessage.serializer(), message).encodeToByteArray(), topic,
                PushDirection.GatewayToPhone, PushCrypto.decodePublic(registration.encPub), signer))
            return NtfyEvent("transport-id", now, "message", topic, body)
        }
        fun inbox(keys: PushKeys = this.keys) = assertNotNull(linuxPushInbox(keys))
    }

    @Test fun aSubscriptionRequiresAPinnedIdentityAndNeverCreatesOne() {
        val store = Store()
        val keys = LinuxPushKeys(store)
        assertNull(assertNotNull(linuxPushInbox(keys)).subscription(URL))
        assertTrue(store.values.isEmpty())
    }

    @Test fun authenticMessagesCommitReplayStateAndSurviveReopenWithoutDuplicates() {
        val f = Fixture()
        val inbox = f.inbox()
        val subscription = assertNotNull(inbox.subscription(URL))
        assertNull(subscription.sinceSeconds)
        assertEquals(f.message, inbox.receive(subscription, f.event().copy(time = Long.MAX_VALUE)))
        assertTrue(inbox.current(subscription))
        val reopened = f.inbox(LinuxPushKeys(f.store))
        val resumed = assertNotNull(reopened.subscription(URL))
        assertEquals(f.message.ts, resumed.sinceSeconds)
        assertNull(reopened.receive(resumed, f.event()))
    }

    @Test fun invalidAndStaleEventsCannotAdvanceTheCursorOrModifyStorage() {
        val f = Fixture()
        val inbox = f.inbox()
        val subscription = assertNotNull(inbox.subscription(URL))
        val before = f.store.values.toMap()
        val invalid = listOf(
            f.event().copy(event = "open"), f.event().copy(topic = PushCrypto.newTopic()),
            f.event().copy(message = "private-invalid-body"), f.event().copy(message = "x".repeat(4097)),
            f.event(signer = PushCrypto.generateKeyPair().private),
            f.event(f.message.copy(id = "old", ts = f.now - PushMessage.MAX_AGE_G2P_SECONDS - 3600)),
            f.event(f.message.copy(id = "negative", ts = Long.MIN_VALUE)),
            f.event(f.message.copy(id = "future", ts = f.now + PushMessage.MAX_SKEW_SECONDS + 3600)),
        )
        invalid.forEach { assertNull(inbox.receive(subscription, it)) }
        assertNull(inbox.subscription("https://other.example"))
        assertEquals(before, f.store.values)
        assertNull(inbox.subscription(URL)?.sinceSeconds)
    }

    @Test fun retiredOrReplacedSubscriptionsCannotReceiveOrClaimCurrentOwnership() {
        val f = Fixture()
        val inbox = f.inbox()
        val previous = assertNotNull(inbox.subscription(URL))
        f.keys.retire(URL)
        assertFalse(inbox.current(previous))
        assertNull(inbox.receive(previous, f.event()))
        val next = f.keys.registration("New identity")
        f.keys.pin(URL, f.pin)
        assertFalse(inbox.current(previous))
        val current = assertNotNull(inbox.subscription(URL))
        assertEquals(next.deviceId, current.deviceId)
        assertNull(inbox.receive(current, f.event()))
        assertNull(inbox.receive(previous, f.event()))
    }

    @Test fun replayCommitFailureCannotPublishSuccessAndCorruptionNeverResetsTheGuard() {
        val f = Fixture()
        val inbox = f.inbox()
        val subscription = assertNotNull(inbox.subscription(URL))
        val before = f.store.values.toMap()
        f.store.fail = true
        assertFailsWith<IOException> { inbox.receive(subscription, f.event()) }
        assertEquals(before, f.store.values)
        f.store.fail = false
        assertEquals(f.message, inbox.receive(subscription, f.event()))
        val cache = (f.store.values.keys - before.keys).single()
        val original = f.store.values.getValue(cache)
        for (corrupt in listOf("{}", "private-corrupt-replay", original.replace("\"version\":1", "\"version\":2"))) {
            f.store.values[cache] = corrupt
            val failure = assertFailsWith<IllegalStateException> { inbox.receive(subscription, f.event()) }
            assertFalse(failure.message.orEmpty().contains("private-corrupt-replay"))
            assertEquals(corrupt, f.store.values[cache])
        }
        f.store.values[cache] = original
        assertNull(inbox.receive(subscription, f.event()))
    }

    @Test fun twoAdaptersCannotAcceptOneEnvelopeTwice() {
        val f = Fixture()
        val first = f.inbox()
        val second = f.inbox(LinuxPushKeys(f.store))
        val subscription = assertNotNull(first.subscription(URL))
        val event = f.event()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(first, second).map { inbox -> executor.submit<PushMessage?> { start.await(); inbox.receive(subscription, event) } }
            start.countDown()
            assertEquals(1, jobs.count { it.get(5, TimeUnit.SECONDS) != null })
        } finally { executor.shutdownNow() }
    }

    private companion object { const val URL = "https://gateway.example" }
}
