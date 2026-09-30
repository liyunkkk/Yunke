package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatus
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatusAccess
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A popup entry belongs to one real configuration owner (including the unique draft key).
 * Reopening invalidates previously captured callbacks even when the owner is the same. */
internal class OwnerBoundPopup<Owner>(val owner: Owner) {
    private var active: Any? = null
    fun open(): Any = Any().also { active = it }
    fun dismiss() { active = null }
    fun isCurrent(ticket: Any?, current: OwnerBoundPopup<Owner>): Boolean =
        ticket != null && current === this && active === ticket
    fun dispatch(ticket: Any?, current: OwnerBoundPopup<Owner>, action: () -> Unit): Boolean {
        if (!isCurrent(ticket, current)) return false
        action()
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    taskRunning: Boolean = false,
    ownerMatches: () -> Boolean = { true },
) {
    if (!show || !ownerMatches()) return
    // Never rebind an already opened dialog to a different editor during asynchronous loading.
    val editor = LocalConversationSubAgentEditor.current?.takeIf { ownerMatches() }
    val state = editor?.observe()
    val current = (state as? SubAgentEditorState.Loaded)?.config
    val canChange = editor?.enabled == true && !taskRunning && ownerMatches()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val view = LocalView.current
    val context = LocalContext.current
    val kimiBinding = kimiConversationBinding(editor?.owner)
    var kimiStatus by remember(kimiBinding) { mutableStateOf<KimiSubAgentStatus?>(null) }
    var kimiDefaultModel by remember(kimiBinding) { mutableStateOf<String?>(null) }
    var kimiModelDialog by remember(editor) { mutableStateOf(false) }
    // 打开面板时按需刷新一次；无运行中的 Kimi 会话时状态为 null（显示「未启动」）。
    LaunchedEffect(show, kimiBinding, current?.kimiModel) {
        if (kimiBinding == null) {
            kimiStatus = null
            kimiDefaultModel = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            kimiStatus = KimiSubAgentStatusAccess.readStatus(context, kimiBinding)
            kimiDefaultModel = KimiSubAgentStatusAccess.defaultModel(context)
        }
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 18.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .alpha(if (canChange) 1f else 0.38f)) {
                        when (state) {
                            is SubAgentEditorState.Error -> {
                                Text("子代理配置读取或保存失败：${state.reason}")
                                TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
                            }
                            SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                            else -> if (editor == null) Text("请先选择会话；未选择会话时不可编辑。")
                                else if (!canChange) Text("主代理尚未停止，暂不可编辑本会话配置。")
                        }
                        if (current != null) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .toggleable(value = current.enabled, enabled = canChange, role = Role.Switch,
                                    onValueChange = { if (ownerMatches() && editor?.enabled == true && !taskRunning) {
                                        TouchHaptics.click(view); editor.setEnabled(it)
                                    } }), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                    Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                                Switch(checked = current.enabled, enabled = canChange, onCheckedChange = null)
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            Text("原生子代理 · 并行调查 / 审查 / 媒体", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                            current.profiles.forEach { profile ->
                                key(editor, profile.id) {
                                    SubAgentProfileRow(profile, providers, enabled = canChange)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            val locale = LocalConfiguration.current.locales[0]
                            val kimiModelLabel = current.kimiModel
                                ?: kimiDefaultModel?.takeIf { it.isNotBlank() }
                                ?: "跟随默认模型"
                            val kimiStatusText = kimiStatus?.let { "${it.statusLabel()} · ${it.contextSummary(locale)}" }
                                ?: "未启动"
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable(enabled = canChange) {
                                    if (ownerMatches() && editor?.enabled == true && !taskRunning) {
                                        TouchHaptics.click(view); kimiModelDialog = true
                                    }
                                }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Kimi Code", style = MaterialTheme.typography.bodyLarge)
                                    Text("重度编码 / 需要编译验证的任务", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(kimiModelLabel, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(kimiStatusText, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                KimiStatusDot(busy = kimiStatus?.busy == true)
                            }
                            Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !taskRunning, onClick = { TouchHaptics.click(view); onDismiss() }) { Text("完成") }
                    }
                }
            }
        }
        if (kimiModelDialog && current != null) {
            KimiSubAgentModelDialog(
                currentModel = current.kimiModel,
                defaultModel = kimiDefaultModel,
                onDismiss = { kimiModelDialog = false },
                onSave = { model ->
                    if (ownerMatches() && editor?.enabled == true && !taskRunning) {
                        TouchHaptics.click(view)
                        editor.saveKimiModel(model)
                    }
                    kimiModelDialog = false
                },
            )
        }
    }
    }
}
