/**
 * iOS 26 极简无色液态玻璃（Colorless Liquid Glass V2）视觉材质与浅色 Siri 珍珠晶球原语；纯视觉层，不参与业务逻辑。
 *
 * 兼容两类宿主环境：
 * 1. 应用内页面（backdrop != null）：调用 miuix `textureBlur` 实时采样底层画面，并叠加无色镜面高光与 3D 折射亮边。
 * 2. 系统浮窗（backdrop == null）：依靠 Android 12+ 窗口级 `blurBehindRadius` 透出真实桌面模糊，
 *    叠加 100% 无色光学透光玻璃（浅色晶透白 / 深色中性烟熏灰），零紫/蓝/彩虹色污染。
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
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 极简黑白墨色体系（无色玻璃上最耐看的高对比度字色）。 */
internal val LiquidGlassInkLight = Color(0xFF14171F)
internal val LiquidGlassInkDark = Color(0xFFF4F6FA)
internal val LiquidGlassInkSecondaryLight = Color(0xB3282E3A)
internal val LiquidGlassInkSecondaryDark = Color(0xB8D2D8E4)

/** 极克制语义停止红（仅用于语音停止小方块核心，绝不染脏玻璃底面）。 */
internal val LiquidGlassStopRedLight = Color(0xFFD92D20)
internal val LiquidGlassStopRedDark = Color(0xFFFF453A)

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

/**
 * 纯无色光学液态玻璃底面：
 * - 浅色态：纯白高透阶梯渐变 + 顶部柔和镜面高光，透出真实背景色彩，零发灰/发紫；
 * - 深色态：纯中性烟熏碳灰透光，零靛蓝/紫雾。
 */
internal fun Modifier.voiceGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    blurRadius: Float = 28f,
    surfaceAlpha: Float = 0.48f,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    val baseGlass = if (backdrop != null) {
        val blendTint = if (dark) {
            Color(0xFF17191E).copy(alpha = (surfaceAlpha + 0.08f).coerceAtMost(0.76f))
        } else {
            Color.White.copy(alpha = surfaceAlpha.coerceAtMost(0.56f))
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
        val fillBrush = if (dark) {
            val topAlpha = (surfaceAlpha + 0.12f).coerceIn(0.36f, 0.78f)
            val midAlpha = surfaceAlpha.coerceIn(0.28f, 0.68f)
            val botAlpha = (surfaceAlpha + 0.06f).coerceIn(0.32f, 0.72f)
            Brush.linearGradient(
                0.0f to Color(0xFF282A30).copy(alpha = topAlpha),
                0.52f to Color(0xFF17191E).copy(alpha = midAlpha),
                1.0f to Color(0xFF212329).copy(alpha = botAlpha),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
        } else {
            val topAlpha = (surfaceAlpha + 0.14f).coerceIn(0.36f, 0.72f)
            val midAlpha = (surfaceAlpha - 0.14f).coerceIn(0.22f, 0.48f)
            val botAlpha = (surfaceAlpha + 0.02f).coerceIn(0.28f, 0.58f)
            Brush.linearGradient(
                0.0f to Color.White.copy(alpha = topAlpha),
                0.48f to Color.White.copy(alpha = midAlpha),
                1.0f to Color.White.copy(alpha = botAlpha),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
        }
        Modifier
            .clip(shape)
            .background(brush = fillBrush, shape = shape)
    }

    // 纯光学无色镜面高光层 + 极微底部反光，无任何彩色染雾。
    baseGlass.drawWithContent {
        drawContent()
        val topBandHeight = (size.height * 0.36f).coerceAtLeast(8f)
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
        val bottomBandHeight = (size.height * 0.18f).coerceAtLeast(6f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = if (dark) 0.04f else 0.14f),
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
 * 独立无色液态玻璃药丸 / 卡片 / 胶囊表面。
 */
internal fun Modifier.liquidGlassSurface(
    shape: Shape,
    backdrop: LayerBackdrop? = null,
    surfaceAlpha: Float = 0.46f,
    rimAlpha: Float = VoiceGlassRimAlpha,
    rimWidth: Dp = 1.05.dp,
    elevation: Dp = 10.dp,
): Modifier = composed {
    val shadowMod = if (elevation > 0.dp) {
        Modifier.voiceGlassShadow(shape = shape, elevation = elevation)
    } else {
        Modifier
    }
    shadowMod
        .voiceGlass(backdrop = backdrop, shape = shape, surfaceAlpha = surfaceAlpha)
        .voiceGlassRim(shape = shape, alpha = rimAlpha, width = rimWidth)
}

/**
 * 无色液态玻璃次级圆形按钮（+ 附件、消息、键盘、语音停止键圆底等）。
 */
internal fun Modifier.liquidGlassCircle(
    rimWidth: Dp = 0.95.dp,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    val baseFill = if (dark) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.07f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.62f),
                Color.White.copy(alpha = 0.30f),
            ),
        )
    }
    val borderBrush = if (dark) {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.34f),
            0.45f to Color.White.copy(alpha = 0.10f),
            1.0f to Color.White.copy(alpha = 0.22f),
        )
    } else {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.94f),
            0.45f to Color.White.copy(alpha = 0.34f),
            1.0f to Color.White.copy(alpha = 0.72f),
        )
    }
    clip(CircleShape)
        .background(brush = baseFill, shape = CircleShape)
        .border(width = rimWidth, brush = borderBrush, shape = CircleShape)
}

