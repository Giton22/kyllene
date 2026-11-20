package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.applyAppLanguage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DesktopPushLocaleTest {
    @Test fun desktopCopyAndConsentUseSpanishWithPerKeyEnglishFallback() = runComposeUiTest {
        val locale = Locale.getDefault()
        var language by mutableStateOf("es")
        try {
            setContent {
                ProvideAppLanguage(language) {
                    HermesTheme(ColorScheme.Light) {
                        CompositionLocalProvider(LocalDesktopLayout provides true) {
                            SettingsView(AppSettings(), GatewayInfo(), "https://fixture.example", {}, {}, {}, {},
                                pushAvailable = true, onPushAnywhere = { error("Consent must not install in this test") })
                        }
                    }
                }
            }
            val off = "Recibe noticias de tus bots incluso fuera de la red de tu Gateway, cifradas de extremo a extremo a través de ntfy.sh. Instala el plugin herald-push en el Gateway. Recibe mientras Kyllene está en ejecución."
            onNodeWithText(off).performScrollTo().assertExists()
            onNodeWithText("Notificaciones en cualquier lugar").performClick()
            onNodeWithText("Vigila los chats de tus bots y envía cada respuesta nueva a este ordenador mientras Kyllene está en ejecución, cifrada de extremo a extremo a través de ntfy.sh.", substring = true).assertExists()
            onNodeWithText("Cancelar").performClick()
            runOnIdle { language = "de" }
            onNodeWithText("Hear from your bots even off your gateway's network, end-to-end encrypted through ntfy.sh. Installs the herald-push plugin on the gateway. Receives while Kyllene is running.").performScrollTo().assertExists()
        } finally {
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }
}
