package io.github.mangi.eta.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.kimi.KimiPermissionMode
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatusAccess
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentPreset
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import io.github.mangi.eta.ui.components.SubAgentPresetCard
import io.github.mangi.eta.ui.components.SubAgentPresetActionsSheet
import io.github.mangi.eta.ui.components.SubAgentPresetNameDialog
import io.github.mangi.eta.ui.components.SubAgentPresetDirectoryStatus
import io.github.mangi.eta.ui.components.rememberSubAgentPresetDirectory
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.SubAgentEditorState
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.components.SubAgentProfileDraftSession
import io.github.mangi.eta.ui.components.SubAgentProfileConfigDialog
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentSettingsScreen(
    onBack: () -> Unit,
    repositoryFactory: () -> ConversationSubAgentPreferences = { ConversationSubAgentPreferences() },
    isCurrentRoute: Boolean = true,
) {
    // Deliberately independent of the app's conversation editor (and its running-task lock).
    val repository = remember { repositoryFactory() }
    val directory = rememberSubAgentPresetDirectory(repository)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val group = directory.entries.firstOrNull { it.id == selectedId }
    LaunchedEffect(directory.entries, directory.loading, directory.error) {
        if (!directory.loading && directory.error == null && selectedId != null && group == null) selectedId = null
    }
    if (group != null) {
        key(group.id) {
            val owner = remember(group.id) { SubAgentConfigKey.Preset(group.id) }
            val editor = remember(owner) {
                ConversationSubAgentEditor(owner, repository) {
                    selectedId == owner.value && try { repository.presetExists(owner.value) } catch (_: Exception) { false }
                }
            }
            DisposableEffect(editor) { onDispose { editor.dispose() } }
            val detailBack = { editor.dispose(); selectedId = null }
            BackHandler(enabled = isCurrentRoute, onBack = detailBack)
            CompositionLocalProvider(LocalConversationSubAgentEditor provides editor) {
                SubAgentPresetDetail(editor, group.name, detailBack, isCurrentRoute)
            }
        }
        return
    }
    var add by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SubAgentPreset?>(null) }
    var delete by remember { mutableStateOf<SubAgentPreset?>(null) }
    var actionsPreset by remember { mutableStateOf<SubAgentPreset?>(null) }
    var name by remember { mutableStateOf("") }
    val view = LocalView.current
    val editable = !directory.loading && directory.error == null
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            Scaffold(containerColor = MaterialTheme.colorScheme.surface,
                topBar = { TopAppBar(title = { Text("子代理组") }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    FilledTonalButton(enabled = editable, onClick = {
                        TouchHaptics.click(view); name = ""; add = true
                    }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp), shape = RoundedCornerShape(50)) {
                        Text("添加子代理组", style = MaterialTheme.typography.bodyLarge)
                    }
                }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("预设组可供不同会话复制使用；修改预设不会改变已应用的会话。", style = MaterialTheme.typography.bodyMedium)
                        SubAgentPresetDirectoryStatus(directory)
                    }
                    items(directory.entries, key = { it.id }) { preset ->
                        SubAgentPresetCard(preset, editable, "编辑子代理组${preset.name}",
                            onClick = { if (editable) directory.change {
                                check(repository.presetExists(preset.id)) { "子代理组不存在，请重试" }
                                selectedId = preset.id
                            } },
                            trailing = {
                                IconButton(modifier = Modifier.padding(end = 8.dp).size(48.dp), enabled = editable, onClick = {
                                    if (editable) { TouchHaptics.click(view); actionsPreset = preset }
                                }) { Icon(Icons.Rounded.MoreVert, "${preset.name}组更多操作") }
                            })
                    }
                }
            }
            actionsPreset?.takeIf { editable }?.let { preset ->
                SubAgentPresetActionsSheet(preset, onDismiss = { actionsPreset = null },
                    onRename = {
                        if (editable) { name = preset.name; rename = preset }
                        actionsPreset = null
                    }, onDelete = {
                        if (editable) delete = preset
                        actionsPreset = null
                    })
            }
            if (add || rename != null) SubAgentPresetNameDialog(
                title = if (add) "添加子代理组" else "重命名子代理组",
                name = name,
                onNameChange = { name = it },
                saveEnabled = editable && name.trim().isNotBlank(),
                onDismiss = { add = false; rename = null },
                onSave = {
                    if (directory.change {
                        if (add) repository.addPreset(name.trim())
                        else check(repository.renamePreset(requireNotNull(rename).id, name.trim())) { "子代理组不存在，请重试" }
                    }) { add = false; rename = null }
                },
            )
            delete?.let { preset ->
                AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除子代理组？") },
                    text = { Text("删除“${preset.name}”不会改变已应用此组的会话配置。") },
                    dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
                    confirmButton = { TextButton(enabled = editable, onClick = {
                        if (directory.change { check(repository.removePreset(preset.id)) { "子代理组不存在，请重试" } }) delete = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAgentPresetDetail(editor: ConversationSubAgentEditor, groupName: String, onBack: () -> Unit, isCurrentRoute: Boolean) {
    val state = editor.observe()
    val lastLoadedConfig = remember(editor) {
        arrayOfNulls<io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig>(1)
    }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    if (loadedConfig != null) lastLoadedConfig[0] = loadedConfig
    val config = loadedConfig ?: lastLoadedConfig[0]
    val profiles = config?.profiles.orEmpty()
    val editable = editor?.enabled == true
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var addedDraft by remember(editor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var rename by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember(editor) { mutableStateOf("") }
    var pollGuard by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.SUBAGENT_POLL_GUARD)) }
    val context = LocalContext.current
    var kimiPermission by remember {
        mutableStateOf(KimiPermissionMode.resolve(Prefs.getString(Prefs.Keys.KIMI_PERMISSION_MODE, KimiPermissionMode.YOLO)))
    }
    var kimiDefaultModel by remember { mutableStateOf<String?>(null) }
    // 默认模型按需读取一次；无运行中的服务端时为 null（显示「未启动」）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { kimiDefaultModel = KimiSubAgentStatusAccess.defaultModel(context) }
    }
    val view = LocalView.current
    LaunchedEffect(editor, editable) { if (!editable) { rename = null; delete = null } }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Scaffold(containerColor = MaterialTheme.colorScheme.surface,
            topBar = { TopAppBar(title = { Text(groupName) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                FilledTonalButton(onClick = {
                    if (editor.enabled) SubAgentProfileDraftSession.open(editor)?.let {
                        TouchHaptics.click(view)
                        addedDraft = it
                    }
                },
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
                            SubAgentEditorState.Loading -> "正在读取子代理组配置…"
                            else -> if (!editable) "当前子代理组暂不可修改。"
                                else "配置属于此预设组；会话应用时复制，后续修改互不影响。"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp))
                        if (state is SubAgentEditorState.Error) TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
                    }
                    if (config != null) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("轮询退避门禁", style = MaterialTheme.typography.bodyLarge)
                                    Text("同一运行中任务的连续查询过于频繁时，短暂摘除查询工具；终态任务与列表查询不受限。", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = pollGuard, enabled = editable,
                                    onCheckedChange = { if (editor?.enabled == true) {
                                        pollGuard = it
                                        Prefs.putBoolean(Prefs.Keys.SUBAGENT_POLL_GUARD, it)
                                    } })
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Kimi Code", style = MaterialTheme.typography.titleMedium)
                                Text("重度编码与需要编译验证的任务走 Kimi Code；并行调查 / 审查 / 媒体走原生子代理。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            // 授权模式与主设置页同一键；全局生效，不依赖本会话是否可编辑。
                            WindowSpinnerPreference(
                                title = "Kimi 授权模式",
                                summary = "默认自动批准，遇到高风险操作可切到「每次确认」。",
                                items = listOf("自动批准（yolo）", "自动（auto）", "每次确认（manual）")
                                    .map { DropdownItem(text = it) },
                                selectedIndex = KimiPermissionMode.all.indexOf(kimiPermission).coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    val mode = KimiPermissionMode.all.getOrNull(index) ?: return@WindowSpinnerPreference
                                    kimiPermission = mode
                                    Prefs.putString(Prefs.Keys.KIMI_PERMISSION_MODE, mode)
                                },
                                startAction = { PreferenceIcon(icon = Icons.Rounded.Lock) },
                            )
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("默认模型", style = MaterialTheme.typography.bodyLarge)
                                    Text("服务端 default_model；本会话可在协作面板另行覆盖。", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(kimiDefaultModel?.takeIf { it.isNotBlank() } ?: "未启动",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // Editing a preset does not change its saved automatic-delegation value.
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
                                                DropdownMenuItem(text = { Text("配置") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                                    onClick = {
                                                        if (editor.enabled) SubAgentProfileDraftSession.open(editor, profile)?.let {
                                                            TouchHaptics.click(view)
                                                            addedDraft = it
                                                        }
                                                        expanded = false
                                                    })
                                                DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                    onClick = { if (editor?.enabled == true) { name = profile.name; rename = profile }; expanded = false })
                                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = { if (editor?.enabled == true) delete = profile; expanded = false })
                                            }
                                        }
                                    }
                                    SubAgentProfileRow(profile, providers, enabled = editable, settings = true, draftAllowed = true)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
        addedDraft?.let { session ->
            SubAgentProfileConfigDialog(session, editor, providers, enabled = isCurrentRoute, onDismiss = {
                session.dismiss()
                if (addedDraft === session) addedDraft = null
            })
        }
        rename?.let { profile ->
            if (editable) SubAgentPresetNameDialog(
                title = "重命名代理",
                fieldHint = "名称",
                name = name,
                onNameChange = { if (editor.enabled) name = it },
                saveEnabled = name.trim().isNotBlank(),
                onDismiss = { rename = null },
                onSave = {
                    if (editor.enabled && editor.updateProfile(profile.id) { it.copy(name = name.trim()) } is ConversationSubAgentPreferences.WriteResult.Saved)
                        rename = null
                },
            )
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
