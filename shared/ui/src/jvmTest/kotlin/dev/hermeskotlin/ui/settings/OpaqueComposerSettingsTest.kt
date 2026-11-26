package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class OpaqueComposerSettingsTest {
    @Test
    fun theAccessibilitySwitchStartsOffAndCanBeTurnedBackOff() = runComposeUiTest {
        var settings by mutableStateOf(AppSettings())
        setContent {
            HermesTheme(ColorScheme.Light) {
                SettingsView(settings, GatewayInfo(), "https://fixture.example", { settings = it(settings) }, {}, {}, {})
            }
        }

        val toggle = onNodeWithText("Opaque composer")
        toggle.performScrollTo().assertIsOff().performClick()
        toggle.assertIsOn().performClick()
        toggle.assertIsOff()
    }
}
