package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.push.NtfyEvent
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushInbox
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.push.PushSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPushTest {
    private val subscription = PushSubscription("https://gateway.example", "device", "https://ntfy.example",
        "hp-" + "a".repeat(32), PushGateway("gateway", "enc", "sig"), null)
    private val watch = PushWatch(subscription.gatewayUrl, 1, 1)
    private val event = NtfyEvent(event = "message", topic = subscription.topic, message = "ciphertext")
    private val message = PushMessage("message", 10, PushMessage.PING)
    private inner class Inbox : PushInbox {
        var snapshot: PushSubscription? = subscription
        var valid = true
        var open: () -> PushMessage? = { message }
        override fun subscription(gatewayUrl: String) = snapshot?.takeIf { it.gatewayUrl == gatewayUrl }
        override fun current(subscription: PushSubscription) = valid
        override fun receive(subscription: PushSubscription, event: NtfyEvent) = open()
    }

    @Test fun disabledOrUnpinnedStateDoesNotSubscribeAndStartIsIdempotent() = runTest {
        val watches = MutableStateFlow<PushWatch?>(null)
        val inbox = Inbox().apply { snapshot = null }
        var started = 0
        var closed = 0
        val listener = DesktopPush(watches, inbox, { flow {
            started++
            try { awaitCancellation() } finally { closed++ }
        } }, { _, _, _ -> }, work = StandardTestDispatcher(testScheduler))
        val job = listener.start(backgroundScope)
        listener.start(backgroundScope)
        runCurrent()
        assertEquals(0, started)
        watches.value = watch
        runCurrent()
        assertEquals(0, started)
        inbox.snapshot = subscription
        watches.value = watch.copy(registration = 2)
        runCurrent()
        assertEquals(1, started)
        watches.value = null
        runCurrent()
        assertEquals(1, closed)
        job.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, started)
    }

    @Test fun aClosedStreamBacksOffAndReconnectsWithTheLatestCursor() = runTest {
        val watches = MutableStateFlow<PushWatch?>(watch)
        val inbox = Inbox()
        val requests = mutableListOf<PushSubscription>()
        val statuses = mutableListOf<String?>()
        val interrupted = CompletableDeferred<Unit>()
        val listener = DesktopPush(watches, inbox, { request -> flow {
            requests += request
            if (requests.size == 1) throw IOException("private https://broker.example/secret-topic")
            awaitCancellation()
        } }, { _, _, _ -> }, {
            statuses += it
            if (it != null) interrupted.complete(Unit)
        }, StandardTestDispatcher(testScheduler))
        val job = listener.start(backgroundScope)
        try {
            runCurrent()
            assertEquals(1, requests.size)
            interrupted.await()
            assertTrue(statuses.any { it != null })
            assertTrue(statuses.filterNotNull().none { "private" in it || "secret-topic" in it })
            inbox.snapshot = subscription.copy(sinceSeconds = 777)
            advanceTimeBy(1999)
            runCurrent()
            assertEquals(1, requests.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf<Long?>(null, 777), requests.map { it.sinceSeconds })
        } finally { job.cancelAndJoin() }
    }

    @Test fun onlyCurrentAcceptedMessagesReachTheConsumer() = runTest {
        val watches = MutableStateFlow<PushWatch?>(watch)
        val inbox = Inbox()
        val events = Channel<NtfyEvent>(Channel.UNLIMITED)
        val delivered = mutableListOf<PushMessage>()
        val listener = DesktopPush(watches, inbox, { events.receiveAsFlow() }, { _, _, item -> delivered += item },
            work = StandardTestDispatcher(testScheduler))
        val job = listener.start(backgroundScope)
        try {
            runCurrent()
            inbox.open = { null }
            events.send(event)
            runCurrent()
            assertTrue(delivered.isEmpty())
            inbox.open = { message }
            inbox.valid = false
            events.send(event)
            runCurrent()
            assertTrue(delivered.isEmpty())
            inbox.valid = true
            events.send(event)
            runCurrent()
            assertEquals(listOf(message), delivered)
        } finally { job.cancelAndJoin(); events.close() }
    }

    @Test fun anOwnerChangeDropsAResultFromNonInterruptibleWorkAndClosesTheOldStream() = runTest {
        val watches = MutableStateFlow<PushWatch?>(watch)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val streamClosed = CompletableDeferred<Unit>()
        val inbox = Inbox().apply { open = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            message
        } }
        var delivered = 0
        var closed = 0
        val listener = DesktopPush(watches, inbox, { flow {
            try { emit(event); awaitCancellation() } finally { closed++; streamClosed.complete(Unit) }
        } }, { _, _, _ -> delivered++ })
        val job = listener.start(backgroundScope)
        try {
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            watches.value = watch.copy(ownerVersion = 2)
            inbox.snapshot = null
            runCurrent()
            release.countDown()
            streamClosed.await()
            runCurrent()
            assertEquals(0, delivered)
            assertEquals(1, closed)
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
