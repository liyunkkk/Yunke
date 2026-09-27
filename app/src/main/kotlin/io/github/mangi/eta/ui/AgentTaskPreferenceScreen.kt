package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
internal fun AgentTaskPreferenceScreen(onBack: () -> Unit) {
    val moduleInstalled = rememberTaskBackendInstalled()
    var previouslyInstalled by remember { mutableStateOf(false) }
    var recoveryWorking by remember { mutableStateOf(false) }
    LaunchedEffect(moduleInstalled, recoveryWorking) {
        if (moduleInstalled == true) previouslyInstalled = true
        if (moduleInstalled == false && !recoveryWorking) onBack()
    }
    // A RESUMED recheck temporarily returns null. Do not dispose the browser's preview controls.
    if (!previouslyInstalled && moduleInstalled != true) return

    val view = LocalView.current
    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    MiuixScaffoldPage(
        title = stringResource(R.string.agent_task_surface_title),
        onBack = { if (!recoveryWorking) onBack() },
    ) {
        item(key = "agent_task_modes") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                AgentTaskSurfaceMode.entries.forEach { mode ->
                    val canSelect = AgentTaskSurface.allowsPersist(mode)
                    BasicComponent(
                        title = stringResource(mode.labelRes),
                        onClick = {
                            if (!canSelect) return@BasicComponent
                            TouchHaptics.click(view)
                            selected = mode
                            AgentTaskSurface.save(mode)
                        },
                        endActions = {
                            RadioButton(
                                selected = selected == mode,
                                enabled = canSelect || selected == mode,
                                onClick = {
                                    if (!canSelect) return@RadioButton
                                    TouchHaptics.click(view)
                                    selected = mode
                                    AgentTaskSurface.save(mode)
                                },
                            )
                        },
                    )
                }
            }
        }
        item(key = "virtual_display_controls") {
            VirtualDisplayRecoveryControls(onWorkingChanged = { recoveryWorking = it })
        }
    }
}
