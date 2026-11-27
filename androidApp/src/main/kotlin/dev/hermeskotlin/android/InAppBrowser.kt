package dev.hermeskotlin.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Opens web pages in a Custom Tab unless the user prefers their browser app. Other links
 * (mail, an APK download) go to whichever app handles them.
 */
class InAppBrowser(
    private val activity: Activity,
    private val dark: () -> Boolean,
    private val openWebLinksInBrowser: () -> Boolean,
) : UriHandler {

    override fun openUri(uri: String) {
        val parsed = Uri.parse(uri)
        val webPage = opensInTab(parsed)
        val browserOnly = webPage && openWebLinksInBrowser()
        if (webPage && !browserOnly) {
            try {
                customTab().launchUrl(activity, parsed)
                return
            } catch (_: ActivityNotFoundException) {
                // Nothing takes it as a tab; try a plain view below.
            }
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, parsed)
            if (browserOnly) {
                // No browser to name (none is the default, and none says it's one): let Android pick, as without the switch.
                intent.addCategory(Intent.CATEGORY_BROWSABLE).setPackage(activity.browserPackage())
            }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun customTab(): CustomTabsIntent {
        fun colors(color: Int) = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(activity, color))
            .setNavigationBarColor(ContextCompat.getColor(activity, color))
            .build()
        return CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (dark()) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, colors(R.color.window_background_light))
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, colors(R.color.window_background_dark))
            .build()
    }

    companion object {
        /** Web pages, but not an APK download (the update banner's), which a browser hands to the installer. */
        fun opensInTab(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && uri.path?.endsWith(".apk", ignoreCase = true) != true
        }
    }
}

/** The user's browser: what opens a link no app claims, else the first browser there is. */
internal fun Context.browserPackage(): String? {
    val manager = packageManager
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    // With no default, this is Android's own "Open with" list, which isn't a browser.
    val chosen = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (chosen != null && chosen in browsers) return chosen
    // Else one that calls itself a browser: any app may take every link, and it shouldn't get them unasked.
    val real = manager.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0)
        .map { it.activityInfo.packageName }
    return browsers.firstOrNull { it in real }
}
