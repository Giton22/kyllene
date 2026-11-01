package dev.hermeskotlin.ui.preview

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.RfbFrame
import dev.hermeskotlin.ui.screen.ScreenUiState
import dev.hermeskotlin.ui.screen.ScreenView

/** The bot's screen, drawn from a made-up desktop. */
object ScreenSamples {

    /** The agent is working and the user is watching. */
    val watching = ScreenUiState(
        status = status(DisplayLease.AGENT),
        frame = frame(),
        lease = DisplayLease(DisplayLease.AGENT),
        connected = true,
    )

    /** The user has taken the screen over. */
    val inControl = ScreenUiState(
        status = status(DisplayLease.HUMAN),
        frame = frame(),
        lease = DisplayLease(DisplayLease.HUMAN, viewerHash = "0f2c"),
        connected = true,
        holding = true,
    )

    /** The screen is stopped, so there is nothing to watch. */
    val stopped = ScreenUiState(
        status = status(DisplayLease.AGENT).copy(running = false),
        lease = DisplayLease(DisplayLease.AGENT),
        connected = false,
    )

    private fun status(holder: String) = DisplayStatus(
        profile = "scribe",
        supported = true,
        installed = true,
        running = true,
        display = ":20",
        geometry = "1440x900",
        placement = "gateway",
        lease = DisplayLease(holder),
        profileKey = "scribe",
    )

    /** A desktop with a sign-in window on it, the way a bot waiting for a human looks. */
    private fun frame(): RfbFrame {
        val width = 1440
        val height = 900
        val pixels = IntArray(width * height) { 0xFF1B1E24.toInt() }
        // The window.
        for (row in 220 until 620) {
            for (column in 420 until 1020) {
                pixels[row * width + column] = 0xFFF2F4F8.toInt()
            }
        }
        // Its title bar.
        for (row in 220 until 262) {
            for (column in 420 until 1020) {
                pixels[row * width + column] = 0xFF3B6EA5.toInt()
            }
        }
        // The field the user has to fill in.
        for (row in 340 until 396) {
            for (column in 470 until 970) {
                pixels[row * width + column] = 0xFFFFFFFF.toInt()
            }
        }
        // The button.
        for (row in 470 until 534) {
            for (column in 760 until 970) {
                pixels[row * width + column] = 0xFF3B6EA5.toInt()
            }
        }
        return RfbFrame(width, height, pixels, 1)
    }
}

/** The bot's screen, in the app's theme. */
@Composable
fun SampleScreen(state: ScreenUiState = ScreenSamples.watching, title: String = "Scribe") {
    ScreenView(
        state = state,
        title = title,
        onBack = {},
        onStart = {},
        onReopen = {},
        onTakeOver = {},
        onHandBack = {},
        onPointer = { _, _, _ -> },
        onKey = { },
        onCharacter = { },
    )
}
