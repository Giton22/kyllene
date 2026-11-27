package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.desktop.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class BackgroundStartupTest {
    @Test fun `startup is off until an explicit request is granted`() = runTest {
        val store = InMemoryKeyValueStore()
        var calls = 0
        val startup = BackgroundStartup.load(store) { calls++; StartupGrant(true, true) }
        assertEquals(StartupChoice.Off, startup.state.value.choice)
        assertEquals(0, calls)
        startup.set(true)
        assertEquals(StartupChoice.Background, startup.state.value.choice)
        assertEquals(StartupChoice.Background, BackgroundStartup.load(store) { null }.state.value.choice)
        startup.set(false)
        assertEquals(StartupChoice.Background, startup.state.value.choice)
        assertEquals(StartupMessage.NotApplied, startup.state.value.message)
    }

    @Test fun `partial consent starts visibly and disable follows the portal result`() = runTest {
        val store = InMemoryKeyValueStore()
        var result = StartupGrant(false, true)
        val startup = BackgroundStartup.load(store) { result }
        startup.set(true)
        assertEquals(StartupChoice.Window, startup.state.value.choice)
        assertFalse(startup.state.value.choice.hidden)
        assertEquals(StartupMessage.ShowsWindow, startup.state.value.message)
        result = StartupGrant(true, false)
        startup.set(false)
        assertEquals(StartupChoice.Off, startup.state.value.choice)
        assertNull(startup.state.value.message)
    }

    @Test fun `decline and transport failure preserve the last confirmed choice`() = runTest {
        val store = InMemoryKeyValueStore()
        var fail = false
        val startup = BackgroundStartup.load(store) {
            if (fail) error("private transport detail") else null
        }
        startup.set(true)
        assertEquals(StartupChoice.Off, startup.state.value.choice)
        assertEquals(StartupMessage.NotApproved, startup.state.value.message)
        fail = true
        startup.set(true)
        assertEquals(StartupChoice.Off, startup.state.value.choice)
        assertEquals(StartupMessage.Incomplete, startup.state.value.message)
        assertFalse(startup.state.value.busy)
    }

    @Test fun `pending requests suppress repeat taps and cancellation releases the control`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<StartupGrant?>()
        var calls = 0
        val startup = BackgroundStartup.load(InMemoryKeyValueStore()) {
            calls++; entered.complete(Unit); answer.await()
        }
        val pending = async { startup.set(true) }
        entered.await()
        assertTrue(startup.state.value.busy)
        startup.set(true)
        assertEquals(1, calls)
        pending.cancelAndJoin()
        assertFalse(startup.state.value.busy)
        assertEquals(StartupChoice.Off, startup.state.value.choice)
    }

    @Test fun `failed app preference write never repeats or rolls back the desktop request`() = runTest {
        var calls = 0
        val store = object : KeyValueStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) { error("write failed") }
            override suspend fun remove(key: String) { error("must not remove data") }
        }
        val startup = BackgroundStartup.load(store) { calls++; StartupGrant(true, true) }
        startup.set(true)
        assertEquals(1, calls)
        assertEquals(StartupChoice.Background, startup.state.value.choice)
        assertEquals(StartupMessage.NotSaved, startup.state.value.message)
    }

    @Test fun `startup guidance follows the system language`() = withLocale("es") {
        runBlocking {
            assertEquals("Iniciar al iniciar sesión", getString(Res.string.startup_at_login))
            val startup = BackgroundStartup.load(InMemoryKeyValueStore()) { StartupGrant(false, true) }
            startup.set(true)
            assertEquals("El inicio automático mostrará una ventana.", getString(startup.state.value.message!!.text))
        }
    }

    @Test fun `background second launch never sends a show request`() {
        val dir = java.nio.file.Files.createTempDirectory("startup-instance-").toFile()
        try {
            java.io.RandomAccessFile(java.io.File(dir, "instance.lock"), "rw").use { file ->
                file.channel.lock().use {
                    java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                        java.io.File(dir, "instance.port").writeText(server.localPort.toString())
                        assertFalse(backgroundLaunch(arrayOf("--background"), StartupChoice.Background, false))
                        assertNull(SingleInstance.claim(dir, foreground = !backgroundInvocation(arrayOf("--background"), true)))
                        server.soTimeout = 100
                        kotlin.test.assertFailsWith<java.net.SocketTimeoutException> { server.accept() }
                        assertNull(SingleInstance.claim(dir))
                        server.accept().use { assertEquals("show", it.getInputStream().bufferedReader().readLine()) }
                    }
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a background launch leaves a lock holder that doesn't answer alone`() {
        val dir = java.nio.file.Files.createTempDirectory("startup-instance-").toFile()
        try {
            java.io.RandomAccessFile(java.io.File(dir, "instance.lock"), "rw").use { file ->
                file.channel.lock().use {
                    // A port nothing listens on: the holder is on its way out, or hung. A second Kyllene beside it
                    // would hold no claim, so no later launch could reach it.
                    val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
                    java.io.File(dir, "instance.port").writeText(port.toString())
                    assertNull(SingleInstance.claim(dir, foreground = false))
                    java.io.File(dir, "instance.port").delete()
                    assertNull(SingleInstance.claim(dir, foreground = false))
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `no answer and a portal failure each say what happened and keep the choice`() = runTest {
        var failure: Exception = StartupNoAnswer()
        val startup = BackgroundStartup.load(InMemoryKeyValueStore()) { throw failure }
        startup.set(true)
        assertEquals(StartupMessage.NoAnswer, startup.state.value.message)
        failure = StartupNotApplied()
        startup.set(true)
        assertEquals(StartupMessage.NotApplied, startup.state.value.message)
        assertEquals(StartupChoice.Off, startup.state.value.choice)
        assertFalse(startup.state.value.busy)
    }

    @Test fun `quitting while the answer is being saved still saves it`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backing = InMemoryKeyValueStore()
        val store = object : KeyValueStore by backing {
            override suspend fun put(key: String, value: String) {
                writing.complete(Unit); release.await(); backing.put(key, value)
            }
        }
        val startup = BackgroundStartup.load(store) { StartupGrant(true, true) }
        val pending = async { startup.set(true) }
        writing.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertEquals(StartupChoice.Background, BackgroundStartup.load(backing) { null }.state.value.choice)
    }

    @Test fun `the portal is asked again while it starts`() = runTest {
        var asks = 0
        assertTrue(awaitPortal({ ++asks == 3 }))
        assertEquals(3, asks)
        assertEquals(20_000L, testScheduler.currentTime)
        asks = 0
        assertFalse(awaitPortal({ asks++; false }))
        assertEquals(PORTAL_RETRIES.size, asks)
    }

    @Test fun `ordinary launches and links never become quiet background handoffs`() {
        assertFalse(backgroundInvocation(arrayOf("--background"), false))
        assertFalse(backgroundInvocation(emptyArray(), true))
        assertFalse(backgroundInvocation(arrayOf("--background", "herald://new-chat"), true))
    }

    @Test fun `hidden launch needs consent tray and an unambiguous background argument`() {
        assertTrue(backgroundLaunch(arrayOf("--background"), StartupChoice.Background, true))
        for (choice in listOf(StartupChoice.Off, StartupChoice.Window)) {
            assertFalse(backgroundLaunch(arrayOf("--background"), choice, true))
        }
        assertFalse(backgroundLaunch(arrayOf("--background"), StartupChoice.Background, false))
        assertFalse(backgroundLaunch(emptyArray(), StartupChoice.Background, true))
        assertFalse(backgroundLaunch(arrayOf("--background", "herald://new-chat"), StartupChoice.Background, true))
    }
}
