package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** The file callbacks and preview cache for one bound gateway, profile and conversation. */
internal data class WidgetFiles(
    val identity: Any,
    val load: suspend (GatewayFile) -> FileLoad,
    val download: suspend (GatewayFile) -> FileDownload,
    val cache: WidgetPreviewCache = WidgetPreviewCache(),
)

internal data class WidgetPreviewKey(val messageKey: String, val file: GatewayFile)

internal class WidgetPreviewCache(private val capacity: Int = 12) {
    class Preview(val document: FileText) {
        var measurement: Measurement? = null
    }

    data class Measurement(val width: Dp, val theme: WidgetTheme, val height: Int)

    private val recent = LinkedHashMap<WidgetPreviewKey, Preview>()

    operator fun get(key: WidgetPreviewKey): Preview? =
        recent.remove(key)?.also { recent[key] = it }

    fun store(key: WidgetPreviewKey, document: FileText): Preview {
        recent.remove(key)
        val preview = Preview(document)
        recent[key] = preview
        if (recent.size > capacity) recent.remove(recent.keys.first())
        return preview
    }

    fun remove(key: WidgetPreviewKey) {
        recent.remove(key)
    }
}

internal val LocalWidgetFiles = staticCompositionLocalOf<WidgetFiles?> { null }

internal suspend fun downloadWidget(files: WidgetFiles, file: GatewayFile): FileDownload = try {
    files.download(file)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    FileDownload.Failed(FileProblem.WidgetNotFetched)
}

internal suspend fun loadWidget(files: WidgetFiles, file: GatewayFile): FileLoad = try {
    val loaded = withTimeoutOrNull(20_000) { files.load(file) } ?: FileLoad.Failed(FileProblem.WidgetSlow)
    if (loaded is FileLoad.Ready) widgetProblem(loaded.text)?.let { FileLoad.Failed(it) } ?: loaded
    else if (loaded is FileLoad.Failed) loaded else FileLoad.Failed(FileProblem.WidgetUnreadable)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    FileLoad.Failed(FileProblem.WidgetNotLoaded)
}

@Composable
internal fun InlineWidget(item: ReplyMedia, file: GatewayFile, files: WidgetFiles, messageKey: String) {
    val previewKey = WidgetPreviewKey(messageKey, file)
    key(files.identity, files.cache, previewKey) { WidgetFrame(item, file, files, previewKey) }
}

@Composable
private fun WidgetFrame(item: ReplyMedia, file: GatewayFile, files: WidgetFiles, previewKey: WidgetPreviewKey) {
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember(attempt) { mutableStateOf<String?>(null) }
    var preview by remember(attempt) { mutableStateOf(files.cache[previewKey]) }
    val loadProblem by key(attempt) {
        produceState<FileProblem?>(null) {
            if (preview == null) {
                when (val loaded = loadWidget(files, file)) {
                    is FileLoad.Ready -> preview = files.cache.store(previewKey, loaded.text)
                    is FileLoad.Failed -> value = loaded.problem
                    else -> value = FileProblem.WidgetUnreadable
                }
            }
        }
    }
    fun css(color: Color) = cssColor(color.toArgb())
    val theme = WidgetTheme(
        css(Theme[colors][text]), css(Theme[colors][textTertiary]), css(Theme[colors][accent]),
        css(Theme[colors][stroke]), css(Theme[colors][surface]), Theme[colors][background].luminance() < 0.5f,
        Theme[typography][bodySmall].fontSize.value * LocalDensity.current.fontScale,
    )
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier.widthIn(max = READING_WIDTH).fillMaxWidth()
            .clip(shape).border(1.dp, Theme[colors][stroke], shape).background(Theme[colors][surface]),
    ) {
        ReplyFile(item, download = { downloadWidget(files, file) }, insidePreview = true)
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
        val current = preview
        when {
            current != null && failed == null -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = maxWidth
                val sizing = remember(current, theme, width) { WidgetHeightTracker() }
                var measured by remember(sizing) {
                    mutableStateOf(current.measurement?.takeIf { it.width == width && it.theme == theme }?.height)
                }
                val height = measured?.dp?.coerceIn(160.dp, 640.dp)
                    ?: (width * 0.625f).coerceIn(280.dp, 640.dp)
                WidgetWebView(
                    current.document.text, theme, item.name,
                    Modifier.fillMaxWidth().height(height),
                    onReport = { report ->
                        sizing.measuredHeight(report)?.let {
                            measured = it
                            current.measurement = WidgetPreviewCache.Measurement(width, theme, it)
                        }
                    },
                    onFailure = { failed = it },
                )
            }
            current == null && loadProblem == null ->
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
        val problem = loadProblem?.let { fileProblemText(it) } ?: failed
        if (problem != null) {
            Text(stringResource(if (isDesktop) Res.string.chat_widget_retry_click else Res.string.chat_widget_retry_tap, problem), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary],
                modifier = Modifier.fillMaxWidth().handCursor().clickable {
                    files.cache.remove(previewKey)
                    attempt++
                }.padding(12.dp))
        }
    }
}

@Composable
internal expect fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onReport: (WidgetReport) -> Unit, onFailure: (String) -> Unit)
