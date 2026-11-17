package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 单轮回答底部的性能统计胶囊（移植自 Eta 的 ChatStatsPopover.kt，只保留 Miuix 外观）。
 *
 * 平时只显示「首 token 平均 · 输出速度」，点一下弹出锚定卡片：
 * 会话统计（轮/步）、模型用时、工具调用用时、首 token 平均（TTFT）、输出速度（TPS）。
 * 没有耗时数据（旧记录 / provider 不回 timing）时整块不渲染。
 *
 * 所有数字都来自运行时侧的 AgentTurnMetrics：
 * - llmMs 只含模型请求，不含工具执行与等待用户；
 * - ttftMs / ttftSteps 只统计有首 token 的步；
 * - decodeMs / decodeTokens 只统计有输出的步，用来算 tok/s。
 */

/** 胶囊高度与底部图标按钮的点击区一致，避免行高抖动。 */
private val TurnStatsPillHeight = 26.dp

@Composable
internal fun TurnStatsPill(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    if (!usage.hasPerformanceStats) return
    // 不用 usage 做 key：新一步的用量回执不该把已经打开的卡片关掉。
    var open by remember { mutableStateOf(false) }
    val steps = (usage.stepCount ?: 1).coerceAtLeast(1)
    val llmMs = usage.llmMs ?: 0L
    val toolMs = usage.toolMs ?: 0L
    val avgTtftMs = usage.averageTtftMs
    val tps = usage.tokensPerSecond
    val speedText = tps?.let { "${formatTokensPerSecond(it)} tok/s" }
    val pillLabel = when {
        avgTtftMs != null && speedText != null -> "${formatStatDuration(avgTtftMs)} · $speedText"
        speedText != null -> speedText
        llmMs > 0L -> formatStatDuration(llmMs)
        avgTtftMs != null -> "TTFT ${formatStatDuration(avgTtftMs)}"
        else -> return
    }

    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val restingTint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f)
    val activeBg = if (open) {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(TurnStatsPillHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(activeBg, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        TouchHaptics.click(view)
                        open = !open
                    },
                )
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = ChatStatsIcons.Gauge,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = restingTint,
            )
            Text(
                text = pillLabel,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = restingTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (open) {
            TurnStatsPopoverPopup(onDismissRequest = { open = false }) {
                StatsSectionHeader(
                    icon = ChatStatsIcons.Gauge,
                    title = "本轮统计",
                    value = "1 轮 $steps 步",
                )
                StatsSectionDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (llmMs > 0L) {
                        StatsDetailRow(label = "模型用时", value = formatStatDuration(llmMs))
                    }
                    if (toolMs > 0L) {
                        StatsDetailRow(label = "工具调用用时", value = formatStatDuration(toolMs))
                    }
                    if (avgTtftMs != null && avgTtftMs > 0L) {
                        StatsDetailRow(label = "首 token 平均（TTFT）", value = formatStatDuration(avgTtftMs))
                    }
                    if (speedText != null) {
                        StatsDetailRow(label = "输出速度（TPS）", value = speedText)
                    }
                    usage.decodeTokens?.takeIf { it > 0 }?.let { tokens ->
                        StatsDetailRow(label = "本轮输出", value = "${formatExactTokenCount(tokens)} tok")
                    }
                }
            }
        }
    }
}

/** 统计卡片本体：点外面关闭，锚定在胶囊上方（放不下时翻到下方）。 */
@Composable
internal fun TurnStatsPopoverPopup(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) { StatsPopoverPositionProvider(density) }
    val dismiss by rememberUpdatedState(onDismissRequest)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { dismiss() },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 232.dp, max = 320.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 18.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cornerRadius = 18.dp,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

private object ChatStatsIcons {
    /** 无 COUI 依赖后，用 Material 图标替代 Eta 的 ChatActionIcons.Gauge。 */
    val Gauge: ImageVector get() = Icons.Rounded.Speed
}

@Composable
private fun StatsSectionHeader(
    icon: ImageVector,
    title: String,
    value: String?,
) {
    val primaryColor = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = primaryColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = primaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = primaryColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatsSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun StatsDetailRow(
    label: String,
    value: String,
) {
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val valueColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

private class StatsPopoverPositionProvider(
    density: Density,
) : PopupPositionProvider {
    private val marginPx = with(density) { 12.dp.roundToPx() }
    private val verticalGapPx = with(density) { 8.dp.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val minX = marginPx
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(minX)
        val preferredX = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = preferredX.coerceIn(minX, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - verticalGapPx
        val belowY = anchorBounds.bottom + verticalGapPx
        val y = if (aboveY >= marginPx || belowY + popupContentSize.height > windowSize.height - marginPx) {
            aboveY.coerceAtLeast(marginPx)
        } else {
            belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        }
        return IntOffset(x, y)
    }
}

// ---- 纯函数格式化（有单测覆盖；不依赖 Compose 运行时） ----

/** 秒级展示：60 秒以内保留一位小数，超过后给「N分M秒」。 */
internal fun formatStatDuration(ms: Long): String {
    val clampedMs = ms.coerceAtLeast(0L)
    val seconds = clampedMs / 1_000.0
    if (seconds < 60.0) {
        val tenths = (seconds * 10.0).roundToLong()
        val whole = tenths / 10L
        val decimal = tenths % 10L
        return if (decimal == 0L) "${whole}秒" else "$whole.${decimal}秒"
    }
    val totalSeconds = seconds.roundToLong()
    val minutes = totalSeconds / 60L
    val remSeconds = totalSeconds % 60L
    return "${minutes}分${remSeconds}秒"
}

/** tok/s：10 以上取整，10 以下保留一位小数，避免「7 tok/s」抖动成「7.3/7.4」。 */
internal fun formatTokensPerSecond(tps: Double): String {
    val clamped = tps.coerceAtLeast(0.0)
    if (clamped >= 10.0) {
        return clamped.roundToInt().toString()
    }
    val tenths = (clamped * 10.0).roundToInt()
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0) whole.toString() else "$whole.$decimal"
}

internal fun formatExactTokenCount(tokens: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(tokens.coerceAtLeast(0))

/**
 * 缓存命中率（百分比字符串，不带 % 号）。命中数缺失/为 0 或 prompt 为 0 时返回 null；
 * 命中数大于等于 prompt 视为 100；四舍五入到 100 但实际不足时退成一位小数，最高报 99.9。
 */
internal fun formatCacheHitPercent(cachedTokens: Int, promptTokens: Int): String? {
    if (promptTokens <= 0 || cachedTokens < 0) return null
    if (cachedTokens >= promptTokens) return "100"
    val ratio = cachedTokens.toDouble() / promptTokens.toDouble() * 100.0
    val rounded = ratio.roundToInt()
    if (rounded < 100) return rounded.toString()
    val oneDecimal = (ratio * 10.0).toInt() / 10.0
    return if (oneDecimal < 100.0) String.format(Locale.US, "%.1f", oneDecimal) else "99.9"
}
