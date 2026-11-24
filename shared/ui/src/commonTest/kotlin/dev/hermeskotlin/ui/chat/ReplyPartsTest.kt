package dev.hermeskotlin.ui.chat

import com.composables.icons.lucide.FileDiff
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.Wrench
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyPartsTest {

    @Test
    fun toolOffsetsTrackRecordedStartsIncludingParallelCallsAndGaps() {
        val tools = listOf(
            ToolActivity("1", "terminal", durationSeconds = 3.0, startedAt = 100.0),
            ToolActivity("2", "read_file", startedAt = 100.2),
            ToolActivity("3", "patch"),
            ToolActivity("4", "read_file", startedAt = 106.04),
        )

        assertEquals(listOf("+0.0s", "+0.2s", null, "+6.0s"), toolTimelineOffsets(tools))
    }

    @Test
    fun toolOffsetsStayHiddenWhenTheFirstCallHasNoReliableStart() {
        val tools = listOf(
            ToolActivity("replayed", "terminal"),
            ToolActivity("live", "read_file", startedAt = 106.0),
        )

        assertEquals(listOf(null, null), toolTimelineOffsets(tools))
    }

    @Test
    fun toolsGetTheIconOfTheirKind() {
        assertEquals(Lucide.SquareTerminal, toolIcon("terminal"))
        assertEquals(Lucide.FileDiff, toolIcon("patch"))
        assertEquals(Lucide.Globe, toolIcon("browser_navigate"))
        assertEquals(Lucide.Wrench, toolIcon("some_plugin_tool"))
    }

    @Test
    fun usageReadsInThenOut() {
        assertEquals("18.4k ↓ · 612 ↑", replyUsage(18_400, 612))
    }
}
