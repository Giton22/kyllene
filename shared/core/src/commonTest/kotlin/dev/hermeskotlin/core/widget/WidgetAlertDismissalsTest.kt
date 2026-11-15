package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D27: dismissals persist per gateway, cap their growth, and go with the gateway on sign-out. */
class WidgetAlertDismissalsTest {

    private val gateway = "https://gw.example.ts.net"

    @Test
    fun dismissalsRoundTripPerGateway() = runTest {
        val dismissals = WidgetAlertDismissals(InMemoryKeyValueStore())
        assertTrue(dismissals.keys(gateway).isEmpty())
        dismissals.dismiss(gateway, "cron:job-1:100.0")
        dismissals.dismiss(gateway, "kanban:board:t1:blocked")
        dismissals.dismiss(gateway, "cron:job-1:100.0") // twice changes nothing
        assertEquals(setOf("cron:job-1:100.0", "kanban:board:t1:blocked"), dismissals.keys(gateway))
        assertTrue(dismissals.keys("https://other.example.ts.net").isEmpty())
    }

    @Test
    fun forgetGatewayRemovesOnlyThatGatewaysDismissals() = runTest {
        val dismissals = WidgetAlertDismissals(InMemoryKeyValueStore())
        dismissals.dismiss(gateway, "cron:job-1:100.0")
        dismissals.dismiss("https://other.example.ts.net", "cron:job-2:200.0")
        dismissals.forgetGateway(gateway)
        assertTrue(dismissals.keys(gateway).isEmpty())
        assertEquals(setOf("cron:job-2:200.0"), dismissals.keys("https://other.example.ts.net"))
    }

    @Test
    fun theListCapsAtMaxKeysDroppingTheOldest() = runTest {
        val dismissals = WidgetAlertDismissals(InMemoryKeyValueStore())
        repeat(WidgetAlertDismissals.MAX_KEYS + 10) { dismissals.dismiss(gateway, "cron:job-$it:0.0") }
        val keys = dismissals.keys(gateway)
        assertEquals(WidgetAlertDismissals.MAX_KEYS, keys.size)
        assertTrue("cron:job-0:0.0" !in keys)
        assertTrue("cron:job-${WidgetAlertDismissals.MAX_KEYS + 9}:0.0" in keys)
    }
}