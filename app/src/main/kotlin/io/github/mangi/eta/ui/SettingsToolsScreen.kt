package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.kimi.KimiPermissionMode
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 设置二级页：工具。
 *
 * 内容全部取自 Yunke 旧一级页的 section_tools（原样搬运，顺序、图标、条件渲染不变），
 * 不是照抄 Number444 的 SettingsToolsScreen.kt：
 * 不搬网页搜索开关（AGENT_WEB_SEARCH，搜索 lane 的，Yunke Prefs.kt 里没有）与
 * 网页搜索与读取设置入口（AppRoute.WebToolsSettings，Yunke 没有这个路由）；
 * 不把 Root 与系统增强从权限分组挪到工具分组——那是 Number444 的分类习惯，
 * Yunke 用户已经习惯它在权限健康页里（PermissionHealthScreen 的 root 项），
 * 挪了反而 confusing。
 */
@Composable
internal fun SettingsToolsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val taskBackendInstalled = rememberTaskBackendInstalled()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var kimiPermissionMode by remember(agentPrefs) {
        mutableStateOf(KimiPermissionMode.resolve(agentPrefs?.getString(Prefs.Keys.KIMI_PERMISSION_MODE, KimiPermissionMode.YOLO)))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_tool_a72ef1),
        onBack = onBack,
    ) {
        item(key = "tools") {
            SmallTitle(stringResource(R.string.ui_tool_a72ef1))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_tools_list),
                    startAction = { PreferenceIcon(Icons.Rounded.Dashboard) },
                    onClick = { onNavigate(AppRoute.Tools) },
                )

                if (taskBackendInstalled == true) {
                    ArrowPreference(
                        title = stringResource(R.string.agent_task_surface_title),
                        startAction = { PreferenceIcon(Icons.Rounded.Layers) },
                        onClick = { onNavigate(AppRoute.AgentTaskPreference) },
                    )
                }

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_enable_web_browsing_tools_8b6b03),
                    key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                    icon = Icons.Rounded.Language,
                )

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_enable_device_direct_tools_e2d595),
                    key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                    icon = Icons.Rounded.Smartphone,
                )

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_allow_reading_of_sensitive_device_information_feaec0),
                    key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                    icon = Icons.Rounded.Visibility,
                )

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_allow_sensitive_device_operation_3d42ea),
                    key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                    icon = Icons.Rounded.GppMaybe,
                )

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_enable_terminal_file_tools_18bb43),
                    key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                    icon = Icons.Rounded.Terminal,
                )
                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.settings_kimi_web_builtin_browser),
                    key = Prefs.Keys.KIMI_WEB_USE_BUILTIN_BROWSER,
                    icon = Icons.Rounded.Language,
                )

                WindowSpinnerPreference(
                    title = stringResource(R.string.settings_kimi_auth_title),
                    summary = stringResource(R.string.settings_kimi_auth_summary),
                    items = listOf(
                        stringResource(R.string.settings_kimi_auth_yolo),
                        stringResource(R.string.settings_kimi_auth_auto),
                        stringResource(R.string.settings_kimi_auth_manual),
                    ).map { DropdownItem(text = it) },
                    selectedIndex = KimiPermissionMode.all.indexOf(kimiPermissionMode).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val mode = KimiPermissionMode.all.getOrNull(index)
                            ?: return@WindowSpinnerPreference
                        val targetPrefs = agentPrefs ?: return@WindowSpinnerPreference
                        if (putStringSync(targetPrefs, Prefs.Keys.KIMI_PERMISSION_MODE, mode)) {
                            kimiPermissionMode = mode
                        } else {
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.settings_write_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.Lock, enabled = agentPrefs != null)
                    },
                    enabled = agentPrefs != null,
                )

                ArrowPreference(
                    title = stringResource(R.string.ui_linux_tool_environment_314d22),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.Inventory2,
                        )
                    },
                    onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                )

                ArrowPreference(
                    title = stringResource(R.string.capability_workspace),
                    summary = stringResource(R.string.capability_workspace_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Folder) },
                    onClick = { onNavigate(AppRoute.Workspace) },
                )
            }
        }
    }
}
