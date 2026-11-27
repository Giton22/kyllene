package dev.hermeskotlin.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Coffee
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.update.SupportNotice
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.settings.KOFI_URL
import dev.hermeskotlin.ui.settings.REPO_URL
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Whether to ask for a star or a tip, and a way to stop asking. */
class SupportOffer(val show: Boolean, val onDismiss: () -> Unit)

val LocalSupportOffer = compositionLocalOf { SupportOffer(false) {} }

/** Asks once per launch whether the support card is due (see [SupportNotice]). */
@Composable
fun rememberSupportOffer(): SupportOffer {
    val notice = koinInject<SupportNotice>()
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = notice.pending() }
    return SupportOffer(show) {
        show = false
        scope.launch { notice.dismiss() }
    }
}

/** A card asking, once, for a star on GitHub or a tip on Ko-fi. Either link, or the close button, ends it. */
@Composable
fun SupportBanner(modifier: Modifier = Modifier) {
    val offer = LocalSupportOffer.current
    if (!offer.show) return
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val tint = Theme[colors][accent]
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .background(tint.copy(alpha = 0.08f), shape)
            .padding(start = 12.dp, top = 10.dp, bottom = 12.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.support_card_title), style = Theme[typography][bodySmall], color = Theme[colors][text])
                Text(stringResource(Res.string.support_card_detail), style = Theme[typography][caption], color = Theme[colors][textSecondary])
            }
            UnstyledButton(onClick = offer.onDismiss, modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall]))) {
                UnstyledIcon(Lucide.X, contentDescription = stringResource(Res.string.common_not_now), tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
        }
        FlowRow(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                stringResource(Res.string.support_card_star),
                accessibleLabel = stringResource(Res.string.settings_support_star),
                onClick = {
                    uriHandler.openUri(REPO_URL)
                    offer.onDismiss()
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = Lucide.Star,
            )
            Button(
                stringResource(Res.string.settings_support_kofi),
                onClick = {
                    uriHandler.openUri(KOFI_URL)
                    offer.onDismiss()
                },
                size = ButtonSize.Small,
                leadingIcon = Lucide.Coffee,
            )
        }
    }
}
