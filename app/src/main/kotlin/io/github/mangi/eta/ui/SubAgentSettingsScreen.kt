package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    val profiles by remember { SubAgentPreferences.profilesFlow() }.collectAsState(initial = SubAgentPreferences.profiles())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var rename by remember { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember { mutableStateOf("") }
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
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.navigationBarsPadding().horizontalCutoutPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TextButton(onClick = { SubAgentPreferences.add() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                                    Icon(Icons.Rounded.Add, null, Modifier.padding(6.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("添加子代理", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        Text("配置代理职责与模型，更改下次运行生效。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp))
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
                                    }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                                    Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Switch(profile.enabled, onCheckedChange = { active ->
                                        SubAgentPreferences.update(profile.id) { it.copy(enabled = active) }
                                    }, modifier = Modifier.semantics { contentDescription = "启用${profile.name}" })
                                    var expanded by remember(profile.id) { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { expanded = true }) {
                                            Icon(Icons.Rounded.MoreVert, "${profile.name}更多操作")
                                        }
                                        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.width(180.dp),
                                            shape = RoundedCornerShape(12.dp), tonalElevation = 0.dp,
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                                            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                onClick = { name = profile.name; rename = profile; expanded = false })
                                            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = { delete = profile; expanded = false })
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
                dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                    SubAgentPreferences.update(profile.id) { it.copy(name = name.trim()) }; rename = null
                }) { Text("保存") } })
        }
        delete?.let { profile ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除代理？") },
                text = { Text("将删除“${profile.name}”的配置，不会删除提供商或模型。") },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
                confirmButton = { TextButton(onClick = { SubAgentPreferences.remove(profile.id); delete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                } })
        }
    }
    }
}
