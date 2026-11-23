package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import org.koin.compose.koinInject

@Composable
internal actual fun androidCallControlsAvailable(): Boolean =
    koinInject<VoiceKeepAlive>().supportsAndroidCallControls()
