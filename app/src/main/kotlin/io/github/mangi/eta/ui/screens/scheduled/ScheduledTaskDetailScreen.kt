package io.github.mangi.eta.ui.screens.scheduled

import android.widget.Toast
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private enum class PendingConfirmation { DELETE, CLEAR_MEMO }

@Composable
internal fun ScheduledTaskDetailScreen(
    taskId: String,
    onNavigate: (AppRoute) -> Unit,
    onOpenRun: (ScheduledTaskRun, ScheduledTask, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by remember(context, taskId) {
        ScheduledTaskRepository.observeTask(taskId, context).map { TaskQuery(it) }
    }.collectAsState(initial = null as TaskQuery?)
    val runs by remember(context, taskId) { ScheduledTaskRepository.observeRuns(taskId, context = context) }
        .collectAsState(initial = emptyList())
    val activeIds by ScheduledTaskRunner.activeTaskIds.collectAsState()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val task = result?.task
    val isRunning = taskId in activeIds
    var busy by remember(taskId) { mutableStateOf(false) }
    var confirm by remember(taskId) { mutableStateOf<PendingConfirmation?>(null) }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "定时任务操作失败", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    MiuixScaffoldPage(title = task?.name ?: "任务详情", onBack = onBack, actions = {
        if (task != null) TextButton(text = "编辑", enabled = !busy,
            onClick = { onNavigate(AppRoute.ScheduledTaskEditor(taskId = taskId)) })
    }) {
        if (task == null) item(key = "missing") {
            ScheduledPageNote(if (result == null) "正在加载任务…" else "此任务不存在或已删除。")
        } else {
            item(key = "overview") {
                ScheduledPageCard {
                    SwitchPreference(title = "启用定时触发", checked = task.enabled, enabled = !busy,
                        onCheckedChange = { enabled -> act {
                            withContext(Dispatchers.IO) { ScheduledTaskRepository.setEnabled(taskId, enabled, context = context) }
                        } })
                    val next = if (task.enabled) ScheduledTaskScheduleCalculator.formatNextRunLabel(task.nextRunAt, System.currentTimeMillis()) else "已暂停"
                    ScheduledPageNote("${ScheduledTaskScheduleCalculator.describeSchedule(task.schedule)} · $next\n" +
                        ScheduledTaskUiFormatting.statusLabel(task.lastStatus, isRunning))
                    task.autoDisabledReason?.let { ScheduledPageNote(it) }
                    ArrowPreference(
                        title = if (isRunning) "停止运行" else "立即试跑",
                        summary = if (isRunning) null else "试跑不会改变原定排期",
                        enabled = !busy,
                        onClick = { act {
                            val message = if (isRunning) {
                                if (ScheduledTaskRunner.cancelRunningTask(context, taskId)) "正在停止任务" else "任务已结束"
                            } else when (withContext(Dispatchers.IO) { ScheduledTaskRunner.triggerManualRun(context, taskId) }) {
                                ScheduledTaskRunner.ManualTriggerResult.Started -> "已启动试跑"
                                ScheduledTaskRunner.ManualTriggerResult.AlreadyRunning -> "任务已在运行"
                                ScheduledTaskRunner.ManualTriggerResult.NotFound -> "任务不存在"
                                ScheduledTaskRunner.ManualTriggerResult.BackgroundStartDenied -> "系统拒绝后台启动，请检查电池后台运行权限"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } },
                    )
                }
            }
            item(key = "settings") {
                SmallTitle(text = "任务设置")
                ScheduledPageCard {
                    val model = providers.asSequence().filter { it.isEnabled }.mapNotNull { provider ->
                        provider.models.firstOrNull { it.isEnabled && it.id == task.modelId }
                            ?.let { "${provider.name} / ${it.displayName}" }
                    }.firstOrNull()
                    ScheduledPageNote("模型：${model ?: if (task.modelId == null) "跟随全局默认" else "所选模型不可用，回退全局默认"}\n" +
                        "结果：${ScheduledTaskUiFormatting.deliveryBadge(task.deliveryMode)}\n" +
                        "会话：${ScheduledTaskUiFormatting.sessionModeLabel(task.sessionMode)}\n" +
                        "运行进度通知：${if (task.showRunningNotification) "开启" else "关闭"}")
                    ScheduledPageNote(task.prompt)
                }
            }
            item(key = "history") {
                SmallTitle(text = "运行记录")
                if (runs.isEmpty()) ScheduledPageNote("暂无运行记录。")
            }
            items(runs, key = { "run:${it.id}" }) { run ->
                val live = isRunning && run.status == ScheduledTaskRunStatus.RUNNING
                val title = "${ScheduledTaskUiFormatting.formatTimestamp(run.startedAt)} · ${ScheduledTaskUiFormatting.statusLabel(run.status, live)}"
                val summary = listOf(ScheduledTaskUiFormatting.triggerSourceLabel(run.triggerSource),
                    ScheduledTaskUiFormatting.formatDuration(run.durationMs), run.modelLabel, run.summary)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                ScheduledPageCard {
                    if (run.archiveConversationKey.isNullOrBlank()) ScheduledPageNote("$title\n$summary")
                    else ArrowPreference(title = title, summary = summary, onClick = { onOpenRun(run, task, live) })
                }
            }
            item(key = "manage") {
                SmallTitle(text = "管理")
                ScheduledPageCard {
                    if (task.memo.isNotBlank()) {
                        ArrowPreference(title = "清空任务记忆", summary = task.memo.take(240), enabled = !busy && !isRunning,
                            onClick = { confirm = PendingConfirmation.CLEAR_MEMO })
                    }
                    ArrowPreference(title = "删除任务", enabled = !busy, onClick = { confirm = PendingConfirmation.DELETE })
                }
            }
        }
    }

    confirm?.let { pending ->
        val delete = pending == PendingConfirmation.DELETE
        OverlayDialog(show = true, title = if (delete) "删除任务？" else "清空任务记忆？",
            summary = if (delete) "删除排期和运行记录。已归档的对话仍会保留。" else "下次运行将从空记忆开始。",
            onDismissRequest = { confirm = null }) {
            MiuixDialogActions(confirmText = if (delete) "删除" else "清空", destructive = true,
                onCancel = { confirm = null }, onConfirm = {
                    confirm = null
                    act {
                        if (delete) {
                            ScheduledTaskRunner.cancelRunningTask(context, taskId)
                            withContext(Dispatchers.IO) { ScheduledTaskRepository.deleteTask(taskId, context = context) }
                            onBack()
                        } else withContext(Dispatchers.IO) { ScheduledTaskRepository.updateMemo(taskId, "", context = context) }
                    }
                })
        }
    }
}

private data class TaskQuery(val task: ScheduledTask?)
