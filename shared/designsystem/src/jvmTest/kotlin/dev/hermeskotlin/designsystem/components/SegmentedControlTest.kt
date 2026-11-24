package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SegmentedControlTest {
    @Test
    fun restingPointerCanLeaveTheSelectedPillAsTheOnlyHighlight() = runComposeUiTest {
        setContent {
            HermesTheme {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Box(Modifier.testTag("surface").width(280.dp).padding(bottom = 24.dp)) {
                        SegmentedControl(
                            options = listOf("Chats", "Bots"),
                            selected = "Chats",
                            onSelect = {},
                            optionLabel = { it },
                            highlightOnHover = false,
                        )
                    }
                }
            }
        }

        val bots = onNodeWithText("Bots")
        val surface = onNodeWithTag("surface")
        surface.performMouseInput { moveTo(Offset(center.x, bottom - 2f)) }
        fun sample() = bots.captureToImage().toPixelMap().let { it[it.width / 4, it.height / 2] }
        val before = sample()
        bots.performMouseInput { moveTo(center) }
        assertEquals(before, sample())
        onNodeWithText("Chats").assertIsSelected()
    }
}
