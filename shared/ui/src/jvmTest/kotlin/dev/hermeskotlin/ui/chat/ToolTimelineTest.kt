package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ToolTimelineTest {
    @Test
    fun comparisonAndFullOutputPreserveThePreviousPage() = runComposeUiTest {
        val tools = listOf(
            ToolActivity("read", "read_file", startedAt = 50.0, detail = "Read the backup log", output = "All archives found"),
            ToolActivity(
                "check", "terminal", startedAt = 50.2, detail = "Check backup snapshots",
                input = "backupctl snapshots --latest", output = "Snapshot listing timed out", failed = true, durationSeconds = 2.0,
            ),
        )
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Tools(tools, "backup-reply")
                }
            }
        }

        onNodeWithText("Check backup snapshots").assertDoesNotExist()
        onNodeWithText("Ran 2 tools").performClick()
        onNodeWithText("+0.2s").assertExists()
        onNodeWithText("Read the backup log").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completion unconfirmed"))
        onNodeWithText("Check backup snapshots").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed"))
        onNodeWithText("Check backup snapshots").assertHasNoClickAction()
        onNodeWithText("View tool details").performClick()
        onNodeWithText("Tool details").assertExists()
        onNode(hasText("Read the backup log") and hasClickAction()).performClick()
        onNode(hasText("Check backup snapshots") and hasClickAction()).performClick()
        onNodeWithText("All archives found").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()

        onAllNodesWithText("View full output")[1].performClick()
        onNodeWithText("Tool output").assertExists()
        onNodeWithText("Error").assertExists()
        onNodeWithText("backupctl snapshots --latest").assertExists()
        onNodeWithContentDescription("Back").performClick()

        onNodeWithText("All archives found").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("View tool details").assertExists()
        onNodeWithText("Check backup snapshots").assertExists()
    }
}
