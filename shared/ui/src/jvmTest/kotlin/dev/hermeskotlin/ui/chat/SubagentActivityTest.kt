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
import dev.hermeskotlin.core.chat.DelegatedTask
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
        var selected: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalSubagents provides SubagentContext(emptyList()) { _, key -> selected = key }) {
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
        runOnIdle { assertEquals("call:4", selected) }

        runOnIdle {
            calls.value = calls.value.map { call ->
                call.copy(running = true, tasks = tasks.dropLast(1) + tasks.last().copy(status = SubagentStatus.Running))
            }
        }
        group.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded, Running"))
    }
}
