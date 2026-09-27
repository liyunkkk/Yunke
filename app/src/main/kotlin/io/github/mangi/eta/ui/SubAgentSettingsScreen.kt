package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor
import io.github.mangi.eta.ui.components.SubAgentEditorState
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    val editor = LocalConversationSubAgentEditor.current
    val state = editor?.observe()
    val config = (state as? SubAgentEditorState.Loaded)?.config
    val profiles = config?.profiles.orEmpty()
    val editable = editor?.enabled == true
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var rename by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember(editor) { mutableStateOf("") }
    val view = LocalView.current
    LaunchedEffect(editor, editable) { if (!editable) { rename = null; delete = null } }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Scaffold(containerColor = MaterialTheme.colorScheme.surface,
            topBar = { TopAppBar(title = { Text("子代理") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                FilledTonalButton(onClick = { if (editor?.enabled == true) { TouchHaptics.click(view); editor.add() } },
                    enabled = editable, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp)) {
                    Text("添加子代理", style = MaterialTheme.typography.bodyLarge)
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp)) {
                    item {
                        Text(when (state) {
                            is SubAgentEditorState.Error -> "子代理配置读取或保存失败：${state.reason}"
                            SubAgentEditorState.Loading -> "正在读取本会话子代理配置…"
                            else -> if (editor == null) "请先选择会话；未选择会话时不可编辑子代理。"
                                else if (!editable) "主代理尚未停止，当前会话配置暂不可修改。"
                                else "配置仅属于当前会话；新任务和失败接替使用更新后的配置。"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp))
                        if (state is SubAgentEditorState.Error) TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
                    }
                    if (config != null) {
                        item {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                    Text("按职责自动分配本会话任务", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = config.enabled, enabled = editable,
                                    onCheckedChange = { if (editor?.enabled == true) editor.setEnabled(it) })
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("子代理诊断日志", style = MaterialTheme.typography.bodyLarge)
                                    Text("仅保存本会话诊断配置；运行日志接线由运行层处理。", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = config.diagnosticsEnabled, enabled = editable,
                                    onCheckedChange = { if (editor?.enabled == true) editor.setDiagnosticsEnabled(it) })
                            }
                        }
                        items(profiles, key = { it.id }) { profile ->
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).alpha(if (editable) 1f else 0.38f)) {
                                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(when (profile.role) {
                                            "review" -> Icons.Rounded.FactCheck
                                            "image_generation" -> Icons.Rounded.Image
                                            "video_generation" -> Icons.Rounded.Videocam
                                            else -> Icons.Rounded.AccountTree
                                        }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                                        Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Box(Modifier.requiredSize(36.dp, 22.dp).wrapContentSize(unbounded = true)
                                            .semantics { contentDescription = "启用${profile.name}" }, contentAlignment = Alignment.Center) {
                                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                                Switch(profile.enabled, enabled = editable, onCheckedChange = { active ->
                                                    if (editor?.enabled == true) { TouchHaptics.click(view); editor.updateProfile(profile.id) { it.copy(enabled = active) } }
                                                }, modifier = Modifier.scale(0.7f))
                                            }
                                        }
                                        var expanded by remember(editor, profile.id) { mutableStateOf(false) }
                                        LaunchedEffect(editable) { if (!editable) expanded = false }
                                        Box {
                                            IconButton(enabled = editable, onClick = { if (editor?.enabled == true) { TouchHaptics.click(view); expanded = true } }) {
                                                Icon(Icons.Rounded.MoreVert, "${profile.name}更多操作")
                                            }
                                            if (editable) SubAgentDropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                    onClick = { if (editor?.enabled == true) { name = profile.name; rename = profile }; expanded = false })
                                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = { if (editor?.enabled == true) delete = profile; expanded = false })
                                            }
                                        }
                                    }
                                    SubAgentProfileRow(profile, providers, enabled = editable, settings = true)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
        rename?.let { profile ->
            if (editable) AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名代理") },
                text = { OutlinedTextField(name, { if (editor?.enabled == true) name = it.take(80) }, label = { Text("名称") },
                    singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth()) },
                dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } },
                confirmButton = { TextButton(enabled = editable && name.trim().isNotBlank(), onClick = {
                    if (editor?.enabled == true && editor.updateProfile(profile.id) { it.copy(name = name.trim()) } is ConversationSubAgentPreferences.WriteResult.Saved)
                        rename = null
                }) { Text("保存") } })
        }
        delete?.let { profile ->
            if (editable) AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除代理？") },
                text = { Text("将删除“${profile.name}”的配置，不会删除提供商或模型。") },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
                confirmButton = { TextButton(enabled = editable, onClick = {
                    if (editor?.enabled == true && editor.remove(profile.id) is ConversationSubAgentPreferences.WriteResult.Saved)
                        delete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) } })
        }
    }
    }
}
