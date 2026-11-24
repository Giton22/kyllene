package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.DelegatedTask
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SubagentActivityTest {
    @Test
    fun countsAllTasksAndOpensTheSelectedRow() = runComposeUiTest {
        val tasks = (1..5).map { index ->
            DelegatedTask("Audit fixture $index", SubagentStatus.Done, summary = "Result $index")
        }
        val calls = mutableStateOf(listOf(ToolActivity("call", "delegate_task", tasks = tasks)))
        var selected: Pair<String, String>? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalSubagents provides SubagentContext(emptyList()) { reply, key -> selected = reply to key }) {
                    DelegationActivity(calls.value, "reply")
                }
            }
        }
        val group = onNodeWithText("5 subagents")
        group.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        onNodeWithText("Audit fixture 5").assertDoesNotExist()
        group.performClick()
        (1..5).forEach { onNodeWithText("Audit fixture $it").assertExists() }
        onNodeWithText("Result 5").assertDoesNotExist()
        onNodeWithContentDescription("Open transcript").assertDoesNotExist()
        onNodeWithText("Audit fixture 5").performClick()
        onNodeWithText("Result 5").assertExists()
        onNodeWithText("Result 4").assertDoesNotExist()
        onNodeWithContentDescription("Stop this subagent").assertDoesNotExist()
        runOnIdle { assertEquals(null, selected) }
        onNodeWithContentDescription("Open transcript").performClick()
        runOnIdle { assertEquals("reply" to "call:0:4", selected) }

        runOnIdle {
            calls.value = calls.value.map { call ->
                call.copy(running = true, tasks = tasks.dropLast(1) + tasks.last().copy(status = SubagentStatus.Running))
            }
        }
        group.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded, Running"))
    }

    @Test
    fun onlyTheExpandedRunningChildHasAnInlineStopControl() = runComposeUiTest {
        val tasks = listOf("First child", "Second child").map { DelegatedTask(it, SubagentStatus.Running) }
        val call = ToolActivity("call", "delegate_task", running = true, tasks = tasks)
        val roster = listOf(
            Subagent("first", toolId = call.id, goal = tasks[0].goal),
            Subagent("second", toolId = call.id, goal = tasks[1].goal),
        )
        val currentRoster = mutableStateOf(roster)
        val stopped = mutableListOf<String>()
        var opened = false
        setContent {
            HermesTheme(ColorScheme.Dark) {
                val context = SubagentContext(currentRoster.value, onStop = { stopped += it }) { _, _ -> opened = true }
                CompositionLocalProvider(LocalSubagents provides context) {
                    DelegationActivity(listOf(call), "reply")
                }
            }
        }
        val stop = onNodeWithContentDescription("Stop this subagent")
        onNodeWithText("2 subagents").performClick()
        stop.assertDoesNotExist()
        onNodeWithText("Second child").performClick()
        stop.performClick()
        onNodeWithContentDescription("Open transcript").assertExists()
        runOnIdle {
            assertEquals(listOf("second"), stopped)
            assertEquals(false, opened)
            currentRoster.value = roster.map { it.copy(status = SubagentStatus.Done) }
        }
        stop.assertDoesNotExist()
    }

    @Test
    fun theFullTranscriptStillStopsItsSelectedChild() = runComposeUiTest {
        val call = ToolActivity("call", "delegate_task", running = true)
        val child = Subagent("selected", toolId = call.id, goal = "Inspect the transcript")
        val row = delegationRows(listOf(call), listOf(child)).single()
        var stopped: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                SubagentInspector(
                    selected = row, runtimeId = "parent", load = { _, _ -> null },
                    onStop = { stopped = it }, onDismiss = {}, desktop = false, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        onNodeWithContentDescription("Stop this subagent").performClick()
        runOnIdle { assertEquals("selected", stopped) }
    }

    @Test
    fun selectedChildStaysInItsReplyWhenTaskArgumentsArrive() {
        val call = ToolActivity("repeated", "delegate_task")
        val earlier = ChatMessage.Assistant(
            "earlier", tools = listOf(call.copy(tasks = listOf(DelegatedTask("Earlier", SubagentStatus.Done)))),
        )
        val current = ChatMessage.Assistant("current", tools = listOf(call))
        val child = Subagent("child", toolId = call.id, goal = "Current")
        val selection = delegationRows(current.tools, listOf(child)).single().selectionKey
        val hydrated = current.copy(tools = listOf(call.copy(tasks = listOf(DelegatedTask("Current", SubagentStatus.Running)))))
        val selected = selectedDelegationRow(listOf(earlier, hydrated), listOf(child), "current", selection)
        assertEquals("child", selected?.task?.transcriptId)
        assertEquals("call:0:0", selected?.key)
    }

    @Test
    fun separateDelegationsCanReuseTheSameToolCallId() {
        val calls = listOf("first", "second").map { id ->
            ToolActivity("repeated", "delegate_task", tasks = listOf(DelegatedTask("Same goal", SubagentStatus.Done, transcriptId = id)))
        }
        val roster = listOf("second", "first").map { id -> Subagent(id, toolId = "repeated", goal = "Same goal") }
        val rows = delegationRows(calls, roster)
        assertEquals(listOf("first", "second"), rows.map { it.task.transcriptId })
        assertEquals(2, rows.map { it.key }.distinct().size)
    }

    @Test
    fun aRefusedTaskStaysVisibleWithoutIncreasingTheCount() = runComposeUiTest {
        val tasks = listOf(
            DelegatedTask("Accepted", SubagentStatus.Unwatched),
            DelegatedTask("Refused", SubagentStatus.Failed, summary = "No backend", refused = true),
        )
        setContent {
            HermesTheme(ColorScheme.Dark) {
                DelegationActivity(listOf(ToolActivity("call", "delegate_task", tasks = tasks)), "reply")
            }
        }
        onNodeWithText("1 subagent").performClick()
        onNodeWithText("No backend").assertDoesNotExist()
        onNodeWithText("Refused").performClick()
        onNodeWithText("No backend").assertExists()
    }
}
