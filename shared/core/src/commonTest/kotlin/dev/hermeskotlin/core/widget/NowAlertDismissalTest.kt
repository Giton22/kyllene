package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.kanban.KanbanTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D27 (`docs/WIDGETS-SPEC.md`): a dismissed alert stays hidden until its failure fingerprint changes
 * — a later failed run or a new task status raises a fresh key — and the cold merge honours the same
 * dismissals for the rows it carries.
 */
class NowAlertDismissalTest {

    private val now = 1_800_000_000L
    private val gateway = "https://gw.example.ts.net"

    private fun failedJob(id: String = "job-1", lastRunAt: String? = "2026-11-15T03:48:00Z") = CronJob(
        id = id,
        name = "Weekly Discovery Radio",
        lastStatus = "error",
        lastError = "boom",
        lastRunAt = lastRunAt,
    )

    private fun inputs(
        cronJobs: List<CronJob> = emptyList(),
        kanbanRows: List<KanbanTask> = emptyList(),
        dismissedAlerts: Set<String> = emptySet(),
    ) = NowInputs(
        sessions = emptyList(),
        seen = null,
        waiting = emptyMap(),
        runningChats = emptyMap(),
        bots = emptyList(),
        troubles = emptyMap(),
        failingRoutines = emptyMap(),
        cronJobs = cronJobs,
        kanbanRows = kanbanRows,
        kanbanBoardSlug = "feedbucket",
        nowSeconds = now,
        dismissedAlerts = dismissedAlerts,
    )

    @Test
    fun aCronProblemCarriesItsFailureFingerprintAsTheAlertKey() {
        val job = failedJob()
        val item = NowReducer.derive(gateway, null, inputs(cronJobs = listOf(job))).needsYou.single()
        assertEquals(NowNeedsYouKind.CronProblem, item.kind)
        assertEquals(NowAlertKeys.cron(job), item.alertKey)
        assertNotNull(item.alertKey)
    }

    @Test
    fun aDismissedCronProblemIsHidden() {
        val job = failedJob()
        val key = NowAlertKeys.cron(job)
        val snapshot = NowReducer.derive(gateway, null, inputs(cronJobs = listOf(job), dismissedAlerts = setOf(key)))
        assertTrue(snapshot.needsYou.isEmpty())
        // With nothing left needing the user, the widget goes quiet rather than showing an empty list.
        assertNotNull(snapshot.quiet)
    }

    @Test
    fun aLaterFailedRunRaisesAFreshAlert() {
        val job = failedJob()
        val dismissed = setOf(NowAlertKeys.cron(job))
        // The same job fails again on a later run: a new stamp, a new key, the alert is back.
        val again = job.copy(lastRunAt = "2026-11-15T08:00:00Z")
        val snapshot = NowReducer.derive(gateway, null, inputs(cronJobs = listOf(again), dismissedAlerts = dismissed))
        assertEquals(listOf(NowAlertKeys.cron(again)), snapshot.needsYou.map { it.alertKey })
    }

    @Test
    fun aKanbanAlertIsDismissedPerStatusAndReturnsWhenTheStatusChanges() {
        val blocked = KanbanTask(id = "t1", title = "Ship it", status = "blocked")
        val dismissed = setOf(NowAlertKeys.kanban("feedbucket", blocked))
        assertTrue(NowReducer.derive(gateway, null, inputs(kanbanRows = listOf(blocked), dismissedAlerts = dismissed)).needsYou.isEmpty())
        // The task moved to review: a different fingerprint, so it asks for attention again.
        val review = blocked.copy(status = "review")
        val snapshot = NowReducer.derive(gateway, null, inputs(kanbanRows = listOf(review), dismissedAlerts = dismissed))
        assertEquals(listOf(NowAlertKeys.kanban("feedbucket", review)), snapshot.needsYou.map { it.alertKey })
    }

    @Test
    fun theColdMergeDropsCarriedRowsTheUserDismissed() {
        val job = failedJob()
        val stored = NowReducer.derive(gateway, null, inputs(cronJobs = listOf(job)))
        // A cold pass with no fresh cron evidence carries the stored rows; the dismissal still applies.
        val merged = NowReducer.mergeCold(
            gateway,
            null,
            stored,
            inputs(dismissedAlerts = setOf(NowAlertKeys.cron(job))),
            knownBots = emptyList(),
        )
        assertTrue(merged.needsYou.isEmpty())
    }

    @Test
    fun liveConversationKindsCarryNoAlertKey() {
        // Waiting chats and bot answers clear themselves when answered; they are not dismissible.
        val session = dev.hermeskotlin.core.sessions.SessionSummary(id = "s1", title = "Design review", lastActive = 100.0)
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs().copy(
                sessions = listOf(session),
                waiting = mapOf("s1" to dev.hermeskotlin.core.chat.Waiting.Question),
            ),
        )
        val item = snapshot.needsYou.single()
        assertEquals(NowNeedsYouKind.ChatWaiting, item.kind)
        assertNull(item.alertKey)
    }
}