/**
 * 浅色 Siri 珍珠晶球主操作圆钮（输入舱发送/继续/停止等主按钮）：
 * 通透凝光珍珠白底 + 顶部玻璃穹顶反光 + 极微冰珠虹彩光泽 + 锐利纯白折射环，
 * 摒弃厚重黑钮与廉价高饱和紫粉渐变，无论浅色或深色玻璃背景都轻盈高级。
 */
internal fun Modifier.liquidGlassPearlActionCircle(
    rimWidth: Dp = 1.2.dp,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    val baseFill = Brush.radialGradient(
        colors = if (dark) {
            listOf(
                Color(0xF5FFFFFF),
                Color(0xE8EEF2F8),
                Color(0xD6DCE3EE),
            )
        } else {
            listOf(
                Color(0xFAFFFFFF),
                Color(0xF0F4F7FC),
                Color(0xDFE4EBF5),
            )
        },
        center = Offset(0f, 0f),
    )
    val rimBrush = Brush.linearGradient(
        0.0f to Color.White.copy(alpha = 0.98f),
        0.48f to Color.White.copy(alpha = 0.56f),
        1.0f to Color.White.copy(alpha = 0.88f),
    )
    shadow(
        elevation = if (dark) 10.dp else 8.dp,
        shape = CircleShape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.34f else 0.12f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.16f),
    )
        .clip(CircleShape)
        .background(brush = baseFill, shape = CircleShape)
        .drawWithContent {
            // 极克制 Siri 浅色珍珠冷光晕（低饱和度 14% 光学折射，而非彩色块）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x24C7E2FF),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.24f, size.height * 0.78f),
                    radius = size.minDimension * 0.65f,
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x1EF5DCF0),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.80f, size.height * 0.26f),
                    radius = size.minDimension * 0.60f,
                ),
            )
            drawContent()
            // 顶部弧面玻璃反光
            drawOval(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.64f),
                        Color.Transparent,
                    ),
                    startY = size.height * 0.04f,
                    endY = size.height * 0.44f,
                ),
                topLeft = Offset(size.width * 0.18f, size.height * 0.05f),
                size = Size(size.width * 0.64f, size.height * 0.36f),
            )
        }
        .border(width = rimWidth, brush = rimBrush, shape = CircleShape)
}

/**
 * 3D 曲面玻璃折射亮边（左上与右下高光，两侧自然收光，零彩色污染）。
 */
