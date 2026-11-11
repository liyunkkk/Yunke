package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
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
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.settingsSectionDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 设置二级页：诊断。
 *
 * 内容从一级页的 section_diagnostics 原样搬出（用户要求：诊断和关于也做成二级页，
 * 与工具/通用一致）。顺序、图标、开关行为都没改。
 */
@Composable
internal fun SettingsDiagnosticsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
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

    MiuixScaffoldPage(
        title = stringResource(R.string.settings_diagnostics),
        onBack = onBack,
    ) {
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
    }

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
