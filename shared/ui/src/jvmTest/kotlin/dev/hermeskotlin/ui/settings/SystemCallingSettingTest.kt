package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SystemCallingSettingTest {
    @Test
    fun androidCallControlsToggleWorksWithoutOpeningSetup() = runComposeUiTest {
        var enabled by mutableStateOf(false)
        setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    SystemCallingSetting(enabled = enabled, onEnabledChange = { enabled = it })
                }
            }
        }

        val toggle = onNodeWithText("Android call controls")
        toggle.assertIsOff().performClick()
        toggle.assertIsOn()
        onNodeWithText("Use Android Auto, Wear OS, and Bluetooth headsets to control GPT-Live calls.").assertExists()
        onNodeWithText("Finish setup").assertDoesNotExist()
        onNodeWithText("Open calling accounts").assertDoesNotExist()
        toggle.performClick().assertIsOff()
    }
}
