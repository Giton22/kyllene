package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.fullScreenDialogProperties
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.chat_close
import dev.hermeskotlin.ui.resources.common_back
import org.jetbrains.compose.resources.stringResource

internal class TranscriptPreview(val title: String, val content: @Composable () -> Unit)

internal val LocalOpenTranscriptPreview = staticCompositionLocalOf<((TranscriptPreview) -> Unit)?> { null }
internal val LocalTranscriptInlineBudget = staticCompositionLocalOf { 0.dp }

internal fun transcriptInlineBudget(viewport: Dp): Dp = viewport.coerceAtLeast(0.dp) * 0.4f

@Composable
internal fun AdaptiveTranscriptDisclosure(
    icon: @Composable () -> Unit,
    label: String,
    disclosureKey: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val openPreview = LocalOpenTranscriptPreview.current
    val budget = LocalTranscriptInlineBudget.current
    var inline by rememberSaveable(disclosureKey) { mutableStateOf<Boolean?>(null) }
    if (openPreview == null || budget <= 0.dp) {
        val expanded = inline ?: initiallyOpen
        Disclosure(icon, label, expanded, { inline = !expanded }, content = content)
        return
    }

    val previewBody by rememberUpdatedState(content)
    SubcomposeLayout { constraints ->
        val gap = 8.dp.roundToPx()
        val threshold = budget.roundToPx()
        // Measure the real content at chat width. A closed row only needs one pixel over budget.
        val bodyLimit = if (inline == true) constraints.maxHeight else minOf(constraints.maxHeight, (threshold - gap + 1).coerceAtLeast(1))
        val body = subcompose("body") {
            val shape = RoundedCornerShape(Theme[radii][radiusMedium])
            Box(
                Modifier.fillMaxWidth().border(1.dp, Theme[colors][stroke], shape)
                    .background(Theme[colors][surface], shape).padding(12.dp),
            ) { content() }
        }.single().measure(constraints.copy(minHeight = 0, maxHeight = bodyLimit))
        val usePreview = body.height + gap > threshold
        val expanded = inline ?: (initiallyOpen && !usePreview)
        val header = subcompose("header") {
            SideEffect { if (inline == null) inline = expanded }
            Disclosure(
                icon, label, expanded,
                onToggle = {
                    if (!expanded && usePreview) openPreview(TranscriptPreview(label) { previewBody() })
                    else inline = !expanded
                },
                opensPreview = !expanded && usePreview,
            )
        }.single().measure(constraints.copy(minHeight = 0))
        layout(
            constraints.constrainWidth(maxOf(header.width, if (expanded) body.width else 0)),
            constraints.constrainHeight(header.height + if (expanded) gap + body.height else 0),
        ) {
            header.placeRelative(0, 0)
            if (expanded) body.placeRelative(0, header.height + gap)
        }
    }
}

@Composable
internal fun TranscriptPreviewViewer(preview: TranscriptPreview, docked: Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    PlatformBackHandler(enabled = docked, onBack = onDismiss)
    LaunchedEffect(docked) { if (docked) focus.requestFocus() }
    val page = @Composable {
        Column(
            Modifier.fillMaxSize().background(Theme[colors][background])
                .then(if (docked) Modifier.focusRequester(focus).focusable() else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
        ) {
            Row(
                Modifier.fillMaxWidth().clearOfWindowControls().padding(start = if (docked) 16.dp else 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!docked) IconButton(Lucide.ArrowLeft, stringResource(Res.string.common_back), onDismiss)
                Text(preview.title, modifier = Modifier.weight(1f), style = Theme[typography][title], color = Theme[colors][text])
                if (docked) IconButton(Lucide.X, stringResource(Res.string.chat_close), onDismiss)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScrollWithBar().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Keep any nested disclosures in the preview the reader already opened.
                CompositionLocalProvider(LocalOpenTranscriptPreview provides null) { preview.content() }
            }
        }
    }
    if (docked) page() else Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) { page() }
}
