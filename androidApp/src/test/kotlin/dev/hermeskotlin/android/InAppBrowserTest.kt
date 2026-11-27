package dev.hermeskotlin.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InAppBrowserTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun open(uri: String, inBrowser: Boolean): Intent? {
        InAppBrowser(activity, dark = { false }, openWebLinksInBrowser = { inBrowser }).openUri(uri)
        return shadowOf(activity).nextStartedActivity
    }

    /** A browser app: it opens web links and says it's a browser. */
    private fun installBrowser(packageName: String) {
        fun resolving(intent: Intent) = shadowOf(activity.packageManager).addResolveInfoForIntent(
            intent,
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Main" } },
        )
        resolving(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE))
        resolving(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
    }

    @Test
    fun aWebLinkOpensInACustomTabByDefault() {
        installBrowser("test.browser")

        val started = open("https://example.org/docs", inBrowser = false)!!

        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertNull(started.`package`)
    }

    @Test
    fun withTheSwitchOnAWebLinkGoesToTheBrowserApp() {
        installBrowser("test.browser")

        val started = open("https://example.org/docs", inBrowser = true)!!

        assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals("test.browser", started.`package`)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("https://example.org/docs", started.dataString)
    }

    @Test
    fun mailAndApkLinksKeepTheirRouteWithTheSwitchOn() {
        installBrowser("test.browser")

        for (link in listOf("mailto:someone@example.com", "https://example.org/app-release.apk")) {
            val started = open(link, inBrowser = true)!!
            assertEquals(link, started.dataString)
            assertNull(started.`package`)
            assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
    }

    @Test
    fun withTheSwitchOnAndNoBrowserToNameAndroidPicksTheApp() {
        // No default browser and none that says it's one (some phones' only browser): nothing to pin the link to.
        val started = open("https://example.org/docs", inBrowser = true)!!

        assertNull(started.`package`)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("https://example.org/docs", started.dataString)
    }

    @Test
    fun withTheSwitchOnAndNothingToOpenALinkItSaysSo() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)

        assertNull(open("https://example.org/docs", inBrowser = true))
        assertEquals(activity.getString(R.string.link_no_app), ShadowToast.getTextOfLatestToast())
    }
}
