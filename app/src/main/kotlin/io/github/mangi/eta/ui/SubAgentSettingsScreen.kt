package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    val profiles by remember { SubAgentPreferences.profilesFlow() }.collectAsState(initial = SubAgentPreferences.profiles())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var rename by remember { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember { mutableStateOf("") }
    val logging by remember { io.github.mangi.eta.data.datastore.SettingsDataStore.fileLoggingEnabledFlow() }.collectAsState(initial = io.github.mangi.eta.core.AppFileLogger.isEnabled())
    val settingsScope = rememberCoroutineScope()
    val view = LocalView.current
    // Material widgets use their own ripple provider, separate from foundation LocalIndication.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(title = { Text("子代理") }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
            },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                FilledTonalButton(
                    onClick = { TouchHaptics.click(view); SubAgentPreferences.add() },
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp),
                ) {
                    Text("添加子代理", style = MaterialTheme.typography.bodyLarge)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    // Allow the last row to scroll fully above the floating add action.
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        Text("配置代理职责与模型，更改下次运行生效。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("子代理诊断日志", style = MaterialTheme.typography.bodyLarge)
                                Text("使用应用统一日志开关。记录排队、模型请求、工具阶段、压缩、超时和续作；不记录任务正文、密钥或工具内容。可在设置的诊断区导出日志。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = logging, onCheckedChange = { value -> settingsScope.launch {
                                io.github.mangi.eta.data.datastore.SettingsDataStore.setFileLoggingEnabled(value)
                            } })
                        }
                    }
                    items(profiles, key = { it.id }) { profile ->
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
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
                                    Box(
                                        Modifier
                                            .requiredSize(36.dp, 22.dp)
                                            .wrapContentSize(unbounded = true)
                                            .semantics { contentDescription = "启用${profile.name}" },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                            Switch(
                                                profile.enabled,
                                                onCheckedChange = { active ->
                                                    TouchHaptics.click(view)
                                                    SubAgentPreferences.update(profile.id) { it.copy(enabled = active) }
                                                },
                                                modifier = Modifier.scale(0.7f),
                                            )
                                        }
                                    }
                                    var expanded by remember(profile.id) { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { TouchHaptics.click(view); expanded = true }) {
                                            Icon(Icons.Rounded.MoreVert, "${profile.name}更多操作")
                                        }
                                        SubAgentDropdownMenu(expanded, { expanded = false }) {
                                            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                onClick = { TouchHaptics.click(view); name = profile.name; rename = profile; expanded = false })
                                            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = { TouchHaptics.click(view); delete = profile; expanded = false })
                                        }
                                    }
                                }
                                SubAgentProfileRow(profile, providers, settings = true)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }

                }
            }
        }
        rename?.let { profile ->
            AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名代理") },
                text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()) },
                dismissButton = { TextButton(onClick = { TouchHaptics.click(view); rename = null }) { Text("取消") } },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                    TouchHaptics.click(view)
                    SubAgentPreferences.update(profile.id) { it.copy(name = name.trim()) }; rename = null
                }) { Text("保存") } })
        }
        delete?.let { profile ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除代理？") },
                text = { Text("将删除“${profile.name}”的配置，不会删除提供商或模型。") },
                dismissButton = { TextButton(onClick = { TouchHaptics.click(view); delete = null }) { Text("取消") } },
                confirmButton = { TextButton(onClick = { TouchHaptics.click(view); SubAgentPreferences.remove(profile.id); delete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                } })
        }
    }
    }
}
