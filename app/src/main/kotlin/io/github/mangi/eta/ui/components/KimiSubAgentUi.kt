package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatus
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Kimi Code 在「子代理」体系里的展示辅助：协作面板、上下文弹层与状态弹窗共用同一套文案，
 * 避免同一状态在三处各写一遍、互相对不上。
 */

/** 会话 owner 才是 Kimi 会话绑定键；草稿等非会话 owner 没有可展示的 Kimi 会话。 */
internal fun kimiConversationBinding(owner: SubAgentConfigKey?): String? =
    (owner as? SubAgentConfigKey.Conversation)?.value

/** 「执行中 / 空闲」。 */
internal fun KimiSubAgentStatus.statusLabel(): String = if (busy) "执行中" else "空闲"

/** 「上下文 12.3k / 200k（6%）」；未测得上限时退化为仅显示已用。 */
internal fun KimiSubAgentStatus.contextSummary(locale: Locale = Locale.getDefault()): String {
    val tokens = contextTokens ?: return "上下文用量未知"
    val window = maxContextTokens
    val tokenText = formatCompactTokenCount(tokens, locale)
    if (window == null || window <= 0) return "上下文 $tokenText tokens"
    val percent = (tokens.toDouble() / window * 100.0).roundToInt()
    return "上下文 $tokenText / ${formatCompactTokenCount(window, locale)}（$percent%）"
}

/** 执行状态圆点：执行中用主色，空闲用弱色。 */
@Composable
internal fun KimiStatusDot(busy: Boolean) {
    val color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * 切换本会话的 Kimi Code 模型。
 *
 * 没有服务端模型列表接口，因此用文本输入模型别名；留空/「跟随默认」表示使用
 * 服务端 `default_model`。返回值经 [ConversationSubAgentEditor.saveKimiModel] 归一化。
 */
@Composable
internal fun KimiSubAgentModelDialog(
    currentModel: String?,
    defaultModel: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(currentModel.orEmpty()) }
    val defaultLabel = defaultModel?.takeIf { it.isNotBlank() } ?: "服务端 default_model"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kimi Code 模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅对当前会话生效；留空表示跟随默认模型（$defaultLabel）。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("模型别名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("跟随默认") } },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("保存") } },
    )
}
