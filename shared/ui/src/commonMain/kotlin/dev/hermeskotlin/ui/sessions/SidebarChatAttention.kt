package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.sessions.LiveSessions
import dev.hermeskotlin.core.sessions.LiveStatus
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.ui.bots.BotsUiState

/** Tab acknowledgment does not change filter membership or the transcript's read position. */
internal data class SidebarChatSignal(
    val waiting: Waiting? = null,
    val requestId: String? = null,
    val unreadAt: Double? = null,
) {
    fun isUnseen(seen: List<SidebarChatReceipt>): Boolean {
        val unseenRequest = waiting != null && if (requestId != null) {
            seen.none { requestId in it.requestIds }
        } else {
            seen.none { it.legacyViewedAt != null }
        }
        val seenAt = seen.mapNotNull { it.unreadAt }.maxOrNull()
        return unseenRequest || unreadAt?.let { seenAt == null || it > seenAt } == true
    }
}

internal fun sidebarAttentionOrder(status: RowStatus?): Int = when {
    status?.waiting != null -> 0
    status?.unread == true -> 1
    else -> 2
}

internal fun ThreadSections.filteredForAttention(filter: AttentionFilter, statuses: Map<String, RowStatus>): ThreadSections {
    val matchingChats = others.filter { filter.keeps(statuses[it.id]) }
    return ThreadSections(
        threads.filter { filter.keeps(statuses[it.session.id]) },
        if (filter == AttentionFilter.NeedsAttention) matchingChats else matchingChats.sortedBy { sidebarAttentionOrder(statuses[it.id]) },
    )
}

@Composable
internal fun sidebarTabCounts(
    gateway: GatewayUrl,
    profile: String,
    launchProfile: String,
    sessions: List<SessionSummary>,
    statuses: Map<String, RowStatus>,
    bots: BotsUiState,
    waiting: Map<String, Waiting>,
    requests: Map<String, InputRequest>,
    live: LiveSessions,
    nonChatAlerts: Set<String>,
    acknowledgements: SidebarChatAcknowledgements,
): Pair<Int, Int> {
    val flow = remember(gateway, acknowledgements) { acknowledgements.receipts(gateway) }
    val loaded by flow.collectAsStateWithLifecycle(initialValue = null)
    val seen = loaded?.takeIf { it.first == gateway }?.second.orEmpty()
    LaunchedEffect(gateway, launchProfile) { acknowledgements.bindLaunchProfile(gateway, launchProfile) }
    val viewers by acknowledgements.viewers.collectAsStateWithLifecycle()

    val (botChats, chatRows) = remember(bots.bots, bots.hidden, sessions, profile) {
        val botChats = bots.all.mapNotNull { bot ->
            bot.canonicalSession?.let { chat ->
                val key = chat.id ?: chat.openId ?: return@let null
                bot.name to SidebarChatEntry(bot.name, key, listOfNotNull(chat.id, chat.openId).toSet(), chat.activityAt)
            }
        }.toMap()
        val botsByAlias = botChats.values.flatMap { bot -> bot.aliases.map { it to bot } }
            .groupBy({ it.first }, { it.second })
        val chatRows = sessions.associate { session ->
            val aliases = (listOf(session.id) + session.lineageIds.orEmpty()).toSet()
            val bot = aliases.firstNotNullOfOrNull { id ->
                val matches = botsByAlias[id].orEmpty()
                matches.firstOrNull { it.profile == profile } ?: matches.singleOrNull()
            }
            session.id to SidebarChatEntry(bot?.profile ?: profile, bot?.key ?: session.id, aliases + bot?.aliases.orEmpty(), session.activityAt)
        }
        botChats to chatRows
    }

    fun signal(entry: SidebarChatEntry, unread: Boolean): SidebarChatSignal {
        val pending = entry.aliases.firstNotNullOfOrNull(waiting::get)
        return SidebarChatSignal(
            waiting = pending,
            requestId = entry.aliases.firstNotNullOfOrNull { id ->
                requests[id]?.takeIf { Waiting.of(it) == pending }?.id
            },
            unreadAt = (entry.activityAt ?: 0.0).takeIf { unread },
        )
    }

    val entries = botChats.values + chatRows.values
    val viewersHere = viewers.values.filter { it.gateway == gateway }
    val read = viewersHere.flatMap { viewer ->
        val owner = viewer.profile ?: launchProfile
        val profileRows = entries.filter { it.profile == owner } + chatRows.values.filter { owner == profile }
        val matched = profileRows.filter { it.aliases.any(viewer.ids::contains) }
        val matchedProfiles = matched.groupBy { it.profile }.ifEmpty { mapOf(owner to emptyList()) }
        matchedProfiles.flatMap { (matchedProfile, rows) ->
            val ids = viewer.ids + rows.flatMap { it.aliases }
            val activityAt = rows.mapNotNull { it.activityAt }.maxOrNull()
            val legacyWaiting = viewer.requests.isNotEmpty() ||
                ids.any { waiting.containsKey(it) || live.rows[it]?.status == LiveStatus.Waiting }
            ids.map { id ->
                SidebarChatReceipt(matchedProfile, id, viewer.requests, activityAt, viewer.viewedAt.takeIf { legacyWaiting })
            }
        }
    }.distinct()
    LaunchedEffect(gateway, read) {
        acknowledgements.acknowledge(gateway, read)
    }

    // Only a successful gateway answer can end a legacy wait. Disconnects and unmounted lists say nothing.
    LaunchedEffect(gateway, live, waiting, entries, read, seen) {
        if (live.askedAtMillis <= 0) return@LaunchedEffect
        entries.distinctBy { it.profile to it.key }.forEach { entry ->
            val current = read.any { it.profile == entry.profile && it.id in entry.aliases }
            val hasLegacyReceipt = seen.any { it.profile == entry.profile && it.id in entry.aliases && it.legacyViewedAt != null }
            if (!current && hasLegacyReceipt && entry.aliases.none {
                    waiting.containsKey(it) || live.rows[it]?.status == LiveStatus.Waiting
                }) acknowledgements.clearLegacyWait(gateway, entry.profile, entry.aliases, live.askedAtMillis)
        }
    }

    if (loaded?.first != gateway) return 0 to 0
    val receipts = (seen + read).groupBy { it.profile to it.id }
    fun unseen(entry: SidebarChatEntry, unread: Boolean): Boolean =
        signal(entry, unread).isUnseen(entry.aliases.flatMap { receipts[entry.profile to it].orEmpty() })

    val unseenChats = chatRows.asSequence().filter { (id, row) ->
        unseen(row, statuses[id]?.unread == true)
    }.map { it.value.key }.toSet().size
    val unseenBots = bots.all.count { bot ->
        bot.name in nonChatAlerts || botChats[bot.name]?.let { unseen(it, bot.name in bots.unread) } == true
    }
    return unseenChats to unseenBots
}

private data class SidebarChatEntry(val profile: String, val key: String, val aliases: Set<String>, val activityAt: Double?)
