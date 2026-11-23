package dev.hermeskotlin.desktop.web

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JcefLifecycleTest {

    @Test
    fun shutdownBeforeStartPreventsStartup() {
        val starts = AtomicInteger()
        val disposes = AtomicInteger()
        val lifecycle = JcefLifecycle<String>(disposer = { disposes.incrementAndGet() })

        lifecycle.shutdown()
        assertNull(lifecycle.getOrStart { starts.incrementAndGet(); "app" })
        assertEquals(0, starts.get())
        assertEquals(0, disposes.get())
    }

    @Test
    fun shutdownWaitsForInProgressStartupAndDisposesOnce() {
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val disposed = AtomicBoolean(false)
        val disposeCount = AtomicInteger()
        val lifecycle = JcefLifecycle<String>(
            disposer = {
                disposeCount.incrementAndGet()
                disposed.set(true)
            },
            shutdownWaitMs = 5_000L,
        )

        val future = lifecycle.getOrStart {
            started.countDown()
            assertTrue(allowFinish.await(5, TimeUnit.SECONDS))
            "chromium"
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val releaser = Thread {
            Thread.sleep(50)
            allowFinish.countDown()
        }.apply { start() }

        lifecycle.shutdown()
        releaser.join(5_000)

        assertTrue(disposed.get())
        assertEquals(1, disposeCount.get())
        assertNull(future?.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun shutdownDisposesAlreadyStartedInstanceExactlyOnce() {
        val disposeCount = AtomicInteger()
        val lifecycle = JcefLifecycle<String>(disposer = { disposeCount.incrementAndGet() })

        assertEquals("chromium", lifecycle.getOrStart { "chromium" }?.get(5, TimeUnit.SECONDS))
        lifecycle.shutdown()
        lifecycle.shutdown()

        assertEquals(1, disposeCount.get())
    }

    @Test
    fun slowStartupCompletingAfterShutdownTimeoutStillDisposes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val disposeCount = AtomicInteger()
        val lifecycle = JcefLifecycle<String>(
            disposer = {
                disposeCount.incrementAndGet()
                disposed.countDown()
            },
            shutdownWaitMs = 50L,
        )

        lifecycle.getOrStart {
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "chromium"
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        lifecycle.shutdown()
        assertEquals(0, disposeCount.get())

        release.countDown()
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
        assertEquals(1, disposeCount.get())
    }
}
