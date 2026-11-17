package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Token 用量详情面板：输入栏圆环点开后的完整视图。
 *
 * 三段结构参照用户给的参考面板：上下文占用（含分项）、本轮构成、说明。
 * 只展示真实拿到的云端回执；拿不到分项时显式写「—」，不臆造数字。
 * 余额段暂不展示——Yunke 的余额查询绑定在提供商详情页，这里没有可靠来源。
 */
@Composable
internal fun UsagePanelSheet(
    usage: AgentContextUsageUi,
    modelName: String,
    onDismiss: () -> Unit,
    conversationUsage: ConversationTokenUsageUi? = null,
) {
    val colors = MiuixTheme.colorScheme
    val percent = usage.progress?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
    val contextTokens = usage.contextTokens?.takeIf { it > 0 }
    val window = usage.contextWindow?.takeIf { it > 0 }
    val history = usage.historyTokens
    val overhead = usage.overheadTokens
    val remaining = if (contextTokens != null && window != null) {
        (window - contextTokens).coerceAtLeast(0)
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 不加暗色遮罩：点面板外的空白直接关闭，背景保持原来的界面。
            val dismissSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = dismissSource,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(colors.surface)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
            ) {
                // 拖拽条
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurface.copy(alpha = 0.14f)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "用量",
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                if (modelName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = modelName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }

                // ── 上下文 ────────────────────────────────────────────
                SectionLabel("上下文")
                PanelCard {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = percent?.let { "$it%" } ?: "—",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (percent != null && percent >= 80) StatusWarning else colors.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${formatTokenShort(contextTokens)} / ${formatTokenShort(window)}",
                            style = MiuixTheme.textStyles.body2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.secondaryContainer),
                    ) {
                        val fraction = (usage.progress ?: 0f).coerceIn(0f, 1f)
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        when {
                                            fraction >= 0.95f -> StatusError
                                            fraction >= 0.80f -> StatusWarning
                                            else -> colors.primary
                                        },
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "系统与工具 ${formatTokenShort(overhead)} · 消息 ${formatTokenShort(history)}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "分项为独立估算，可能与总量不同",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }

                // ── 本轮构成 ──────────────────────────────────────────
                SectionLabel("本轮构成")
                PanelCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatCell("已用", formatTokenShort(contextTokens))
                        StatCell("消息", formatTokenShort(history))
                        StatCell("剩余", formatTokenShort(remaining))
                    }
                    if (conversationUsage != null && conversationUsage.hasUsage) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatCell("累计输入", formatTokenShort(conversationUsage.inputTokens.toInt()))
                            StatCell("输出", formatTokenShort(conversationUsage.outputTokens.toInt()))
                            StatCell("缓存命中", conversationUsage.cachePercent?.let { "${it.roundToInt()}%" } ?: "—")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when {
                            usage.estimated -> "以上为本地估算，等待云端回执"
                            usage.firstTurn -> "首轮请求，尚未产生回执"
                            else -> "数值来自最近一次云端回执"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "会话累计与余额明细在提供商页查看",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 紧凑 token 数：1024 进制，K/M 保留最多两位有效数字。
 * 例：9429 → 9.2K；464000 → 453K；1050000 → 1.0M。
 */
internal fun formatTokenShort(tokens: Int?): String {
    if (tokens == null) return "—"
    val value = tokens.coerceAtLeast(0)
    if (value < 1024) return value.toString()
    val k = value / 1024.0
    if (k < 1024) return trimToTwoSignificant(k) + "K"
    return trimToTwoSignificant(k / 1024.0) + "M"
}

private fun trimToTwoSignificant(value: Double): String {
    val text = if (value >= 100) {
        value.roundToInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
    return text.removeSuffix(".0")
}
