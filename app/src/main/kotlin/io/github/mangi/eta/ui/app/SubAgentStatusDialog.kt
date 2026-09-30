package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.agent.kimi.KimiMessage
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatus
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatusAccess
import io.github.mangi.eta.ui.components.KimiStatusDot
import io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor
import io.github.mangi.eta.ui.components.contextLabel
import io.github.mangi.eta.ui.components.contextStatusLabel
import io.github.mangi.eta.ui.components.contextSummary
import io.github.mangi.eta.ui.components.kimiConversationBinding
import io.github.mangi.eta.ui.components.statusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SubAgentStatusDialog(
    show: Boolean,
    agents: List<SubAgentContextStats>,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val ordered = agents.sortedWith(compareBy({ statusRank(it.status) }, { it.contextLabel() }))
    val context = LocalContext.current
    val binding = kimiConversationBinding(LocalConversationSubAgentEditor.current?.owner)
    var kimiStatus by remember(binding) { mutableStateOf<KimiSubAgentStatus?>(null) }
    var kimiMessages by remember(binding) { mutableStateOf<List<KimiMessage>?>(null) }
    var messagesExpanded by remember(binding) { mutableStateOf(false) }
    // Panel opens on demand: refresh once, never poll in the background.
    LaunchedEffect(show, binding) {
        kimiMessages = null
        messagesExpanded = false
        if (binding == null) {
            kimiStatus = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { kimiStatus = KimiSubAgentStatusAccess.readStatus(context, binding) }
    }
    LaunchedEffect(messagesExpanded, binding) {
        if (!messagesExpanded || binding == null || kimiMessages != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            kimiMessages = KimiSubAgentStatusAccess.recentMessages(context, binding)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_sub_agent_status)) },
        text = {
            if (ordered.isEmpty() && kimiStatus == null && !messagesExpanded) {
                Text(
                    text = stringResource(R.string.sub_agent_status_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KimiCodeStatusBlock(
                        status = kimiStatus,
                        messages = kimiMessages,
                        messagesExpanded = messagesExpanded,
                        onToggleMessages = { messagesExpanded = !messagesExpanded },
                    )
                    if (ordered.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ordered.forEach { agent ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = agent.contextLabel(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = agent.modelName.ifBlank { agent.model },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = agent.contextStatusLabel(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sub_agent_status_close))
            }
        },
    )
}

/** Kimi Code 区块：状态、模型、上下文占用、上一轮是否失败与「查看消息」。 */
@Composable
private fun KimiCodeStatusBlock(
    status: KimiSubAgentStatus?,
    messages: List<KimiMessage>?,
    messagesExpanded: Boolean,
    onToggleMessages: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Kimi Code", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = status?.model?.takeIf { it.isNotBlank() } ?: "未选择模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                KimiStatusDot(busy = status?.busy == true)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status?.statusLabel() ?: "未启动",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = status?.contextSummary(locale) ?: "未启动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status?.lastTurnFailed == true) {
                Text(
                    text = "上一轮执行失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status != null) {
                TextButton(onClick = onToggleMessages) {
                    Text(if (messagesExpanded) "收起消息" else "查看消息")
                }
            }
            if (messagesExpanded) {
                when {
                    messages == null -> Text(
                        text = "正在读取消息…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    messages.isEmpty() -> Text(
                        text = "没有可展示的消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> messages.takeLast(MAX_SHOWN_MESSAGES).forEach { message ->
                        Text(
                            text = "${message.role}: ${message.text.take(MAX_MESSAGE_CHARS)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_SHOWN_MESSAGES = 8
private const val MAX_MESSAGE_CHARS = 200

private fun statusRank(status: String): Int = when (status) {
    "running" -> 0
    "pausing" -> 1
    "queued" -> 2
    "awaiting_decision" -> 3
    "paused" -> 4
    "failed" -> 5
    "timed_out" -> 6
    "cancelled" -> 7
    "completed" -> 8
    else -> 9
}
