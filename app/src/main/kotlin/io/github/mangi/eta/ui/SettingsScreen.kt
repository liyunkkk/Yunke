package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
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
 * - 扩展与角色 → [SettingsExtensionsScreen]（记忆、鲸鱼女仆、助手、Skills、MCP）
 * - 工具 → [SettingsToolsScreen]（工具列表、设备/终端/Kimi 开关、Linux、工作区）
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
            // ── 分类入口 ────────────────────────────────────────────────
            item(key = "section_categories") {
                Card(modifier = Modifier.settingsSectionDiagnostics("categories").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_category_model_conversation),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Psychology)
                        },
                        onClick = { onNavigate(AppRoute.SettingsModel) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_category_extensions),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Extension)
                        },
                        onClick = { onNavigate(AppRoute.SettingsExtensions) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_tool_a72ef1),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Build)
                        },
                        onClick = { onNavigate(AppRoute.SettingsTools) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_general),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Tune)
                        },
                        onClick = { onNavigate(AppRoute.SettingsGeneral) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_category_permissions_notifications),
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.Security)
                        },
                        endActions = {
                            // 未授权计数聚合悬浮窗/无障碍/超级岛三状态，入口可见性不因入二级而丢失。
                            // 岛项本 lane 恒为 CHECKING（不计入），岛 lane 接线后自动生效。
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
                        title = stringResource(R.string.settings_system_takeover_entry),
                        summary = stringResource(R.string.settings_system_takeover_entry_summary),
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AutoAwesome,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AssistantTakeover) },
                    )
                }
            }

            item(key = "section_diagnostics") {
                SmallTitle(stringResource(R.string.settings_diagnostics))
                Card(modifier = Modifier.settingsSectionDiagnostics("diagnostics").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle(stringResource(R.string.ui_about_bed172))
                Card(modifier = Modifier.settingsSectionDiagnostics("about").padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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
