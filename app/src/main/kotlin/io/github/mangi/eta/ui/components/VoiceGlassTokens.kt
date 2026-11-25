/**
 * 液态玻璃（Liquid Glass）视觉材质与极光声波原语；纯视觉层，不参与业务逻辑。
 *
 * 兼容两类宿主环境：
 * 1. 应用内页面（backdrop != null）：调用 miuix `textureBlur` 实时采样底层画面，并叠加顶部镜面高光与底部极光漫反射。
 * 2. 系统浮窗（backdrop == null）：依靠 Android 12+ 窗口级 `blurBehindRadius` 透出真实桌面模糊，
 *    叠加冰晶（浅色）/ 暗夜烟熏（深色）半透明渐变、顶部镜面折射光带与双层亮边。
 */
package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import kotlin.math.PI
import kotlin.math.sin
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LiquidGlassAuroraViolet = Color(0xFF6C4CF6)
internal val LiquidGlassAuroraVioletDark = Color(0xFFB49BFF)
internal val LiquidGlassAuroraCyan = Color(0xFF38BDF8)
internal val LiquidGlassAuroraPink = Color(0xFFEC4899)
internal val LiquidGlassStopRose = Color(0xFFE11D48)
internal val LiquidGlassStopRoseLight = Color(0xFFFB7185)

@Composable
internal fun isLiquidGlassDark(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f

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
    val dark = isLiquidGlassDark()
    val baseGlass = if (backdrop != null) {
        val blendTint = if (dark) {
            Color(0xFF161B2E).copy(alpha = (surfaceAlpha + 0.08f).coerceAtMost(0.82f))
        } else {
            Color(0xFFF9FAFF).copy(alpha = surfaceAlpha.coerceAtMost(0.76f))
        }
        Modifier
            .clip(shape)
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = blurRadius,
                colors = BlurColors(listOf(BlendColorEntry(blendTint))),
            )
    } else {
        val topAlpha = (surfaceAlpha + if (dark) 0.16f else 0.14f).coerceIn(0.32f, 0.88f)
        val midAlpha = surfaceAlpha.coerceIn(0.26f, 0.80f)
        val botAlpha = (surfaceAlpha + if (dark) 0.10f else 0.04f).coerceIn(0.30f, 0.84f)
        val fillBrush = if (dark) {
            Brush.verticalGradient(
                0.0f to Color(0xFF212840).copy(alpha = topAlpha),
                0.55f to Color(0xFF141828).copy(alpha = midAlpha),
                1.0f to Color(0xFF231F3D).copy(alpha = botAlpha),
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color(0xFFFFFFFF).copy(alpha = topAlpha),
                0.55f to Color(0xFFF6F8FF).copy(alpha = midAlpha),
                1.0f to Color(0xFFEDE8FF).copy(alpha = botAlpha),
            )
        }
        Modifier
            .clip(shape)
            .background(brush = fillBrush, shape = shape)
    }

    // 镜面高光层 + 底部极光漫反射：纯绘制层，不增加布局节点。
    baseGlass.drawWithContent {
        drawContent()
        val topBandHeight = (size.height * 0.42f).coerceAtLeast(10f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.14f else 0.34f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = topBandHeight,
            ),
            size = Size(size.width, topBandHeight),
        )
        val bottomBandHeight = (size.height * 0.28f).coerceAtLeast(8f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    (if (dark) Color(0xFFA78BFA) else Color(0xFF8B6CF6)).copy(
                        alpha = if (dark) 0.12f else 0.08f,
                    ),
                ),
                startY = size.height - bottomBandHeight,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - bottomBandHeight),
            size = Size(size.width, bottomBandHeight),
        )
    }
}

/**
 * 独立液态玻璃药丸 / 卡片 / 胶囊表面（支持可选柔彩着色，如激活态会话胶囊、柔红停止钮等）。
 */
