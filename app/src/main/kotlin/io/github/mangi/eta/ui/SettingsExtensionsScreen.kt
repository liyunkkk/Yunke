package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 设置二级页：扩展与角色。
 *
 * 内容取自 Yunke 旧一级页的 section_context_extensions（原样搬运，顺序、图标不变），
 * 只在首位加了“记忆”入口（Number444 扩展页的第一项；Yunke 已有 AppRoute.Memory，
 * 文案 ui_memory_b55ff5 在中/英/繁三处都有）。
 * 不搬 Number444 的“角色”行：Yunke 没有角色系统（无 Characters 路由、无后端）。
 * 顺序按 Yunke 现有扩展分组（鲸鱼女仆、助手、Skills、MCP），不按 Number444 的
 * 记忆、Skills、MCP、角色。
 */
@Composable
internal fun SettingsExtensionsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.settings_category_extensions),
        onBack = onBack,
    ) {
        item(key = "context_extensions") {
            SmallTitle(stringResource(R.string.settings_context_extensions))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.ui_memory_b55ff5),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                        )
                    },
                    onClick = { onNavigate(AppRoute.Memory) },
                )

                ArrowPreference(
                    title = stringResource(R.string.whale_maid_title),
                    startAction = { PreferenceIcon(icon = Icons.Rounded.Pets) },
                    onClick = { onNavigate(AppRoute.WhaleMaid) },
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_assistants),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.SmartToy,
                        )
                    },
                    onClick = { onNavigate(AppRoute.Assistants()) },
                )

                ArrowPreference(
                    title = stringResource(R.string.route_skills),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.Extension,
                        )
                    },
                    onClick = { onNavigate(AppRoute.Skills) },
                )

                ArrowPreference(
                    title = stringResource(R.string.route_mcp_servers),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.AccountTree,
                        )
                    },
                    onClick = { onNavigate(AppRoute.McpServers) },
                )
            }
        }
    }
}
