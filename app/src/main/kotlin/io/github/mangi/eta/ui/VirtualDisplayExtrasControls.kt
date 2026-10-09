package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.VirtualDisplayFloatingWindow

/**
 * 「Agent 任务偏好」页里的虚拟屏分区。
 *
 * 布局原则：一个分区一张卡、每项一行；需要多选一的设置（空闲清理）压成单行 + 弹窗选择，
 * 避免把整页撑成一长串单选行。所有偏好与「任务执行位置」共用同一个偏好文件。
 */
@Composable
internal fun VirtualDisplayExtrasControls() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var idleMinutes by remember { mutableIntStateOf(prefs.getInt(IDLE_KEY, DEFAULT_IDLE_MINUTES)) }
    var allowScreenOff by remember { mutableStateOf(prefs.getBoolean(SCREEN_OFF_KEY, false)) }
    var floatingWindow by remember { mutableStateOf(prefs.getBoolean(FLOAT_KEY, false)) }
    var idlePickerOpen by remember { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.vd_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vd_status_title)) },
                supportingContent = { Text(stringResource(R.string.vd_status_summary)) },
                modifier = Modifier.clickable { viewerOpen = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vd_float_title)) },
                supportingContent = { Text(stringResource(R.string.vd_float_summary)) },
                trailingContent = {
                    Switch(
                        checked = floatingWindow,
                        onCheckedChange = { next ->
                            val applied = if (next) {
                                VirtualDisplayFloatingWindow.show(context)
                            } else {
                                VirtualDisplayFloatingWindow.hide(context)
                                true
                            }
                            if (applied) {
                                floatingWindow = next
                                prefs.edit().putBoolean(FLOAT_KEY, next).apply()
                            } else {
                                Toast.makeText(context, R.string.vd_float_denied, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vd_screen_off_title)) },
                supportingContent = { Text(stringResource(R.string.vd_screen_off_summary)) },
                trailingContent = {
                    Switch(
                        checked = allowScreenOff,
                        onCheckedChange = { next ->
                            allowScreenOff = next
                            prefs.edit().putBoolean(SCREEN_OFF_KEY, next).apply()
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.vd_idle_title)) },
                supportingContent = { Text(idleLabel(idleMinutes)) },
                modifier = Modifier.clickable { idlePickerOpen = true },
            )
        }
    }

    if (idlePickerOpen) {
        AlertDialog(
            onDismissRequest = { idlePickerOpen = false },
            title = { Text(stringResource(R.string.vd_idle_title)) },
            text = {
                Column {
                    IDLE_CHOICES.forEach { minutes ->
                        ListItem(
                            headlineContent = { Text(idleLabel(minutes)) },
                            trailingContent = {
                                RadioButton(selected = idleMinutes == minutes, onClick = null)
                            },
                            modifier = Modifier.clickable {
                                idleMinutes = minutes
                                prefs.edit().putInt(IDLE_KEY, minutes).apply()
                                idlePickerOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { idlePickerOpen = false }) {
                    Text(stringResource(R.string.vd_viewer_close))
                }
            },
        )
    }

    if (viewerOpen) {
        VirtualDisplayViewerDialog(onDismiss = { viewerOpen = false })
    }
}

@Composable
private fun idleLabel(minutes: Int): String =
    if (minutes <= NEVER_MINUTES) {
        stringResource(R.string.vd_idle_never)
    } else {
        stringResource(R.string.vd_idle_minutes, minutes)
    }

/** 与 AgentTaskSurface 共用的偏好文件与键名。 */
internal const val PREFERENCES = "eta_agent_preferences"
internal const val IDLE_KEY = "agent_virtual_display_idle_timeout_minutes"
internal const val SCREEN_OFF_KEY = "agent_virtual_display_allow_screen_off"
internal const val FLOAT_KEY = "agent_virtual_display_floating_window"
internal const val DEFAULT_IDLE_MINUTES = 20
internal const val NEVER_MINUTES = 0
private val IDLE_CHOICES = listOf(10, 20, 60, NEVER_MINUTES)
