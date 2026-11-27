package dev.hermeskotlin.ui.update

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.designsystem.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateBannerTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private val release = "https://github.com/Giton22/kyllene/releases/tag/v2.0.0"
    private val asset = "https://github.com/Giton22/kyllene/releases/download/v2.0.0/kyllene.apk"
    private val width = mutableStateOf(336.dp)

    // The library has no manifest registering the Compose host activity.
    private val registerActivity = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(registerActivity).around(compose)

    @Test
    fun notesWrapAndOpenTheReleaseHistoryWithoutStartingADownload() {
        showBanner(asset)
        val download = compose.onNodeWithText("Download")
        val notes = compose.onNodeWithText("Release notes")
        assertEquals(download.fetchSemanticsNode().boundsInRoot.top, notes.fetchSemanticsNode().boundsInRoot.top)

        compose.runOnIdle { width.value = 192.dp }
        val downloadBounds = download.fetchSemanticsNode().boundsInRoot
        val notesBounds = notes.fetchSemanticsNode().boundsInRoot
        assertTrue(notesBounds.top >= downloadBounds.bottom)

        notes.performClick().assertExists()
        download.performClick()
        compose.runOnIdle { assertEquals(listOf("https://github.com/Giton22/kyllene/releases", asset), opened) }
    }

    @Test
    fun noAssetOffersOnlyTheTaggedReleasePage() {
        showBanner(null)
        compose.onNodeWithText("Release notes").assertDoesNotExist()
        compose.onNodeWithText("Open release").performClick()
        compose.runOnIdle { assertEquals(listOf(release), opened) }
    }

    private fun showBanner(downloadUrl: String?) {
        compose.setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(
                    LocalUpdateOffer provides UpdateOffer(AppUpdate("2.0.0", release, downloadUrl)) {},
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    },
                ) {
                    Box(Modifier.width(width.value)) { UpdateBanner() }
                }
            }
        }
    }
}
