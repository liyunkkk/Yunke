package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「Agent 任务偏好」页里的虚拟屏分区。
 *
 * 布局原则：一个分区一张卡、每项一行；需要多选一的设置（空闲清理）压成单行 + 弹窗选择，
 * 避免把整页撑成一长串单选行。所有偏好与「任务执行位置」共用同一个偏好文件。
 *
 * 与全应用统一用 Miuix 的卡片与偏好行（此前是 Material 3 的 Card/ListItem/Switch/AlertDialog，
 * 圆角、排版与开关样式都和别的设置页对不上）。
 */
@Composable
internal fun VirtualDisplayExtrasControls() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var idleMinutes by remember { mutableIntStateOf(prefs.getInt(IDLE_KEY, DEFAULT_IDLE_MINUTES)) }
    var allowScreenOff by remember { mutableStateOf(prefs.getBoolean(SCREEN_OFF_KEY, false)) }
    var floatingWindow by remember { mutableStateOf(prefs.getBoolean(FLOAT_KEY, false)) }
    var conflictTakeover by remember { mutableStateOf(prefs.getBoolean(TAKEOVER_KEY, false)) }
    var viewerOpen by remember { mutableStateOf(false) }

    Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.vd_section_title),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            ArrowPreference(
                title = stringResource(R.string.vd_status_title),
                summary = stringResource(R.string.vd_status_summary),
                onClick = { viewerOpen = true },
            )
            SwitchPreference(
                title = stringResource(R.string.vd_float_title),
                summary = stringResource(R.string.vd_float_summary),
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
            SwitchPreference(
                title = stringResource(R.string.vd_screen_off_title),
                summary = stringResource(R.string.vd_screen_off_summary),
                checked = allowScreenOff,
                onCheckedChange = { next ->
                    allowScreenOff = next
                    prefs.edit().putBoolean(SCREEN_OFF_KEY, next).apply()
                },
            )
            SwitchPreference(
                title = stringResource(R.string.vd_takeover_title),
                summary = stringResource(R.string.vd_takeover_summary),
                checked = conflictTakeover,
                onCheckedChange = { next ->
                    conflictTakeover = next
                    prefs.edit().putBoolean(TAKEOVER_KEY, next).apply()
                },
            )
            WindowSpinnerPreference(
                title = stringResource(R.string.vd_idle_title),
                summary = idleLabel(idleMinutes),
                items = IDLE_CHOICES.map { minutes -> DropdownItem(text = idleLabel(minutes)) },
                selectedIndex = IDLE_CHOICES.indexOf(idleMinutes).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    val minutes = IDLE_CHOICES.getOrNull(index) ?: return@WindowSpinnerPreference
                    idleMinutes = minutes
                    prefs.edit().putInt(IDLE_KEY, minutes).apply()
                },
            )
        }
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
internal const val TAKEOVER_KEY = "agent_virtual_display_conflict_takeover"
internal const val DEFAULT_IDLE_MINUTES = 20
internal const val NEVER_MINUTES = 0
private val IDLE_CHOICES = listOf(10, 20, 60, NEVER_MINUTES)