internal fun Modifier.liquidGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop? = null,
    surfaceAlpha: Float = 0.64f,
    rimAlpha: Float = VoiceGlassRimAlpha,
    rimWidth: Dp = 1.1.dp,
    elevation: Dp = 10.dp,
    accentTint: Color? = null,
    accentAlpha: Float = 0.16f,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    val shadowMod = if (elevation > 0.dp) {
        Modifier.voiceGlassShadow(shape = shape, elevation = elevation)
    } else {
        Modifier
    }
    val tintMod = if (accentTint != null) {
        Modifier.background(
            brush = Brush.linearGradient(
                colors = listOf(
                    accentTint.copy(alpha = if (dark) (accentAlpha * 1.45f).coerceAtMost(0.42f) else accentAlpha),
                    accentTint.copy(alpha = if (dark) (accentAlpha * 0.9f).coerceAtMost(0.28f) else accentAlpha * 0.55f),
                ),
            ),
            shape = shape,
        )
    } else {
        Modifier
    }
    shadowMod
        .voiceGlass(backdrop = backdrop, shape = shape, surfaceAlpha = surfaceAlpha)
        .then(tintMod)
        .voiceGlassRim(shape = shape, alpha = rimAlpha, width = rimWidth)
}

/**
 * 液态玻璃圆形操作按钮（+ 附件、消息、键盘、柔红停止/暂停圆钮等）。
 */
internal fun Modifier.liquidGlassCircle(
    tint: Color? = null,
    tintAlpha: Float = 0.20f,
    rimWidth: Dp = 1.dp,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    val baseFill = if (tint != null) {
        Brush.verticalGradient(
            listOf(
                tint.copy(alpha = if (dark) (tintAlpha * 1.35f).coerceAtMost(0.44f) else (tintAlpha + 0.10f).coerceAtMost(0.36f)),
                tint.copy(alpha = if (dark) (tintAlpha * 0.85f).coerceAtMost(0.28f) else tintAlpha * 0.65f),
            ),
        )
    } else if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.08f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.82f),
                Color(0xFFF1EEFF).copy(alpha = 0.54f),
            ),
        )
    }
    val borderBrush = if (tint != null) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.42f else 0.86f),
                tint.copy(alpha = if (dark) 0.36f else 0.30f),
            ),
        )
    } else if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.34f),
                Color(0xFFA78BFA).copy(alpha = 0.18f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.94f),
                Color(0xFFDCD6FF).copy(alpha = 0.58f),
            ),
        )
    }
    Modifier
        .clip(CircleShape)
        .background(brush = baseFill, shape = CircleShape)
        .border(width = rimWidth, brush = borderBrush, shape = CircleShape)
}

@Composable
internal fun Modifier.voiceGlassRim(
    shape: Shape,
    alpha: Float = 0.30f,
    width: Dp = 1.dp,
): Modifier {
    val dark = isLiquidGlassDark()
    val rimBrush = if (dark) {
        Brush.verticalGradient(
            0.0f to Color(0xFFF3F0FF).copy(alpha = (alpha * 0.44f).coerceIn(0.16f, 0.46f)),
            0.45f to Color.White.copy(alpha = (alpha * 0.22f).coerceIn(0.08f, 0.24f)),
            1.0f to Color(0xFFA78BFA).copy(alpha = (alpha * 0.24f).coerceIn(0.10f, 0.26f)),
        )
    } else {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = (alpha + 0.12f).coerceAtMost(0.96f)),
            0.55f to Color.White.copy(alpha = (alpha * 0.62f).coerceAtMost(0.68f)),
            1.0f to Color(0xFFDAD2FF).copy(alpha = (alpha * 0.56f).coerceAtMost(0.58f)),
        )
    }
    return border(
        width = width,
        brush = rimBrush,
        shape = shape,
    )
}

internal fun Modifier.voiceGlassShadow(
    shape: Shape,
    elevation: Dp = 14.dp,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0xFF0A1026).copy(alpha = if (dark) 0.42f else 0.14f),
        spotColor = Color(0xFF0A1026).copy(alpha = if (dark) 0.48f else 0.18f),
    )
}

/**
 * 第一版标志性「极光流体声波球」：青-紫-粉液态光泽 + 顶部弧面玻璃反光 + 亮白折射环 + 5 条动态声纹。
 */
