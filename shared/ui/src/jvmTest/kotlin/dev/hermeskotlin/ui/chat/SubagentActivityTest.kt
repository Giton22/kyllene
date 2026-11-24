package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
        val tasks = (1..5).map { index -> DelegatedTask("Audit fixture $index", SubagentStatus.Done) }
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
        onNodeWithText("Audit fixture 5").performClick()
        runOnIdle { assertEquals("reply" to "call:0:4", selected) }

        runOnIdle {
            calls.value = calls.value.map { call ->
                call.copy(running = true, tasks = tasks.dropLast(1) + tasks.last().copy(status = SubagentStatus.Running))
            }
        }
        group.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded, Running"))
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
        onNodeWithText("Refused").assertExists()
        onNodeWithText("No backend").assertExists()
    }
}
