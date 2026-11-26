package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import kotlin.math.ceil

internal val TranscriptControlRim = 12.dp

/** Blur a narrow area of the transcript around the control, following its own shape. */
@Composable
internal fun TranscriptControlBackdrop(
    shape: Shape, haze: HazeState?, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (haze != null) {
            val page = Theme[colors][background]
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val style = remember(page, shape, density, direction) {
                val feather = featheredRimBrush(shape, density, direction)
                HazeBlurStyle {
                    blurEnabled(true)
                    blurRadius(12.dp)
                    backgroundColor(page)
                    progressive(HazeProgressive.Brush(feather))
                    mask(feather)
                }
            }
            Box(Modifier.matchParentSize().clip(shape).hazeBlur(input = HazeInput.Sources(haze), style = style))
            Box(Modifier.padding(TranscriptControlRim)) { content() }
        } else {
            content()
        }
    }
}

/** The blur fades smoothly from full at the button edge to transparent at the rim's outside. */
private fun featheredRimBrush(shape: Shape, density: Density, direction: LayoutDirection) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val bitmap = ImageBitmap(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val feather = with(density) { TranscriptControlRim.toPx() }.coerceAtMost(size.minDimension / 2)
        val innerSize = Size(size.width - 2 * feather, size.height - 2 * feather)
        val outline = shape.createOutline(innerSize, direction, density)
        val button = (outline as? Outline.Rounded)?.roundRect ?: RoundRect(Rect(Offset.Zero, innerSize), CornerRadius.Zero)
        val steps = ceil(feather).toInt().coerceAtLeast(1)
        var previousAlpha = 0f
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val inset = (step - 0.5f) * feather / steps
            val alpha = t * t * (3 - 2 * t)
            // Each inset fill is composited over the previous fill, which already has some alpha.
            paint.color = Color.White.copy(alpha = ((alpha - previousAlpha) / (1 - previousAlpha)).coerceIn(0f, 1f))
            // Expand each corner from the button's edge, so the feather follows the button's outline.
            val spread = feather - inset
            fun CornerRadius.outset() = CornerRadius(x + spread, y + spread)
            canvas.drawOutline(
                Outline.Rounded(RoundRect(
                    inset, inset, size.width - inset, size.height - inset,
                    button.topLeftCornerRadius.outset(), button.topRightCornerRadius.outset(),
                    button.bottomRightCornerRadius.outset(), button.bottomLeftCornerRadius.outset(),
                )),
                paint,
            )
            previousAlpha = alpha
        }
        return ImageShader(bitmap)
    }
}
