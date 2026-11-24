package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.SubagentTranscriptPage
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.designsystem.HermesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SubagentLiveScrollTest {
    @Test fun followsOnlyWhileReadingTheTail() = runComposeUiTest {
        val pages = Channel<SubagentTranscriptPage>(Channel.UNLIMITED)
        val requests = AtomicInteger()
        fun send(range: IntRange, cursor: String) {
            val rows = range.map { SessionMessage(id = it.toLong(), role = "user", content = JsonPrimitive("Fixture line $it")) }
            check(pages.trySend(SubagentTranscriptPage(rows, cursor, false, true, true, true)).isSuccess)
        }
        send(1..16, "first")
        val task = SubagentRow(key = "task", goal = "Fixture", status = SubagentStatus.Running, transcriptId = "child")
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.size(400.dp, 420.dp)) {
                    SubagentInspector(
                        selected = InspectorRow("task", task), runtimeId = "parent",
                        load = { _, _ -> requests.incrementAndGet(); pages.receive() },
                        onStop = {}, onDismiss = {}, desktop = false, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val list = onNode(hasScrollToIndexAction())
        waitUntil(timeoutMillis = 5000) { requests.get() >= 2 }
        list.performScrollToIndex(15)
        onNodeWithText("Fixture line 16").assertIsDisplayed()
        send(17..19, "second")
        waitUntil(timeoutMillis = 5000) { onAllNodes(androidx.compose.ui.test.hasText("Fixture line 19")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Fixture line 19").assertIsDisplayed()

        list.performScrollToIndex(0)
        send(20..22, "third")
        waitUntil(timeoutMillis = 5000) { requests.get() >= 4 }
        onNodeWithText("Task instructions").assertIsDisplayed()
        onNodeWithText("Fixture line 22").assertDoesNotExist()
        list.performScrollToIndex(21)
        onNodeWithText("Fixture line 22").assertIsDisplayed()
    }
}
