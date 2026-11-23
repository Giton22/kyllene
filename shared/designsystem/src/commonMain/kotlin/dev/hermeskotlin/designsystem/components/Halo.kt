package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.colors

/**
 * The one soft blue glow behind the top of a screen, brightest near its top-left corner and gone by
 * [HALO_REACH] down, however wide the screen. Every screen's ground carries one; nothing else on it is a gradient.
 */
@Composable
fun Modifier.halo(): Modifier {
    // Off, every screen's ground is one flat color.
    if (!LocalGlow.current) return this
    val glow = Theme[colors][accentSoft]
    return drawWithCache {
        if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind {}
        // The glow fades out at FADE of the radius; capping the radius keeps that within the reach.
        val radius = minOf(size.width * 0.8f, HALO_REACH.toPx() / FADE)
        if (radius <= 0f) return@drawWithCache onDrawBehind {}
        val brush = Brush.radialGradient(0f to glow, FADE to Color.Transparent, center = Offset(size.width * 0.15f, 0f), radius = radius)
        onDrawBehind { drawRect(brush) }
    }
}

private const val FADE = 0.7f
private val HALO_REACH = 260.dp
