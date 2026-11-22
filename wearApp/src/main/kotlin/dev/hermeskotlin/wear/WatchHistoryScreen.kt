package dev.hermeskotlin.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** Gateway-backed reading with the watch's existing local voice for optional read-aloud. */
@Composable
internal fun WatchHistoryScreen(link: WatchLink, store: WatchStore, speech: SpeechOut) {
    val page by link.history.collectAsState()
    val loading by link.historyLoading.collectAsState()
    val chat by store.chat.collectAsState()
    val phone by link.phone.collectAsState()
    val state = rememberTransformingLazyColumnState()
    var positioned by remember(chat.id, chat.botProfile, chat.scope) { mutableStateOf(false) }
    // Short pieces stay scrollable within the narrow middle of a round display, even for a very long answer.
    val pieces = remember(page?.messages) {
        page?.messages.orEmpty().flatMap { message ->
            val parts = historyTextParts(message.text)
            parts.mapIndexed { index, text ->
                HistoryPiece("${message.id}:$index", message.role, text,
                    message.text.takeIf { message.role == "assistant" && index == parts.lastIndex })
            }
        }
    }
    LaunchedEffect(chat.id, chat.botProfile, chat.scope, phone == PhoneState.Ready) {
        if (phone == PhoneState.Ready) link.loadHistoryIfMissing()
    }
    LaunchedEffect(page?.request?.requestId, pieces.size, loading) {
        if (!positioned && !link.historyLoading.value && pieces.isNotEmpty()) {
            state.scrollToItem(pieces.size + 1)
            positioned = true
        }
    }
    LaunchedEffect(state, positioned) {
        snapshotFlow {
            if (positioned && state.isScrollInProgress && state.lastScrolledBackward && state.anchorItemIndex <= 1 &&
                !loading && page?.error == null) page?.nextOffset else null
        }.collect { if (it != null) link.readHistory(older = true) }
    }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), modifier = Modifier.fillMaxSize()) {
            item(key = "title") {
                Text(chat.title ?: stringResource(R.string.wear_history_title), modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            }
            item(key = "older") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (page?.nextOffset != null) Button(onClick = { link.readHistory(older = true) }, enabled = !loading,
                        label = { Text(stringResource(R.string.wear_history_earlier)) })
                    if (loading) Text(stringResource(R.string.wear_history_loading), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    val error = page?.error
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                    if (!loading) Button(onClick = { positioned = false; link.readHistory() }, label = { Text(stringResource(R.string.wear_history_refresh)) })
                    if (!loading && pieces.isEmpty() && error == null) Text(if (page == null) {
                        if (phone == PhoneState.Ready) stringResource(R.string.wear_history_prompt) else stringResource(R.string.wear_phone_not_connected_detail)
                    } else if (page?.nextOffset != null) stringResource(R.string.wear_history_empty_page) else stringResource(R.string.wear_history_empty))
                }
            }
            pieces.forEach { (id, role, text, readAloud) ->
                item(key = id) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (role == "user") stringResource(R.string.wear_history_you) else "Hermes", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        if (readAloud != null) IconButton(onClick = { speech.say("history:$id", spokenText(readAloud)) }) {
                            Icon(painterResource(R.drawable.ic_volume), stringResource(R.string.wear_history_read),
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

private data class HistoryPiece(val id: String, val role: String, val text: String, val readAloud: String?)

internal fun historyTextParts(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 600, text.length)
        // Each part is rendered alone: keep both halves of an emoji or other supplementary character together.
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}
