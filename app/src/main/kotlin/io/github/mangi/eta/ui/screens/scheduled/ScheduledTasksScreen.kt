package io.github.mangi.eta.ui.screens.scheduled

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskHealthChecker
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskPresets
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import io.github.mangi.eta.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.SwitchPreference

@Composable
internal fun ScheduledTasksScreen(onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val tasks by remember(context) { ScheduledTaskRepository.observeTasks(context) }
        .collectAsState(initial = null as List<ScheduledTask>?)
    val running by ScheduledTaskRunner.activeTaskIds.collectAsState()
    var health by remember(context) { mutableStateOf(ScheduledTaskHealthChecker.inspect(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var updating by remember { mutableStateOf(emptySet<String>()) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = ScheduledTaskHealthChecker.inspect(context)
                now = System.currentTimeMillis()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    MiuixScaffoldPage(title = "定时任务", onBack = onBack, actions = {
        TextButton(text = "新建", onClick = { onNavigate(AppRoute.ScheduledTaskEditor()) })
    }) {
        if (!health.allHealthy) item(key = "health") {
            SmallTitle(text = "后台运行检查")
            ScheduledPageCard {
                fun openSettings(primary: android.content.Intent, fallback: android.content.Intent) {
                    val opened = runCatching { context.startActivity(primary) }
                        .recoverCatching { context.startActivity(fallback) }
                    if (opened.isFailure) Toast.makeText(context, "打开系统设置失败", Toast.LENGTH_SHORT).show()
                }
                if (!health.canScheduleExactAlarms) ArrowPreference(
                    title = "允许精确闹钟", summary = "改善定时触发的准时性",
                    onClick = { openSettings(ScheduledTaskHealthChecker.exactAlarmSettingsIntent(context), ScheduledTaskHealthChecker.appDetailsIntent(context)) },
                )
                if (!health.ignoringBatteryOptimizations) ArrowPreference(
                    title = "解除电池优化限制", summary = "让任务在设备休眠时继续运行",
                    onClick = { openSettings(ScheduledTaskHealthChecker.batteryOptimizationIntent(context), ScheduledTaskHealthChecker.batterySettingsFallbackIntent(context)) },
                )
                if (!health.notificationsEnabled) ArrowPreference(
                    title = "开启通知", summary = "接收任务结果和失败提醒",
                    onClick = { openSettings(ScheduledTaskHealthChecker.notificationSettingsIntent(context), ScheduledTaskHealthChecker.appDetailsIntent(context)) },
                )
            }
        }
        val current = tasks
        if (current == null) item(key = "loading") { ScheduledPageNote("正在加载任务…") }
        else if (current.isEmpty()) item(key = "presets") {
            ScheduledPageNote("还没有定时任务。新建任务，或从模板快速开始。")
            SmallTitle(text = "快速开始")
            ScheduledPageCard {
                ScheduledTaskPresets.all.forEach { preset ->
                    ArrowPreference(title = preset.title, summary = preset.subtitle,
                        onClick = { onNavigate(AppRoute.ScheduledTaskEditor(presetId = preset.id)) })
                }
            }
        } else items(current, key = { "task:${it.id}" }) { task ->
            ScheduledPageCard {
                val status = task.autoDisabledReason ?: ScheduledTaskUiFormatting.statusLabel(task.lastStatus, task.id in running)
                val next = if (task.enabled) ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, now) else "已暂停"
                ArrowPreference(
                    title = task.name,
                    summary = "${ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)} · $next\n$status · ${ScheduledTaskUiFormatting.deliveryBadge(task.deliveryMode)}",
                    onClick = { onNavigate(AppRoute.ScheduledTaskDetail(task.id)) },
                )
                SwitchPreference(title = "启用", checked = task.enabled, enabled = task.id !in updating,
                    onCheckedChange = { enabled ->
                        updating = updating + task.id
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ScheduledTaskRepository.setEnabled(task.id, enabled, context = context) }
                                now = System.currentTimeMillis()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "更新任务失败", Toast.LENGTH_SHORT).show()
                            } finally {
                                updating = updating - task.id
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ScheduledPageCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
}

@Composable
internal fun ScheduledPageNote(text: String) {
    Text(text = text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
}
