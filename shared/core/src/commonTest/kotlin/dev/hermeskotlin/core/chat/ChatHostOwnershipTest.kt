package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ChatHostOwnershipTest {
    @Test
    fun ordinaryChatSwitchKeepsAccountOwnershipButCloseAndReopenCannotReviveOldSources() = runTest {
        val gateway = FakeGateway(backgroundScope)
        val host = ChatHost(gateway.connection, SessionsApi(gateway.client), backgroundScope)
        val first = host.open(FakeGateway.URL, null, "First", "work")
        val captured = first.ownerVersion
        val second = host.open(FakeGateway.URL, null, "Second", "work")
        assertNotSame(first, second)
        assertEquals(captured, second.ownerVersion)
        host.close()
        val reopened = host.open(FakeGateway.URL, null, "Again", "work")
        assertNotEquals(captured, host.ownerVersion.value)
        assertEquals(host.ownerVersion.value, reopened.ownerVersion)
        // A collector that missed the intermediate null still has the original immutable version.
        assertEquals(captured, first.ownerVersion)
        host.close()
        gateway.client.close()
    }

    @Test
    fun profileInvalidationIsImmediateAndReturningToTheSameChatMakesAFreshSource() = runTest {
        val gateway = FakeGateway(backgroundScope)
        val host = ChatHost(gateway.connection, SessionsApi(gateway.client), backgroundScope)
        val first = host.open(FakeGateway.URL, "s1", "First", "work")
        assertSame(first, host.open(FakeGateway.URL, "s1", "First", "work"))
        val captured = first.ownerVersion
        host.invalidateOwner() // A completed selection leaves work, before its home screen can replace the source.
        host.invalidateOwner() // Another completed selection returns to work before a collector runs.
        assertSame(first, host.session.value)
        assertNotEquals(captured, host.ownerVersion.value)
        val reopened = host.open(FakeGateway.URL, "s1", "First", "work")
        assertNotSame(first, reopened)
        assertEquals(host.ownerVersion.value, reopened.ownerVersion)
        assertEquals(captured, first.ownerVersion)
        host.close()
        gateway.client.close()
    }
}
