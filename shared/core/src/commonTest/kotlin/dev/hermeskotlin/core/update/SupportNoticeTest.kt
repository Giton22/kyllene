package dev.hermeskotlin.core.update

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class SupportNoticeTest {

    private class TestClock(var at: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000)) : Clock {
        override fun now() = at
    }

    @Test
    fun someoneWhoAlreadyUsedTheAppIsAskedAtOnceUntilTheyCloseIt() = runTest {
        val store = InMemoryKeyValueStore()
        GatewayRepository(store).save(SavedGateway("https://hermes.example"))

        assertTrue(SupportNotice(store, GatewayRepository(store)).pending())
        // A relaunch before closing it still shows it.
        assertTrue(SupportNotice(store, GatewayRepository(store)).pending())

        SupportNotice(store, GatewayRepository(store)).dismiss()
        assertFalse(SupportNotice(store, GatewayRepository(store)).pending())
    }

    @Test
    fun aFreshInstallIsAskedAfterThreeWeeks() = runTest {
        val store = InMemoryKeyValueStore()
        val gateways = GatewayRepository(store)
        val clock = TestClock()

        assertFalse(SupportNotice(store, gateways, clock).pending())
        // Signing in right after the first launch doesn't count as having used it before.
        gateways.save(SavedGateway("https://hermes.example"))
        clock.at += 21.days - 1.hours
        assertFalse(SupportNotice(store, gateways, clock).pending())

        clock.at += 1.hours
        assertTrue(SupportNotice(store, gateways, clock).pending())

        SupportNotice(store, gateways, clock).dismiss()
        clock.at += 100.days
        assertFalse(SupportNotice(store, gateways, clock).pending())
    }
}
