package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.DropdownItem
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.ModelRetrySettings
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.settingsSectionDiagnostics
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置一级页：6 个分类入口 + 权限未授权计数 + 诊断 + 关于。
 *
 * 二级化后的瘦身版（原 1088 行 → 约 300 行）。搬走的内容：
 * - 模型与对话 → [SettingsModelScreen]（提供商入口、深度思考、压缩入口、模型功能）
 * - 扩展与角色 → [SettingsExtensionsScreen]（记忆、助手、Skills、MCP）
 * - 工具 → [SettingsToolsScreen]（工具列表、设备/终端开关、Linux、工作区）
 * - 通用 → [SettingsGeneralScreen]（语音、外观、用量、备份、触感）
 * - 权限与通知 → 已有的 [AppRoute.Permissions]（PermissionHealthScreen，不新建简化页）
 * - 系统接管增强 → 已有的 [AppRoute.AssistantTakeover]（SystemTakeoverSettingsScreen）
 * - 底部 helper（SwitchPref / putBooleanSync / putStringSync / SettingsProviderEntry 等）
 *   → [SettingsShared.kt]；权限状态 → [rememberPermissionSummaryState]。
 * currentProviderId / currentModelId 参数随瘦身删掉，改由 AgentAppRoot 的
 * SettingsModel 入口直接从 shellHomeState 传入 SettingsModelScreen。
 *
 * 行为保持点（特意没动）：
 * - 接管入口无条件显示（Number444 是按框架/root/系统化条件显隐，改了会让没 root
 *   也没接过 LSPosed 的新用户看不到入口，见 README 待确认）。
 * - 诊断分组留在一级页（Number444 的六个分类里没有它的位置）。
 * - 关于区保持 Yunke 的两项（检查更新、源代码），不补 Number444 的版本号单行与反馈入口。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
    currentModelId: String? = null,
) {
    // Read only attach/detach generation so idle sections rebuild observer identities.
    StreamPerformanceDiagnostics.sessionGeneration.longValue
    // Counts only successfully applied compositions; never writes snapshot state.
    SideEffect {
        if (StreamPerformanceDiagnostics.enabled) {
            StreamPerformanceDiagnostics.record("settings.composition")
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var modelRetryCount by remember(agentPrefs) {
        mutableStateOf(
            ModelRetrySettings.parseCount(
                agentPrefs?.getString(Prefs.Keys.MODEL_RETRY_COUNT, ModelRetrySettings.DEFAULT_COUNT.toString()),
            ),
        )
    }
    val permissionState = rememberPermissionSummaryState(context)
    val currentVersion = remember { AppUpdateRepository.currentVersionName(context) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateOffer by remember { mutableStateOf<AppUpdateOffer?>(null) }
    val appSettings by remember { SettingsDataStore.settingsFlow() }.collectAsState(
        initial = io.github.mangi.eta.data.model.Settings(),
    )
    var exportingLogs by remember { mutableStateOf(false) }
    var clearingLogs by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            exportingLogs = true
            try {
                val output = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?: error(context.getString(R.string.settings_export_logs_open_failed))
                }
                val count = withContext(Dispatchers.IO) {
                    output.use { AppFileLogger.export(it) }
                }
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_export_logs_success, count),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Toast.makeText(
                    context.applicationContext,
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.settings_export_logs_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                exportingLogs = false
            }
        }
    }

    WithoutPressRipple {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
        modifier = Modifier
            .streamDiagnosticMeasure("settings.root.measure")
            .streamDiagnosticDraw("settings.root.draw"),
        topBarModifier = Modifier.streamDiagnosticMeasure("settings.topbar.measure"),
        listModifier = Modifier.streamDiagnosticMeasure("settings.lazy.measure").streamDiagnosticPlacement("settings.lazy.place").streamDiagnosticDraw("settings.lazy.draw"),
    ) {
            // ── 分类入口：按语义分 6 组 ────────────────────────────────
            item(key = "section_conversation") {
                SmallTitle("对话与模型")
                Card(modifier = Modifier.settingsSectionDiagnostics("conversation").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    // 模型与对话二级页已拆散：提供商入口和默认推理、重试次数直接放在这里，
                    // 其余模型功能与下面各组合并，不再有一个同名二级页重复收纳。
                    SettingsProviderEntry(currentProviderId, currentModelId) {
                        onNavigate(AppRoute.ModelProviders)
                    }
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.route_context_compression),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Compress) },
                        onClick = { onNavigate(AppRoute.ContextCompression) },
                    )
                    ArrowPreference(
                        title = "自动会话标题",
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Label) },
                        onClick = { onNavigate(AppRoute.TitleModel) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.auxiliary_vision_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Visibility) },
                        onClick = { onNavigate(AppRoute.AuxiliaryVision) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.route_subagents),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.AccountTree) },
                        onClick = { onNavigate(AppRoute.SubAgents) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.error_reconnect_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Sync) },
                        onClick = { onNavigate(AppRoute.ErrorReconnectSettings) },
                    )
                    WindowSpinnerPreference(
                        title = stringResource(R.string.settings_model_retry_title),
                        summary = stringResource(R.string.settings_model_retry_summary),
                        items = ModelRetrySettings.allowedCounts.map { DropdownItem(text = it.toString()) },
                        selectedIndex = ModelRetrySettings.allowedCounts.indexOf(modelRetryCount).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val count = ModelRetrySettings.allowedCounts.getOrNull(index)
                                ?: return@WindowSpinnerPreference
                            val targetPrefs = agentPrefs ?: return@WindowSpinnerPreference
                            if (putStringSync(targetPrefs, Prefs.Keys.MODEL_RETRY_COUNT, count.toString())) {
                                modelRetryCount = count
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Refresh, enabled = agentPrefs != null)
                        },
                        enabled = agentPrefs != null,
                    )
                }
            }

            item(key = "section_persona") {
                SmallTitle("人格与扩展")
                Card(modifier = Modifier.settingsSectionDiagnostics("persona").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "助手与角色",
                        startAction = { PreferenceIcon(icon = Icons.Rounded.SmartToy) },
                        onClick = { onNavigate(AppRoute.Assistants()) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        startAction = { PreferenceIcon(icon = Icons.AutoMirrored.Rounded.MenuBook) },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.route_skills),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Extension) },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.route_mcp_servers),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Dns) },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )
                }
            }

            item(key = "section_execution") {
                SmallTitle("工具与执行")
                Card(modifier = Modifier.settingsSectionDiagnostics("execution").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.ui_tool_a72ef1),
                        summary = "逐项开关与分组",
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Build) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )
                    ArrowPreference(
                        title = "网页搜索与抓取",
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Public) },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Terminal) },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Folder) },
                        onClick = { onNavigate(AppRoute.Workspace) },
                    )
                }
            }

            item(key = "section_system") {
                SmallTitle("系统与权限")
                Card(modifier = Modifier.settingsSectionDiagnostics("system").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_category_permissions_notifications),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Security) },
                        endActions = {
                            // 未授权计数聚合悬浮窗/无障碍/超级岛三状态，入口可见性不因入二级而丢失。
                            if (permissionState.unauthorizedCount > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.settings_permissions_unauthorized_count,
                                        permissionState.unauthorizedCount,
                                    ),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                        },
                        onClick = { onNavigate(AppRoute.Permissions) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.capability_enhancements),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Bolt) },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_system_takeover_entry),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.AutoAwesome) },
                        onClick = { onNavigate(AppRoute.AssistantTakeover) },
                    )
                }
            }

            item(key = "section_general") {
                SmallTitle("外观与数据")
                Card(modifier = Modifier.settingsSectionDiagnostics("general").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.voice_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                        onClick = { onNavigate(AppRoute.VoiceSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.appearance_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Palette) },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.haptics_list),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Vibration) },
                        onClick = { onNavigate(AppRoute.Haptics) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.stats_page_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.BarChart) },
                        onClick = { onNavigate(AppRoute.UsageStats) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.data_backup_title),
                        startAction = { PreferenceIcon(icon = Icons.Rounded.Backup) },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                }
            }

            // ── 关于与诊断 ──────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle("关于与诊断")
                Card(modifier = Modifier.settingsSectionDiagnostics("about").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    // 版本行：与检查更新同卡，只做展示（点击不动作，保留箭头与图标）。
                    ArrowPreference(
                        title = stringResource(R.string.settings_about_version),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Info)
                        },
                        endActions = {
                            Text(
                                text = currentVersion.ifBlank { stringResource(R.string.update_unknown_version) },
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {},
                    )
                    ArrowPreference(
                        title = stringResource(R.string.update_check_title),
                        summary = if (checkingUpdate) {
                            stringResource(R.string.update_checking)
                        } else {
                            stringResource(
                                R.string.update_current_version,
                                currentVersion.ifBlank { stringResource(R.string.update_unknown_version) },
                            )
                        },
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.CloudDownload,
                            )
                        },
                        onClick = {
                            if (checkingUpdate) return@ArrowPreference
                            checkingUpdate = true
                            coroutineScope.launch {
                                val result = AppUpdateRepository.checkForUpdate(context, force = true)
                                checkingUpdate = false
                                result.fold(
                                    onSuccess = { offer ->
                                        if (offer == null) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.update_latest),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            updateOffer = offer
                                        }
                                    },
                                    onFailure = { failure ->
                                        Toast.makeText(
                                            context,
                                            failure.message?.takeIf { it.isNotBlank() }
                                                ?: context.getString(R.string.update_check_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_source_code_740296),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Code,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/liyunkkk/Yunke"),
                            )
                            context.startActivity(intent)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_about_feedback),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Feedback)
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/liyunkkk/Yunke/issues"),
                            )
                            context.startActivity(intent)
                        },
                    )
                    // ── 诊断（原独立分组，按用户要求并入「关于」）──────────────
                    SwitchPreference(
                        title = stringResource(R.string.settings_file_logging),
                        summary = stringResource(R.string.settings_file_logging_summary),
                        checked = appSettings.fileLoggingEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                SettingsDataStore.setFileLoggingEnabled(enabled)
                            }
                        },
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.BugReport)
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_export_logs),
                        summary = stringResource(
                            if (exportingLogs) {
                                R.string.settings_export_logs_working
                            } else {
                                R.string.settings_export_logs_summary
                            },
                        ),
                        enabled = !exportingLogs && !clearingLogs,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Share)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@ArrowPreference
                            if (!AppFileLogger.hasLogs()) {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_export_logs_empty),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@ArrowPreference
                            }
                            exportLogsLauncher.launch(defaultDiagnosticLogFileName())
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_clear_logs),
                        summary = stringResource(R.string.settings_clear_logs_summary),
                        enabled = !exportingLogs && !clearingLogs,
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.DeleteSweep)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@ArrowPreference
                            showClearLogsDialog = true
                        },
                    )
                }
            }
        }
    }

        AppUpdateDialog(
            offer = updateOffer,
            currentVersion = currentVersion,
            onDismiss = { updateOffer = null },
        )

        if (showClearLogsDialog) {
            WindowDialog(
                show = true,
                title = stringResource(R.string.settings_clear_logs_confirm_title),
                summary = stringResource(R.string.settings_clear_logs_confirm_summary),
                onDismissRequest = {
                    if (!clearingLogs) showClearLogsDialog = false
                },
            ) {
                MiuixDialogActions(
                    confirmText = if (clearingLogs) {
                        stringResource(R.string.settings_export_logs_working)
                    } else {
                        stringResource(R.string.settings_clear_logs)
                    },
                    destructive = true,
                    cancelEnabled = !clearingLogs,
                    confirmEnabled = !clearingLogs,
                    onCancel = { showClearLogsDialog = false },
                    onConfirm = {
                        if (clearingLogs) return@MiuixDialogActions
                        clearingLogs = true
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    AppFileLogger.clear()
                                    io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics.clear()
                                }
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_clear_logs_done),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showClearLogsDialog = false
                            } catch (throwable: Throwable) {
                                if (throwable is CancellationException) throw throwable
                                Toast.makeText(
                                    context.applicationContext,
                                    throwable.message?.takeIf { it.isNotBlank() }
                                        ?: context.getString(R.string.settings_export_logs_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                clearingLogs = false
                            }
                        }
                    },
                )
            }
        }

}
