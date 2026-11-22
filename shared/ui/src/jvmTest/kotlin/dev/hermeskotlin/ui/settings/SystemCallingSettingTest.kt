package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.voice.SystemCallingStatus
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SystemCallingSettingTest {
    @Test
    fun unfinishedSetupCanResumeAndShowsOnlyTheCurrentAction() = runComposeUiTest {
        var enabled by mutableStateOf(false)
        var status by mutableStateOf(SystemCallingStatus.NeedsPermissions)
        setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    SystemCallingSetting(
                        access = SystemCallingAccess(
                            status,
                            requestPermissions = { status = SystemCallingStatus.NeedsAccount },
                            openAccounts = { status = SystemCallingStatus.Ready },
                        ),
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                    )
                }
            }
        }

        onNodeWithText("Android call controls").assertIsOff().performClick()
        onNodeWithText("Phone permissions").assertExists()
        onNodeWithText("Calling account").assertExists()
        onNodeWithText("Open calling accounts").assertDoesNotExist()
        onNodeWithContentDescription("Close").performClick()
        onNodeWithText("Allow calling permissions").assertDoesNotExist()

        onNodeWithText("Finish setup").performClick()
        onNodeWithText("Allow calling permissions").performClick()
        onNodeWithText("Allow calling permissions").assertDoesNotExist()
        onNodeWithText("Open calling accounts").performClick()
        onNodeWithText("Done").performClick()
        onNodeWithText("Finish setup").assertDoesNotExist()
        onNodeWithText("Ready for GPT-Live").assertExists()
    }
}
