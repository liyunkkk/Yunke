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
        // 没有 backdrop（浮窗 / 设备不支持 RuntimeShader）时的液态玻璃降级：
        // 半透明渐变叠 surface 基色，透出背后的壁纸 / 内容，保持通透玻璃感，
        // 而不是一块不透明白板（与参考图一致：玻璃下方能看到模糊壁纸）。
        // 奶白玻璃：上端更亮、下端略暗，整体接近不透明白但保留一点透色；
        // surfaceAlpha 由调用方按用途给（卡片 0.86 偏实、胶囊 0.52 更透）。
        Modifier.background(
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = surfaceAlpha).compositeOver(surfaceColor.copy(alpha = 0.10f)),
                    Color.White.copy(alpha = (surfaceAlpha - 0.10f).coerceAtLeast(0.28f))
                        .compositeOver(surfaceColor.copy(alpha = 0.10f)),
                ),
            ),
            shape = shape,
        )
    }
}

@Composable
internal fun Modifier.voiceGlassRim(
    shape: Shape,
    alpha: Float = 0.30f,
    width: Dp = 1.dp,
): Modifier {
    val onSurfaceTint = MiuixTheme.colorScheme.onSurface.copy(alpha = RimOnSurfaceAlpha)
    val rimBrush = Brush.verticalGradient(
        0.0f to Color.White.copy(alpha = alpha).compositeOver(onSurfaceTint),
        1.0f to Color.White.copy(alpha = alpha * 0.25f).compositeOver(onSurfaceTint),
    )
    return border(
        width = width,
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

/** 语音态统一的液态玻璃不透明度：卡片与胶囊必须同档，否则视觉会"两个材质"。 */
internal const val VoiceGlassSurfaceAlpha = 0.55f

/** 语音态统一的边缘高光强度。 */
internal const val VoiceGlassRimAlpha = 0.78f
