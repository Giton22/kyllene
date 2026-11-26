package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ModelProvider
import dev.hermeskotlin.core.models.ReasoningEffort
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.models.starKey
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.LocalAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopModelPickerTest {
    @Test
    fun openingTheEffortPanelKeepsTheFillSteady() = runComposeUiTest {
        val option = ModelOption("test-model", "test", reasoning = true)
        val open = mutableStateOf(false)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.width(330.dp)) {
                    if (open.value) {
                        QuickEffortPanel(
                            ChatState(reasoningEffort = ReasoningEffort.Medium.wire),
                            ModelSelection(option.id, option.provider, option), "Test",
                            onSelect = {}, onFast = {}, onOpenModels = {},
                        )
                    }
                }
            }
        }
        val slider = onNodeWithContentDescription("Reasoning effort")
        fun brightPixelsInFill(): Int {
            val image = slider.captureToImage().toPixelMap()
            // Sample the dark-blue fill before the thumb; the thumb itself is always white.
            return (0 until image.height).sumOf { y ->
                (0 until image.width / 4).count { x -> image[x, y].red > .5f }
            }
        }
        mainClock.autoAdvance = false
        repeat(2) {
            runOnIdle { open.value = true }
            mainClock.advanceTimeBy(176)
            val opening = brightPixelsInFill()
            mainClock.advanceTimeBy(600)
            val settled = brightPixelsInFill()
            assertEquals(settled, opening)
            runOnIdle { open.value = false }
            mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun effortOpensFirstAndModelListKeepsFavoritesAcrossProviders() = runComposeUiTest {
        val primary = ModelOption("gpt-6.2-sol", "openai", reasoning = true)
        val favorite = ModelOption("qwen3.8-flash", "local")
        val other = ModelOption("fixture-third-model", "anthropic")
        val catalog = ModelCatalog(listOf(
            ModelProvider("openai", "OpenAI", listOf(primary)),
            ModelProvider("local", "Local", listOf(favorite)),
            ModelProvider("anthropic", "Anthropic", listOf(other)),
        ), primary.id, primary.provider)
        val effort = mutableStateOf(ReasoningEffort.Medium)
        val settings = mutableStateOf(AppSettings(starredModels = listOf(favorite.starKey)))
        val picked = mutableListOf<ModelOption>()
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalAppSettings provides settings.value) {
                    Box(Modifier.fillMaxSize()) {
                        ModelSheet(
                            visible = true, onDismiss = {}, state = ChatState(reasoningEffort = effort.value.wire),
                            picker = ModelPickerState(catalog), onRefresh = {}, onSelectModel = { picked += it },
                            onSelectEffort = { effort.value = it }, onFast = {}, onSettings = { settings.value = it(settings.value) },
                            quickPicker = true, anchor = { Rect(48f, 500f, 190f, 534f) },
                        )
                    }
                }
            }
        }
        onNodeWithContentDescription("Favorites").assertDoesNotExist()
        onNodeWithContentDescription("Reasoning effort").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        runOnIdle { assertEquals(ReasoningEffort.XHigh, effort.value) }
        onNodeWithText(displayModelName(primary.id)).performClick()
        onNodeWithContentDescription("Favorites").assertIsDisplayed()
        onNodeWithText(displayModelName(other.id)).assertDoesNotExist()
        onNodeWithText("Anthropic").performClick()
        onNodeWithText(displayModelName(other.id)).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf(other), picked) }
        onNodeWithContentDescription("Star ${displayModelName(other.id)}").performClick()
        runOnIdle { assertEquals(listOf(favorite.starKey, other.starKey), settings.value.starredModels) }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Reasoning effort").assertIsDisplayed()
    }
}
