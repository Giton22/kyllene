package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.BackgroundProcess
import dev.hermeskotlin.core.chat.SubagentRow
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ProcessesSheetTest {
    @Test
    fun activeSubagentWithNoShellProcessesCanExpandItsActivity() = runComposeUiTest {
        val roster = mutableStateOf(listOf(SubagentRow(
            key = "fixture",
            goal = "Check documentation links",
            status = SubagentStatus.Running,
            activity = listOf("Read the link index", "Check the reference pages"),
        )))
        setContent {
            HermesTheme(ColorScheme.Dark) {
                ProcessesSheetView(
                    visible = true,
                    state = ProcessesState(processes = emptyList(), subagents = roster.value),
                    onKill = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("None running").assertDoesNotExist()
        onNodeWithText("Read the link index").assertDoesNotExist()
        onNodeWithText("Check documentation links").performClick()
        onNodeWithText("Read the link index").assertExists()
        runOnIdle { roster.value = emptyList() }
        onNodeWithText("Check documentation links").assertDoesNotExist()
        onNodeWithText("None running").assertExists()
    }

    private fun sheet(state: ProcessesState): @Composable () -> Unit = {
        HermesTheme(ColorScheme.Dark) { ProcessesSheetView(visible = true, state = state, onKill = {}, onDismiss = {}) }
    }

    @Test
    fun aSubagentsOwnChildrenCountAsRunning() = runComposeUiTest {
        val child = SubagentRow(key = "child", goal = "Check the reference pages", status = SubagentStatus.Running)
        val parent = SubagentRow(key = "parent", goal = "Check documentation links", status = SubagentStatus.Running, children = listOf(child))
        setContent(sheet(ProcessesState(processes = emptyList(), subagents = listOf(parent))))

        onNodeWithText("2 running").assertExists()
        onNodeWithText("Check the reference pages").assertExists()
    }

    @Test
    fun aChatBeforeItsFirstReplyGetsOneNote() = runComposeUiTest {
        setContent(sheet(ProcessesState(unavailable = true, subagentsUnavailable = true)))

        onNodeWithText("Available once this chat is connected and has had its first reply.").assertExists()
        onNodeWithText("Subagent status is unavailable.").assertDoesNotExist()
    }

    @Test
    fun anEmptyRosterBeforeTheProcessListStillWaits() = runComposeUiTest {
        val state = mutableStateOf(ProcessesState(subagents = emptyList()))
        setContent { sheet(state.value)() }

        onNodeWithText("None running").assertDoesNotExist()
        onNodeWithText("No background shell processes.").assertDoesNotExist()
        runOnIdle { state.value = state.value.withProcesses(emptyList()) }
        onNodeWithText("None running").assertExists()
        onNodeWithText("Nothing running.", substring = true).assertExists()
    }

    @Test
    fun unavailableSubagentRosterStillShowsShellProcesses() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Light) {
                ProcessesSheetView(
                    visible = true,
                    state = ProcessesState(
                        processes = listOf(BackgroundProcess("proc_1", "fixture build", null, null, true, null, null, "")),
                        subagentsUnavailable = true,
                    ),
                    onKill = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Subagent status is unavailable.").assertExists()
        onNodeWithText("fixture build").assertExists()
        onNodeWithText("None running").assertDoesNotExist()
    }
}
