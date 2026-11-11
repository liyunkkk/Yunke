package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Vibration
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
 * 设置二级页：通用。
 *
 * 内容全部取自 Yunke 旧一级页的 section_general（原样搬运，顺序、图标不变），
 * 不是照抄 Number444 的 SettingsGeneralScreen.kt：
 * 不搬重新引导（Yunke 没有 AppRoute.Onboarding，那是 onboarding lane 的）、
 * 不搬语言行（Yunke 没有 LanguagePreference 组件）、
 * 不搬数字助理应用（别从系统接管页挪出来，Yunke 的接管页已经有它）。
 */
@Composable
internal fun SettingsGeneralScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.settings_general),
        onBack = onBack,
    ) {
        item(key = "general") {
            SmallTitle(stringResource(R.string.settings_general))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.voice_title),
                    startAction = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                    onClick = { onNavigate(AppRoute.VoiceSettings) },
                )

                ArrowPreference(
                    title = stringResource(R.string.appearance_title),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.Palette,
                        )
                    },
                    onClick = { onNavigate(AppRoute.AppearanceSettings) },
                )

                ArrowPreference(
                    title = stringResource(R.string.stats_page_title),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.BarChart,
                        )
                    },
                    onClick = { onNavigate(AppRoute.UsageStats) },
                )

                ArrowPreference(
                    title = stringResource(R.string.data_backup_title),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.Description,
                        )
                    },
                    onClick = { onNavigate(AppRoute.DataBackup) },
                )

                ArrowPreference(
                    title = stringResource(R.string.haptics_list),
                    summary = stringResource(R.string.haptics_entry_summary),
                    startAction = { PreferenceIcon(Icons.Rounded.Vibration) },
                    onClick = { onNavigate(AppRoute.Haptics) },
                )
            }
        }
    }
}
