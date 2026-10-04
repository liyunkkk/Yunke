package io.github.mangi.eta.ui.components

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics

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
    // Opening the chooser never writes: legacy conversations keep their original configuration.
    var chooserOverride by remember(editor) { mutableStateOf<Boolean?>(null) }
    val choosing = chooserOverride ?: (current?.appliedPresetId == null)
    var applicationError by remember(editor) { mutableStateOf<String?>(null) }
    val latestOwnerMatches by rememberUpdatedState(ownerMatches)
    val latestTaskRunning by rememberUpdatedState(taskRunning)
    val latestChoosing by rememberUpdatedState(choosing)
    val latestCanChange by rememberUpdatedState(canChange)
    val directory = editor?.let { rememberSubAgentPresetDirectory(it.repository) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val view = LocalView.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                        Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 18.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(key(editor, choosing, current?.presetApplicationToken) { rememberScrollState() })
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
                            if (choosing && directory != null && editor != null) {
                                val popup = remember(editor) { OwnerBoundPopup(editor.owner) }
                                val ticket = remember(popup) { popup.open() }
                                DisposableEffect(popup) { onDispose { popup.dismiss() } }
                                Text("选择子代理组", style = MaterialTheme.typography.titleMedium)
                                Text("应用时复制到本会话；本会话修改不会反写预设。切换会替换当前会话配置。",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                                SubAgentPresetDirectoryStatus(directory)
                                directory.entries.forEach { preset ->
                                    key(editor, preset.id) {
                                        SubAgentPresetCard(preset, canChange && directory.error == null && !directory.loading,
                                            "应用子代理组${preset.name}", selected = current?.appliedPresetId == preset.id,
                                            onClick = {
                                                if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing) {
                                                    val result = editor.applyPreset(preset.id) {
                                                        popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestChoosing
                                                    }
                                                    if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                                                        applicationError = null
                                                        chooserOverride = false
                                                    } else applicationError = "子代理组未应用，当前会话配置已保留；请重试。"
                                                }
                                            })
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                                applicationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (current != null) TextButton(enabled = canChange, onClick = {
                                    if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && editor.enabled) {
                                        TouchHaptics.click(view)
                                        chooserOverride = false
                                    }
                                }) { Text("使用当前配置") }
                            } else if (current != null && editor != null) {
                                key(editor, current.presetApplicationToken) {
                                    // Unlike key alone, this permanently captured token rejects retained callbacks
                                    // even after the root editor has observed a newly applied group with the same IDs.
                                    val panelEditor = remember(editor, current.presetApplicationToken) {
                                        editor.scoped(current.presetApplicationToken) {
                                            latestOwnerMatches() && !latestTaskRunning && !latestChoosing
                                        }
                                    }
                                    DisposableEffect(panelEditor) { onDispose { panelEditor.dispose() } }
                                    val panelState = panelEditor.observe()
                                    val panelConfig = (panelState as? SubAgentEditorState.Loaded)?.config
                                    val panelEnabled = canChange && panelEditor.enabled && panelConfig != null
                                    CompositionLocalProvider(LocalConversationSubAgentEditor provides panelEditor) {
                                        when (panelState) {
                                            is SubAgentEditorState.Error -> {
                                                Text("本会话配置保存或读取失败：${panelState.reason}", color = MaterialTheme.colorScheme.error)
                                                TextButton(enabled = latestOwnerMatches() && !latestTaskRunning, onClick = {
                                                    if (latestOwnerMatches() && !latestTaskRunning && !latestChoosing) {
                                                        TouchHaptics.click(view)
                                                        // Both subscriptions can be fenced by the same failed commit.
                                                        editor.retry()
                                                        panelEditor.retry()
                                                    }
                                                }) { Text("重试本会话配置") }
                                            }
                                            SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                            else -> Unit
                                        }
                                        (panelConfig ?: current).appliedPresetName?.let {
                                            Text("已应用：$it", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(enabled = panelEnabled, onClick = {
                                            if (panelEnabled && panelEditor.enabled) { TouchHaptics.click(view); chooserOverride = true }
                                        }) { Text("切换子代理组") }
                                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                            .toggleable(value = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, role = Role.Switch,
                                                onValueChange = {
                                                    if (panelEnabled && panelEditor.enabled) { TouchHaptics.click(view); panelEditor.setEnabled(it) }
                                                }), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                                Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface)
                                            }
                                            Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, onCheckedChange = null)
                                        }
                                        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                        panelConfig?.profiles.orEmpty().forEach { profile ->
                                            key(panelEditor, profile.id) {
                                                SubAgentProfileRow(profile, providers, enabled = panelEnabled)
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                            }
                                        }
                                        Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = !taskRunning, onClick = {
                                if (latestOwnerMatches() && !latestTaskRunning) { TouchHaptics.click(view); onDismiss() }
                            }) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}
