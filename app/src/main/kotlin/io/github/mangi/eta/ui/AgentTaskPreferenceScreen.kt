package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「Agent 任务偏好」页：与其它设置页统一用 Miuix 骨架（MiuixScaffoldPage + Card + 偏好行）。
 *
 * 这一页曾经是 Material 3 的 Scaffold/Card/ListItem/RadioButton，和全应用其余页面（Miuix 圆角卡、
 * Miuix 排版与开关）摆在一起明显割裂；这里换成 Miuix。选择语义完全不变：
 * 可选项仍由 [AgentTaskSurface.allowsPersist] 决定，整行点击仍写同一份持久化值。
 */
@Composable
internal fun AgentTaskPreferenceScreen(
    onBack: () -> Unit,
    onRecoveryWorkingChanged: (Boolean) -> Unit = {},
) {
    val moduleInstalled = rememberTaskBackendInstalled()
    var previouslyInstalled by remember { mutableStateOf(false) }
    var recoveryWorking by remember { mutableStateOf(false) }
    LaunchedEffect(moduleInstalled, recoveryWorking) {
        if (moduleInstalled == true) previouslyInstalled = true
        if (moduleInstalled == false && !recoveryWorking) onBack()
    }
    // A RESUMED recheck temporarily returns null. Do not dispose the browser's preview controls.
    if (!previouslyInstalled && moduleInstalled != true) return

    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    MiuixScaffoldPage(
        title = stringResource(R.string.agent_task_surface_title),
        onBack = { if (!recoveryWorking) onBack() },
    ) {
        item {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(modifier = Modifier.selectableGroup()) {
                    AgentTaskSurfaceMode.entries.forEach { mode ->
                        TaskSurfaceModeRow(
                            mode = mode,
                            selected = selected == mode,
                            onSelect = { selected = it },
                        )
                    }
                }
            }
        }
        item { VirtualDisplayExtrasControls() }
        item {
            VirtualDisplayRecoveryControls(onWorkingChanged = {
                recoveryWorking = it
                onRecoveryWorkingChanged(it)
            })
        }
    }
}

/** 一行单选：整行可点，选中项右侧给对勾；不可选的项只置灰、不写偏好。 */
@Composable
private fun TaskSurfaceModeRow(
    mode: AgentTaskSurfaceMode,
    selected: Boolean,
    onSelect: (AgentTaskSurfaceMode) -> Unit,
) {
    val view = LocalView.current
    val canSelect = AgentTaskSurface.allowsPersist(mode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = canSelect,
                role = Role.RadioButton,
                onClick = {
                    if (!canSelect) return@selectable
                    TouchHaptics.click(view)
                    AgentTaskSurface.save(mode)
                    onSelect(mode)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(mode.labelRes),
                style = MiuixTheme.textStyles.body1,
                color = if (canSelect) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Text(
                text = stringResource(mode.hintRes),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 每项的执行说明。ASK 首次为其它应用确定位置时弹窗；代鱼自身直接前台，不改普通应用选择。 */
private val AgentTaskSurfaceMode.hintRes: Int
    get() = when (this) {
        AgentTaskSurfaceMode.ASK -> R.string.agent_task_preference_mode_hint_ask
        AgentTaskSurfaceMode.FOREGROUND -> R.string.agent_task_preference_mode_hint_foreground
        AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_preference_mode_hint_background
    }
