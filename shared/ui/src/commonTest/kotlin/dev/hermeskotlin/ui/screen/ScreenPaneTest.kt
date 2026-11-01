package dev.hermeskotlin.ui.screen

import androidx.compose.ui.input.key.Key
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.ScreenKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenPaneTest {

    @Test
    fun aWideScreenIsLetterboxedInATallBox() {
        // A 1440x900 screen in a box 800 wide and 600 high: as wide as the box allows, and centred in it.
        val fit = fitFrame(1440, 900, 800, 600)!!
        assertEquals(800, fit.width)
        assertEquals(500, fit.height)
        assertEquals(0, fit.offsetX)
        assertEquals(50, fit.offsetY)
    }

    @Test
    fun aTallScreenIsLetterboxedInAWideBox() {
        val fit = fitFrame(900, 1440, 800, 600)!!
        assertEquals(375, fit.width)
        assertEquals(600, fit.height)
        assertEquals((800 - 375) / 2, fit.offsetX)
        assertEquals(0, fit.offsetY)
    }

    @Test
    fun aScreenWithNoSizeIsNotDrawn() {
        assertNull(fitFrame(0, 900, 800, 600))
        assertNull(fitFrame(1440, 900, 0, 600))
    }

    @Test
    fun aTapInThePictureIsThePointOnTheScreen() {
        val fit = fitFrame(1440, 900, 800, 600)!!
        // The middle of the picture is the middle of the screen.
        assertEquals(720 to 450, screenPoint(400f, 300f, fit, 1440, 900))
        // The top left corner of the picture is the top left corner of the screen.
        assertEquals(0 to 0, screenPoint(0f, 50f, fit, 1440, 900))
    }

    @Test
    fun aTapInTheLetterboxIsNowhereOnTheScreen() {
        val fit = fitFrame(1440, 900, 800, 600)!!
        // Above the picture, and below it.
        assertNull(screenPoint(400f, 10f, fit, 1440, 900))
        assertNull(screenPoint(400f, 590f, fit, 1440, 900))
    }

    @Test
    fun aGatewayWithoutAScreenIsSaidSo() {
        assertEquals(ScreenStage.Unsupported, screenStage(ScreenUiState(supported = false)))
    }

    @Test
    fun aScreenThatHasNotAnsweredYetIsWaitedFor() {
        assertEquals(ScreenStage.Looking, screenStage(ScreenUiState()))
    }

    @Test
    fun aScreenThatIsNotInstalledAsksForTheCommand() {
        val state = ScreenUiState(
            status = status(installed = false, installCommand = "hermes screen install"),
        )
        assertEquals(ScreenStage.NotInstalled, screenStage(state))
        assertEquals("hermes screen install", state.status?.installCommand)
    }

    @Test
    fun aScreenTheGatewayWillNotStartSaysWhy() {
        val state = ScreenUiState(status = status(installed = true, blocker = "No memory left on the host"))
        assertEquals(ScreenStage.Blocked, screenStage(state))
    }

    @Test
    fun aStoppedScreenIsOfferedToStart() {
        assertEquals(ScreenStage.Stopped, screenStage(ScreenUiState(status = status(installed = true))))
    }

    @Test
    fun aStreamThatDroppedIsOfferedToOpenAgain() {
        val state = ScreenUiState(status = status(installed = true, running = true), connected = false, everConnected = true)
        assertEquals(ScreenStage.Dropped, screenStage(state))
    }

    @Test
    fun aStreamStillOnItsWayIsWaitedFor() {
        val state = ScreenUiState(status = status(installed = true, running = true), connected = false)
        assertEquals(ScreenStage.Looking, screenStage(state))
    }

    @Test
    fun aScreenThatIsRunningAndOpenIsWatched() {
        val state = ScreenUiState(status = status(installed = true, running = true), connected = true)
        assertEquals(ScreenStage.Watching, screenStage(state))
    }

    @Test
    fun theBotIsInControlUntilTheUserTakesOver() {
        val agent = DisplayLease(DisplayLease.AGENT)
        assertEquals("The bot is in control", controlLabel(agent, holding = false))
        assertEquals("You are in control", controlLabel(agent, holding = true))
        assertEquals("Someone else is in control", controlLabel(DisplayLease(DisplayLease.HUMAN), holding = false))
    }

    @Test
    fun onlyTheLeaseThisAppTookIsThisAppsOwn() {
        val human = DisplayLease(DisplayLease.HUMAN, viewerHash = "abc")
        // The agent holds it, whoever this app is.
        assertTrue(!holdsTheScreen(DisplayLease(DisplayLease.AGENT), "abc"))
        // This app never took it over.
        assertTrue(!holdsTheScreen(human, null))
        // It took it over, and the gateway says whose human it is.
        assertTrue(holdsTheScreen(human, "abc"))
        assertTrue(!holdsTheScreen(human, "xyz"))
        // It took it over, and the gateway doesn't say whose human it is.
        assertTrue(holdsTheScreen(human, ""))
    }

    @Test
    fun theKeysWithNoCharacterAreThePanesOwn() {
        assertEquals(ScreenKey.Enter, screenKey(Key.Enter))
        assertEquals(ScreenKey.Enter, screenKey(Key.NumPadEnter))
        assertEquals(ScreenKey.Backspace, screenKey(Key.Backspace))
        assertEquals(ScreenKey.Tab, screenKey(Key.Tab))
        assertEquals(ScreenKey.Escape, screenKey(Key.Escape))
        assertEquals(ScreenKey.Left, screenKey(Key.DirectionLeft))
        assertEquals(ScreenKey.Home, screenKey(Key.MoveHome))
        assertEquals(ScreenKey.F5, screenKey(Key.F5))
        // A letter is a character, and goes in as one.
        assertNull(screenKey(Key.A))
    }

    private fun status(
        installed: Boolean,
        running: Boolean = false,
        installCommand: String? = null,
        blocker: String? = null,
    ) = DisplayStatus(
        profile = "scribe",
        supported = true,
        installed = installed,
        running = running,
        installCommand = installCommand,
        blocker = blocker,
        lease = DisplayLease(DisplayLease.AGENT),
    )
}
