package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplyToTest {
    @Test
    fun theMenuHandsTheReplyOverToBeQuoted() = runComposeUiTest {
        var quoted: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.size(420.dp, 480.dp).padding(20.dp)) {
                        AssistantReply(
                            message = ChatMessage.Assistant(key = "reply", text = "The file is in `src/core`.\n\nReady to review."),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReply = { quoted = it },
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription("More").performClick()
        onNodeWithText("Reply").performClick()
        runOnIdle { assertEquals("The file is in `src/core`.\n\nReady to review.", quoted) }
    }

    @Test
    fun aStreamingReplyHasNoReplyToIt() = runComposeUiTest {
        var quoted: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.size(420.dp, 480.dp).padding(20.dp)) {
                        AssistantReply(
                            message = ChatMessage.Assistant(key = "reply", text = "Writing…", streaming = true),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReply = { quoted = it },
                        )
                    }
                }
            }
        }

        onNodeWithContentDescription("More").assertDoesNotExist()
        onNodeWithText("Reply").assertDoesNotExist()
        runOnIdle { assertEquals(null, quoted) }
    }

    @Test
    fun theQuoteTakenFromAReplyIsOneLineAndStopsAtTwoLines() {
        assertEquals("Done.", quoteOneLine("Done."))
        assertEquals("One Two.", quoteOneLine("\u2068One\n\n   Two.\u2069"))
        val quote = quoteOneLine("café ".repeat(80))
        assertTrue(quote.endsWith("…"), quote)
        assertTrue(quote.length <= QUOTE_MAX + 1, quote)
    }
}