@Composable
internal fun AuroraVoiceOrb(
    size: Dp,
    active: Boolean,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "aurora_orb_wave")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "aurora_orb_phase",
        )
        animated
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                // 柔和外发光晕染
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LiquidGlassAuroraViolet.copy(alpha = if (active) 0.48f else 0.34f),
                            LiquidGlassAuroraCyan.copy(alpha = if (active) 0.22f else 0.12f),
                            Color.Transparent,
                        ),
                        radius = this.size.minDimension * 0.78f,
                    ),
                    radius = this.size.minDimension * 0.78f,
                )
            }
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        LiquidGlassAuroraCyan,
                        LiquidGlassAuroraViolet,
                        LiquidGlassAuroraPink,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            .drawWithContent {
                drawContent()
                // 顶部玻璃弧面高光
                drawOval(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color.Transparent,
                        ),
                        startY = this.size.height * 0.05f,
                        endY = this.size.height * 0.48f,
                    ),
                    topLeft = Offset(this.size.width * 0.18f, this.size.height * 0.06f),
                    size = Size(this.size.width * 0.64f, this.size.height * 0.38f),
                )
            }
            .border(
                width = 1.4.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.92f),
                        Color.White.copy(alpha = 0.48f),
                    ),
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val baseHeights = floatArrayOf(0.24f, 0.48f, 0.66f, 0.42f, 0.24f)
        val clampedLevel = level.coerceIn(0f, 1f)
        Canvas(modifier = Modifier.size(size * 0.56f)) {
            val count = baseHeights.size
            val barWidth = (this.size.width / (count * 2f - 0.8f)).coerceAtLeast(2.2.dp.toPx())
            val gap = barWidth * 0.92f
            val totalW = count * barWidth + (count - 1) * gap
            val startX = (this.size.width - totalW) / 2f
            val cy = this.size.height / 2f
            for (i in 0 until count) {
                val mod = if (active) {
                    val wave = 0.5f + 0.5f * sin(phase + i * 1.05f)
                    val boost = 0.22f + clampedLevel * 0.45f
                    (baseHeights[i] * (0.76f + wave * boost)).coerceIn(0.20f, 0.94f)
                } else {
                    baseHeights[i]
                }
                val h = this.size.height * mod
                val x = startX + i * (barWidth + gap)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, cy - h / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}

/**
 * 第一版「多频极光呼吸声纹」：替代语音态空白期死板的 4 条灰骨架线。
 */
@Composable
internal fun LiquidGlassWaveform(
    active: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val dark = isLiquidGlassDark()
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "liquid_glass_waveform")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "liquid_glass_waveform_phase",
        )
        animated
    } else {
        0f
    }

    val weights = floatArrayOf(
        0.18f, 0.30f, 0.50f, 0.38f, 0.68f, 0.90f, 0.58f, 1.00f,
        0.74f, 0.44f, 0.84f, 0.62f, 0.36f, 0.52f, 0.28f, 0.18f,
    )
    val clampedLevel = level.coerceIn(0f, 1f)
    val startColor = if (dark) Color(0xFF818CF8) else Color(0xFF5B45F2)
    val midColor = if (dark) Color(0xFFC4B5FD) else Color(0xFF7C5CFA)
    val endColor = if (dark) Color(0xFF67E8F9) else Color(0xFF38BDF8)

    Canvas(modifier = modifier) {
        val count = weights.size
        val barWidth = 3.dp.toPx()
        val gap = ((size.width - count * barWidth) / (count - 1).coerceAtLeast(1))
            .coerceIn(3.dp.toPx(), 6.5.dp.toPx())
        val totalW = count * barWidth + (count - 1) * gap
        val startX = (size.width - totalW) / 2f
        val cy = size.height / 2f
        val barBrush = Brush.horizontalGradient(
            colors = listOf(startColor, midColor, endColor),
            startX = startX,
            endX = startX + totalW,
        )
        for (i in 0 until count) {
            val fraction = if (active) {
                val wave = 0.5f + 0.5f * sin(phase + i * 0.62f)
                val dynamicGain = 0.48f + clampedLevel * 0.48f
                (weights[i] * (0.42f + wave * dynamicGain)).coerceIn(0.14f, 0.98f)
            } else {
                (weights[i] * 0.42f).coerceIn(0.14f, 0.46f)
            }
            val barH = size.height * fraction
            val x = startX + i * (barWidth + gap)
            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, cy - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

internal val VoiceGlassCorner: Dp = 28.dp

/** 语音态统一的液态玻璃不透明度：卡片与胶囊保持同档材质。 */
internal const val VoiceGlassSurfaceAlpha = 0.58f

/** 语音态统一的边缘高光强度。 */
internal const val VoiceGlassRimAlpha = 0.82f