@Composable
internal fun Modifier.voiceGlassRim(
    shape: Shape,
    alpha: Float = 0.86f,
    width: Dp = 1.05.dp,
): Modifier {
    val dark = isLiquidGlassDark()
    val scale = alpha.coerceIn(0.25f, 1f)
    val rimBrush = if (dark) {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = (0.40f * scale).coerceAtMost(0.42f)),
            0.36f to Color.White.copy(alpha = (0.12f * scale).coerceAtMost(0.14f)),
            0.66f to Color.White.copy(alpha = (0.06f * scale).coerceAtMost(0.08f)),
            1.0f to Color.White.copy(alpha = (0.24f * scale).coerceAtMost(0.26f)),
        )
    } else {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = (0.98f * scale).coerceAtMost(0.98f)),
            0.36f to Color.White.copy(alpha = (0.36f * scale).coerceAtMost(0.40f)),
            0.66f to Color.White.copy(alpha = (0.18f * scale).coerceAtMost(0.22f)),
            1.0f to Color.White.copy(alpha = (0.78f * scale).coerceAtMost(0.82f)),
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
    elevation: Dp = 12.dp,
): Modifier = composed {
    val dark = isLiquidGlassDark()
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.10f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.46f else 0.14f),
    )
}

/**
 * 浅色 Siri 珍珠晶球声波按钮（LiquidGlassVoiceOrb）：
 * - 通透浅色珍珠白晶体球体，内置随声波缓慢流转的极轻光学珠光（低饱和冰雾/珍珠粉白，绝非廉价高饱和紫蓝粉）；
 * - 顶部玻璃弧面镜面高光 + 锐利纯白折射外环；
 * - 内部 5 条深石墨色高对比几何声波，随 [level] 与 [active] 细腻呼吸律动。
 */
