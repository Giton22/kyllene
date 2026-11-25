package dev.hermeskotlin.android

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.core.graphics.toColorInt
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.hermeskotlin.android.widget.ChatsWidgetProvider
import dev.hermeskotlin.android.widget.KanbanWidgetProvider
import dev.hermeskotlin.android.widget.NowWidgetProvider
import dev.hermeskotlin.android.widget.ScheduledWidgetProvider
import dev.hermeskotlin.android.widget.WidgetRefresher
import dev.hermeskotlin.android.widget.WidgetUpdater
import dev.hermeskotlin.android.widget.WidgetViews
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanStaleTier
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowNeedsYouKind
import dev.hermeskotlin.core.widget.NowTarget
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledProblemKind
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Debug builds only: hosts the four REAL widget RemoteViews in an AppWidgetHost with sample data,
 * for the PR screenshots (the Compose gallery twins alone don't exercise the launcher layouts).
 *
 * ```
 * adb shell appwidget grantbind --package dev.herald.android
 * adb shell am start -n dev.herald.android/dev.hermeskotlin.android.WidgetHostActivity --ez dark true --ez notice false
 * ```
 *
 * `notice` renders the "Couldn't refresh · Saved" strips; the system night mode picks the palette.
 * Sample data only — nothing real is read or shown.
 */
class WidgetHostActivity : ComponentActivity() {

    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 77)
        val dark = intent.getBooleanExtra("dark", true)
        val notice = intent.getBooleanExtra("notice", false)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(24), pad, pad)
        }
        val root = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(if (dark) "#FF0B0E13".toColorInt() else "#FFE8EBF0".toColorInt())
            addView(column, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        host.startListening()
        val manager = AppWidgetManager.getInstance(this)
        val order = listOf(
            NowWidgetProvider::class.java,
            ChatsWidgetProvider::class.java,
            KanbanWidgetProvider::class.java,
            ScheduledWidgetProvider::class.java,
        )
        for (cls in order) {
            val info = manager.installedProviders.firstOrNull { it.provider.className == cls.name } ?: continue
            val id = host.allocateAppWidgetId()
            if (!manager.bindAppWidgetIdIfAllowed(id, info.provider)) continue
            val view = host.createView(this, id, info)
            column.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(info.minHeight + 24)).apply {
                    bottomMargin = dp(16)
                },
            )
        }

        lifecycleScope.launch {
            // The bind-time onUpdate and the startup debounce pass render signed-out first (no
            // gateway is saved in a fresh debug install); let them finish, then push the samples.
            delay(7_000)
            seedAndRender(notice)
        }
    }

    override fun onDestroy() {
        host.stopListening()
        super.onDestroy()
    }

    private suspend fun seedAndRender(notice: Boolean) {
        val koin = GlobalContext.get()
        val store = koin.get<WidgetSnapshotStore>()
        val gw = "https://widgets.example.test"
        val clock = System.currentTimeMillis() / 1000
        val now = NowWidgetSnapshot(
            gatewayUrl = gw,
            profile = null,
            needsYou = listOf(
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.CronProblem,
                    title = "Weekly Discovery Radio",
                    detail = "The last run failed: playlist delivery timed out",
                    target = NowTarget(scheduledJobId = "job_radio"),
                    activityEpochSeconds = (clock - 3_600).toDouble(),
                    alertKey = "cron:job_radio:${clock - 3_600}.0",
                ),
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.KanbanAttention,
                    title = "Shell: a clock that finishes the turn",
                    detail = "blocked",
                    target = NowTarget(kanbanBoard = "waypoint", kanbanTaskId = "t_3"),
                    activityEpochSeconds = (clock - 7_200).toDouble(),
                    alertKey = "kanban:waypoint:t_3:blocked",
                ),
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.ChatWaiting,
                    title = "Weekend deploy plan",
                    detail = "Has a question",
                    target = NowTarget(storedSessionId = "chat_1", title = "Weekend deploy plan"),
                    activityEpochSeconds = (clock - 600).toDouble(),
                ),
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.RoutineFailed,
                    title = "Scribe",
                    detail = "Inbox sweep",
                    target = NowTarget(botProfile = "scribe", scheduledJobId = "job_sweep"),
                    botProfile = "scribe",
                    activityEpochSeconds = (clock - 5_400).toDouble(),
                    alertKey = "cron:job_sweep:${clock - 5_400}.0",
                ),
            ),
            running = emptyList(),
            quiet = null,
            socketSavedAtEpochSeconds = null,
            savedAtEpochSeconds = clock,
        )
        val chats = ChatsWidgetSnapshot(
            gatewayUrl = gw,
            profile = null,
            rows = listOf(
                ChatWidgetRow("chat_3", "Kyllene roadmap", subtitle = "The widget wave lands this week.", unread = true, pinned = true, activityEpochSeconds = (clock - 120).toDouble()),
                ChatWidgetRow("chat_1", "Weekend deploy plan", waitingLabel = "Has a question", activityEpochSeconds = (clock - 600).toDouble()),
                ChatWidgetRow("chat_2", "Release notes draft", running = true, activityEpochSeconds = (clock - 45).toDouble()),
                ChatWidgetRow("chat_4", "Water the plants reminder", subtitle = "Done, every Sunday at 9.", thread = true, activityEpochSeconds = (clock - 86_400).toDouble()),
            ),
            savedAtEpochSeconds = clock,
        )
        val kanban = KanbanWidgetSnapshot(
            gatewayUrl = gw,
            boardSlug = "waypoint",
            boardName = "Waypoint",
            counts = mapOf("running" to 1, "blocked" to 1, "review" to 0, "ready" to 1),
            rows = listOf(
                KanbanWidgetRow("t_3", "Gateway: chat over the Webapp listener", status = "blocked", assignee = "reviewer", priority = 5, stale = KanbanStaleTier.Red),
                KanbanWidgetRow("t_1", "APIs: the schedule off the wire", status = "ready", assignee = "coder", priority = 10),
                KanbanWidgetRow("t_2", "Shell: a clock that finishes the turn", status = "running", assignee = "coder", warningCount = 1),
            ),
            boardCount = 3,
            savedAtEpochSeconds = clock,
        )
        val scheduled = ScheduledWidgetSnapshot(
            gatewayUrl = gw,
            rows = listOf(
                ScheduledWidgetRow("job_radio", displayName = "Weekly Discovery Radio", problemSummary = "The last run failed: playlist delivery timed out", state = "error", isProblem = true, problemKind = ScheduledProblemKind.Failed),
                ScheduledWidgetRow("job_brief", displayName = "Morning briefing", scheduleDisplay = "Every day at 9:00", nextRunEpochSeconds = clock + 3_600, state = "scheduled"),
                ScheduledWidgetRow("job_tidy", displayName = "Weekly disk tidy", scheduleDisplay = "Mondays at 8:00", state = "paused"),
            ),
            savedAtEpochSeconds = clock,
        )
        store.saveNow(now)
        store.saveChats(chats)
        store.saveKanban(kanban)
        store.saveScheduled(scheduled)

        val saved = if (notice) "Couldn't refresh · Saved 3:48 AM" else null
        val outcome = WidgetRefresher.Outcome(
            gatewayUrl = gw,
            gatewayLabel = "Sample gateway",
            profile = null,
            nowCold = false,
            now = WidgetViews.UiState.Content(now, saved),
            chats = WidgetViews.UiState.Content(chats, saved),
            kanban = WidgetViews.UiState.Content(kanban),
            scheduled = WidgetViews.UiState.Content(scheduled, saved),
        )
        koin.get<WidgetUpdater>().render(outcome, locked = false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}