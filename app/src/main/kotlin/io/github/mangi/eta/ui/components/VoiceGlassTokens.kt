/**
 * 语音态液态玻璃材质原语；不参与业务逻辑。
 *
 * voiceGlassRim 是 @Composable 扩展。
 */
package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val RimOnSurfaceAlpha = 0.08f

@Composable
internal fun rememberVoiceGlassBackdrop(): LayerBackdrop? {
    if (!LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

internal fun Modifier.voiceGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    blurRadius: Float = 28f,
    surfaceAlpha: Float = 0.62f,
): Modifier = composed {
    val surfaceColor = MiuixTheme.colorScheme.surface
    if (backdrop != null) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(listOf(BlendColorEntry(surfaceColor.copy(alpha = surfaceAlpha)))),
        )
    } else {
        Modifier.background(
            color = surfaceColor.copy(alpha = surfaceAlpha.coerceAtLeast(0.85f)),
            shape = shape,
        )
    }
}

@Composable
internal fun Modifier.voiceGlassRim(
    shape: Shape,
    alpha: Float = 0.30f,
): Modifier {
    val onSurfaceTint = MiuixTheme.colorScheme.onSurface.copy(alpha = RimOnSurfaceAlpha)
    val rimBrush = Brush.verticalGradient(
        0.0f to Color.White.copy(alpha = alpha).compositeOver(onSurfaceTint),
        1.0f to Color.White.copy(alpha = alpha * 0.25f).compositeOver(onSurfaceTint),
    )
    return border(
        width = 1.dp,
        brush = rimBrush,
        shape = shape,
    )
}

internal fun Modifier.voiceGlassShadow(
    shape: Shape,
    elevation: Dp = 14.dp,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.18f),
    spotColor = Color.Black.copy(alpha = 0.22f),
)

internal val VoiceGlassCorner: Dp = 26.dp