@Composable
internal fun LiquidGlassVoiceOrb(
    size: Dp,
    active: Boolean,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "siri_pearl_orb")
    val orbitPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (active) 2600 else 6400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "siri_pearl_orbit",
    )
    val wavePhase = if (active) {
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1150, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "siri_pearl_wave_phase",
        )
        animated
    } else {
        0f
    }

    val clampedLevel = level.coerceIn(0f, 1f)
    val shimmerBoost = if (active) 0.24f + clampedLevel * 0.14f else 0.14f

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                // 轻盈浅色珍珠光晕（柔和纯白 + 极淡冰雾，无艳色脏光）
                val haloRadius = this.size.minDimension * (if (active) 0.80f + clampedLevel * 0.12f else 0.68f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (active) 0.56f else 0.34f),
                            Color(0xFFCDE5FF).copy(alpha = if (active) 0.20f else 0.08f),
                            Color.Transparent,
                        ),
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                )
            }
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .clip(CircleShape)
            .drawBehind {
                // 1) 凝光珍珠白基底
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFCFFFFFF),
                            Color(0xF2F5F7FC),
                            Color(0xE0E5ECF6),
                        ),
                        center = Offset(this.size.width * 0.32f, this.size.height * 0.26f),
                        radius = this.size.minDimension * 0.88f,
                    ),
                )

                // 2) Siri 轻盈光学母贝流光（三道极淡低饱和折射薄雾缓慢绕球流转）
                val r = this.size.minDimension * 0.24f
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val p1 = Offset(cx + cos(orbitPhase) * r, cy + sin(orbitPhase) * r)
                val p2 = Offset(
                    cx + cos(orbitPhase + 2.1f) * r,
                    cy + sin(orbitPhase + 2.1f) * r,
                )
                val p3 = Offset(
                    cx + cos(orbitPhase + 4.2f) * r,
                    cy + sin(orbitPhase + 4.2f) * r,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFBAE0FF).copy(alpha = shimmerBoost),
                            Color.Transparent,
                        ),
                        center = p1,
                        radius = this.size.minDimension * 0.62f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFF3D4EE).copy(alpha = shimmerBoost * 0.86f),
                            Color.Transparent,
                        ),
                        center = p2,
                        radius = this.size.minDimension * 0.58f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFCCF2EC).copy(alpha = shimmerBoost * 0.78f),
                            Color.Transparent,
                        ),
                        center = p3,
                        radius = this.size.minDimension * 0.54f,
                    ),
                )
            }
            .drawWithContent {
                drawContent()
                // 3) 顶部玻璃球面弧光
                drawOval(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.68f),
                            Color.Transparent,
                        ),
                        startY = this.size.height * 0.04f,
                        endY = this.size.height * 0.44f,
                    ),
                    topLeft = Offset(this.size.width * 0.17f, this.size.height * 0.05f),
                    size = Size(this.size.width * 0.66f, this.size.height * 0.36f),
                )
            }
            .border(
                width = 1.3.dp,
                brush = Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = 0.98f),
                    0.46f to Color.White.copy(alpha = 0.52f),
                    1.0f to Color.White.copy(alpha = 0.88f),
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val baseHeights = floatArrayOf(0.24f, 0.48f, 0.68f, 0.44f, 0.24f)
        val barColor = Color(0xE6161922)
        Canvas(modifier = Modifier.size(size * 0.54f)) {
            val count = baseHeights.size
            val barWidth = (this.size.width / (count * 2f - 0.7f)).coerceAtLeast(2.1.dp.toPx())
            val gap = barWidth * 0.94f
            val totalW = count * barWidth + (count - 1) * gap
            val startX = (this.size.width - totalW) / 2f
            val cy = this.size.height / 2f
            for (i in 0 until count) {
                val mod = if (active) {
                    val wave = 0.5f + 0.5f * sin(wavePhase + i * 1.05f)
                    val boost = 0.24f + clampedLevel * 0.46f
                    (baseHeights[i] * (0.74f + wave * boost)).coerceIn(0.20f, 0.94f)
                } else {
                    baseHeights[i]
                }
                val h = this.size.height * mod
                val x = startX + i * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, cy - h / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}

/**
 * 极简纯黑白渐隐声学波形（替代蓝紫渐变波形）：
 * 中心高对比、向两侧透明度自然渐隐，兼顾极简设计感与实时声学反馈。
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
                animation = tween(durationMillis = 1450, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "liquid_glass_waveform_phase",
        )
        animated
    } else {
        0f
    }

    val weights = floatArrayOf(
        0.18f, 0.28f, 0.40f, 0.30f, 0.58f, 0.80f, 0.54f, 0.92f,
        1.00f,
        0.88f, 0.50f, 0.78f, 0.58f, 0.34f, 0.42f, 0.26f, 0.18f,
    )
    val edgeOpacities = floatArrayOf(
        0.22f, 0.32f, 0.44f, 0.56f, 0.70f, 0.84f, 0.92f, 0.98f,
        1.00f,
        0.98f, 0.92f, 0.84f, 0.70f, 0.56f, 0.44f, 0.32f, 0.22f,
    )
    val clampedLevel = level.coerceIn(0f, 1f)
    val baseInk = if (dark) LiquidGlassInkDark else LiquidGlassInkLight

    Canvas(modifier = modifier) {
        val count = weights.size
        val barWidth = 2.8.dp.toPx()
        val gap = ((size.width - count * barWidth) / (count - 1).coerceAtLeast(1))
            .coerceIn(3.dp.toPx(), 5.8.dp.toPx())
        val totalW = count * barWidth + (count - 1) * gap
        val startX = (size.width - totalW) / 2f
        val cy = size.height / 2f
        for (i in 0 until count) {
            val fraction = if (active) {
                val wave = 0.5f + 0.5f * sin(phase + i * 0.58f)
                val dynamicGain = 0.48f + clampedLevel * 0.48f
                (weights[i] * (0.40f + wave * dynamicGain)).coerceIn(0.14f, 0.98f)
            } else {
                (weights[i] * 0.34f).coerceIn(0.12f, 0.38f)
            }
            val barH = size.height * fraction
            val x = startX + i * (barWidth + gap)
            val barAlpha = if (active) edgeOpacities[i] else edgeOpacities[i] * 0.45f
            drawRoundRect(
                color = baseInk.copy(alpha = barAlpha),
                topLeft = Offset(x, cy - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

internal val VoiceGlassCorner: Dp = 30.dp

/** 语音态统一的极简无色液态玻璃不透明度。 */
internal const val VoiceGlassSurfaceAlpha = 0.48f

/** 语音态统一的边缘折射高光强度。 */
internal const val VoiceGlassRimAlpha = 0.88f
