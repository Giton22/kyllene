package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Coffee
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Users
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.update.LocalSupportOffer
import org.jetbrains.compose.resources.stringResource

/** Where Kyllene's code lives; a star there helps other people find it. */
internal const val REPO_URL = "https://github.com/Giton22/kyllene"

/** One-time tips, by card or PayPal. The same account as `ko_fi` in `.github/FUNDING.yml`. */
internal const val KOFI_URL = "https://ko-fi.com/giton"

/** Monthly or one-time sponsorship. The same account as `github` in `.github/FUNDING.yml`. */
internal const val SPONSORS_URL = "https://github.com/sponsors/Giton22"

/**
 * The people named on Settings › Contributors and supporters, by GitHub login. A weekly job adds new contributors and
 * public GitHub sponsors here and in the README (.github/workflows/credits.yml), so keep each list as one `listOf`.
 * Add a Ko-fi supporter by hand, and only when they said they'd like to be named. Someone who asks to be taken off
 * also goes in that script's LEAVE_OFF (.github/scripts/update-credits.py), or the job adds them back.
 */
internal object Credits {
    val maintainers = listOf("Giton22")

    val contributors = listOf(
        "StavenCross", "AlexGreenUK", "StefanoHernandez", "Rockey011", "faultoverload", "jcrabapple",
        "asmodaydoescoding", "goetterbote2342", "oriolj", "0rcasec", "guberm", "matanrom", "jaffer1979", "vdexter221",
        "smhc", "maxdewald", "kiwipaulrob", "humbledude", "crabiosa", "ZeroOttantotto", "subhashhhhhh", "rusty4444",
        "lamahuissn-web", "danno131313", "Aquillum", "palominoz",
    )

    val supporters = listOf("dimitar-gif")

    fun profile(login: String) = "https://github.com/$login"
}

/** Settings › Support Kyllene: a star, the two ways to give, and the people behind the app. */
@Composable
internal fun SupportSection(onOpenCredits: () -> Unit) {
    val open = rememberSupportLink()
    Section(stringResource(Res.string.settings_section_support)) {
        NavRow(
            title = stringResource(Res.string.settings_support_star),
            detail = stringResource(Res.string.settings_support_star_detail),
            icon = Lucide.Star,
            onClick = { open(REPO_URL) },
        )
        Divider()
        NavRow(
            title = stringResource(Res.string.settings_support_kofi),
            detail = stringResource(Res.string.settings_support_kofi_detail),
            icon = Lucide.Coffee,
            onClick = { open(KOFI_URL) },
        )
        Divider()
        NavRow(
            title = stringResource(Res.string.settings_support_sponsors),
            detail = stringResource(Res.string.settings_support_sponsors_detail),
            icon = Lucide.Heart,
            onClick = { open(SPONSORS_URL) },
        )
        Divider()
        NavRow(
            title = stringResource(Res.string.settings_credits),
            detail = stringResource(Res.string.settings_credits_detail),
            icon = Lucide.Users,
            onClick = onOpenCredits,
        )
    }
}

/** Settings › Contributors and supporters: everyone in [Credits]; a tap opens their GitHub profile. */
@Composable
fun CreditsScreen(onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    CreditsView(onBack = onBack)
}

/** The page itself, without the back handler, so previews can draw it. */
@Composable
internal fun CreditsView(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val openSupport = rememberSupportLink()
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader(stringResource(Res.string.settings_credits), onBack = onBack)
            // Over Settings, whose desktop list of sections must not pick up this page's.
            CompositionLocalProvider(LocalSectionAnchors provides null, LocalSettingsQuery provides "") {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        stringResource(Res.string.settings_credits_intro),
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    People(stringResource(Res.string.settings_credits_maintainers), Credits.maintainers) { uriHandler.openUri(Credits.profile(it)) }
                    People(stringResource(Res.string.settings_credits_supporters), Credits.supporters) { uriHandler.openUri(Credits.profile(it)) }
                    People(stringResource(Res.string.settings_credits_contributors), Credits.contributors) { uriHandler.openUri(Credits.profile(it)) }
                    Section(stringResource(Res.string.settings_credits_more)) {
                        NavRow(
                            title = stringResource(Res.string.settings_support_kofi),
                            detail = stringResource(Res.string.settings_credits_join_detail),
                            icon = Lucide.Coffee,
                            onClick = { openSupport(KOFI_URL) },
                        )
                        Divider()
                        NavRow(
                            title = stringResource(Res.string.settings_credits_all),
                            detail = stringResource(Res.string.settings_credits_all_detail),
                            icon = Lucide.Users,
                            onClick = { uriHandler.openUri("$REPO_URL/graphs/contributors") },
                        )
                    }
                }
            }
        }
    }
}

/** Opens a star or giving link, and stops the sidebar card asking, since this person already answered it. */
@Composable
private fun rememberSupportLink(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val offer = LocalSupportOffer.current
    return { url ->
        uriHandler.openUri(url)
        offer.onDismiss()
    }
}

/** A heading and one row per login. */
@Composable
private fun People(title: String, logins: List<String>, onOpen: (String) -> Unit) {
    if (logins.isEmpty()) return
    Section(title) {
        logins.forEachIndexed { i, login ->
            if (i > 0) Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .handCursor()
                    .clickable(role = Role.Button, onClick = { onOpen(login) })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(login, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                UnstyledIcon(Lucide.ExternalLink, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
        }
    }
}
