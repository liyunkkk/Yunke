package io.github.mangi.eta.ui.screens.scheduled

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskPresets
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import io.github.mangi.eta.ui.components.SwitchPreference

private val OnceFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val TriggerLabels = listOf("一次性", "每天", "工作日", "每周", "固定间隔", "Cron")

@Composable
internal fun ScheduledTaskEditorScreen(
    taskId: String? = null,
    presetId: String? = null,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = taskId?.takeIf(String::isNotBlank)
    val preset = remember(presetId) { ScheduledTaskPresets.find(presetId) }
    var draft by remember(id, presetId) {
        mutableStateOf(if (id != null) null else ScheduledTask(
            id = "", name = preset?.defaultName.orEmpty(), prompt = preset?.prompt.orEmpty(),
            schedule = preset?.schedule ?: ScheduledTaskSchedule(),
            deliveryMode = preset?.deliveryMode ?: ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = preset?.sessionMode ?: ScheduledTaskSessionMode.ISOLATED,
            capabilities = preset?.capabilities ?: ScheduledTaskCapabilities(),
        ))
    }
    var originalEnabled by remember(id) { mutableStateOf<Boolean?>(null) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    var saveError by remember(id, presetId) { mutableStateOf<String?>(null) }
    var saving by remember(id, presetId) { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != null) try {
            draft = withContext(Dispatchers.IO) { ScheduledTaskRepository.getTask(id, context) }
            originalEnabled = draft?.enabled
            if (draft == null) loadError = "此任务不存在或已删除。"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "读取任务失败"
        }
    }
    val current = draft
    val schedule = current?.schedule
    val zone = schedule?.let { ScheduledTaskScheduleCalculator.resolveZone(it) } ?: ZoneId.systemDefault()
    var onceText by remember(id, presetId, schedule?.triggerAtEpochMs) {
        val epoch = schedule?.triggerAtEpochMs ?: System.currentTimeMillis() + 3_600_000L
        mutableStateOf(Instant.ofEpochMilli(epoch).atZone(zone).format(OnceFormat))
    }
    var interval by remember(id, presetId, schedule?.intervalMinutes) { mutableStateOf((schedule?.intervalMinutes ?: 60).toString()) }
    var time by remember(id, presetId, schedule?.hour, schedule?.minute) { mutableStateOf(editorTime(schedule?.hour ?: 9, schedule?.minute ?: 0)) }
    var timeout by remember(id, presetId, current?.timeoutSeconds) { mutableStateOf((current?.timeoutSeconds ?: 300).toString()) }
    var window by remember(id, presetId, current?.misfireWindowMinutes) { mutableStateOf((current?.misfireWindowMinutes ?: 180).toString()) }
    var quietStart by remember(id, presetId, current?.quietHours) { mutableStateOf(editorTime(current?.quietHours?.startHour ?: 23, current?.quietHours?.startMinute ?: 0)) }
    var quietEnd by remember(id, presetId, current?.quietHours) { mutableStateOf(editorTime(current?.quietHours?.endHour ?: 7, current?.quietHours?.endMinute ?: 30)) }
    var advanced by remember(id, presetId) { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val defaultModel by remember { RuntimeConfigRepository.selectedModelIdFlow() }.collectAsState(initial = null)
    val models = remember(providers) {
        providers.filter { it.isEnabled }.flatMap { provider ->
            provider.models.filter { it.isEnabled && !it.supportsImageGeneration && !it.supportsVideoGeneration && !it.supportsSpeechSynthesis }
                .map { provider to it }
        }
    }
    val effectiveModel = models.firstOrNull { it.second.id == (current?.modelId ?: defaultModel) }
    val efforts = remember(effectiveModel) {
        listOf<ReasoningEffort?>(null) + effectiveModel?.let { (provider, model) ->
            RuntimeConfigRepository.buildRuntimeConfig(provider, model).reasoningCapabilities?.selectableEfforts
        }.orEmpty()
    }
    val normalized = remember(current, onceText, interval, time, timeout, window, quietStart, quietEnd, zone) {
        current?.let { value -> runCatching {
            val original = value.schedule
            var next = original.copy(timezoneId = original.timezoneId.trim())
            next = when (original.kind) {
                ScheduledTaskTriggerKind.ONCE -> {
                    val oldEpoch = original.triggerAtEpochMs
                    val local = LocalDateTime.parse(onceText.trim(), OnceFormat)
                    val offset = zone.rules.getValidOffsets(local).firstOrNull() ?: error("所填时间在该时区不存在")
                    val epoch = if (oldEpoch != null && Instant.ofEpochMilli(oldEpoch).atZone(zone).format(OnceFormat) == onceText.trim())
                        oldEpoch else local.toInstant(offset).toEpochMilli()
                    next.copy(triggerAtEpochMs = epoch)
                }
                ScheduledTaskTriggerKind.INTERVAL -> next.copy(intervalMinutes = interval.trim().toIntOrNull() ?: error("请输入有效的间隔分钟数"))
                ScheduledTaskTriggerKind.CRON -> next.copy(cronExpression = original.cronExpression.trim())
                else -> {
                    val (hour, minute) = parseEditorTime(time)
                    next.copy(hour = hour, minute = minute)
                }
            }
            val quiet = if (value.quietHours.enabled && next.kind != ScheduledTaskTriggerKind.ONCE) {
                val (sh, sm) = parseEditorTime(quietStart)
                val (eh, em) = parseEditorTime(quietEnd)
                require(sh != eh || sm != em) { "免打扰时段的开始和结束时间应不同" }
                value.quietHours.copy(startHour = sh, startMinute = sm, endHour = eh, endMinute = em)
            } else value.quietHours
            val timeoutSeconds = timeout.trim().toIntOrNull()
            require(timeoutSeconds != null && timeoutSeconds in ScheduledTask.MIN_TIMEOUT_SECONDS..ScheduledTask.MAX_TIMEOUT_SECONDS) { "超时需为 30–1800 秒" }
            val windowMinutes = if (value.misfirePolicy == ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW) window.trim().toIntOrNull() else value.misfireWindowMinutes
            require(windowMinutes != null && windowMinutes in 5..1440) { "补跑窗口需为 5–1440 分钟" }
            value.copy(schedule = next, quietHours = quiet, timeoutSeconds = timeoutSeconds, misfireWindowMinutes = windowMinutes)
        } }
    }
    val ready = normalized?.getOrNull()
    val now = remember(ready) { System.currentTimeMillis() }
    val scheduleError = ready?.let { validateEditorSchedule(it, now) }
    val preview = remember(ready, now, scheduleError) {
        if (ready != null && scheduleError == null && ready.enabled) {
            ScheduledTaskScheduleCalculator.computeNextRunAt(ready.schedule, now, ready.quietHours)
        } else null
    }
    fun change(transform: (ScheduledTask) -> ScheduledTask) {
        if (!saving) {
            draft = draft?.let(transform)
            saveError = null
        }
    }
    fun save() {
        if (saving || ready == null || ready.name.isBlank() || ready.prompt.isBlank()) return
        validateEditorSchedule(ready, System.currentTimeMillis())?.let { saveError = it; return }
        saving = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val latest = if (id != null) ScheduledTaskRepository.getTask(id, context) ?: error("此任务已删除") else ready
                    val merged = latest.copy(name = ready.name, prompt = ready.prompt,
                        enabled = if (originalEnabled == ready.enabled && id != null) latest.enabled else ready.enabled,
                        schedule = ready.schedule, quietHours = ready.quietHours, deliveryMode = ready.deliveryMode,
                        sessionMode = ready.sessionMode, showRunningNotification = ready.showRunningNotification,
                        misfirePolicy = ready.misfirePolicy, misfireWindowMinutes = ready.misfireWindowMinutes,
                        modelId = ready.modelId, reasoningEffort = ready.reasoningEffort,
                        capabilities = ready.capabilities, timeoutSeconds = ready.timeoutSeconds)
                    ScheduledTaskRepository.saveTask(merged, context = context)
                }
                onSaved(saved.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }
    MiuixScaffoldPage(title = if (id == null) "新建定时任务" else "编辑定时任务", onBack = { if (!saving) onBack() },
        listModifier = Modifier.imePadding(), actions = {
            TextButton(text = if (saving) "保存中" else "保存", onClick = ::save,
                enabled = !saving && ready != null && ready.name.isNotBlank() && ready.prompt.isNotBlank() && scheduleError == null)
        }) {
        if (current == null) item(key = "loading") { ScheduledPageNote(loadError ?: "正在加载任务…") }
        else {
            item(key = "task") {
                ScheduledPageCard {
                    Column {
                        EditorField("任务名称", current.name) { value -> change { it.copy(name = value) } }
                        EditorField("任务指令", current.prompt, singleLine = false) { value -> change { it.copy(prompt = value) } }
                    }
                }
            }
            item(key = "schedule") {
                SmallTitle(text = "排期")
                ScheduledPageCard {
                    val rule = current.schedule
                    SwitchPreference(title = "启用定时触发", checked = current.enabled,
                        onCheckedChange = { value -> change { it.copy(enabled = value) } })
                    OverlayDropdownPreference(title = "触发方式", items = TriggerLabels, selectedIndex = rule.kind.ordinal,
                        onSelectedIndexChange = { index -> change { it.copy(schedule = it.schedule.copy(kind = ScheduledTaskTriggerKind.entries[index])) } })
                    when (rule.kind) {
                        ScheduledTaskTriggerKind.ONCE -> EditorField("触发时间（YYYY-MM-DD HH:mm）", onceText) { onceText = it }
                        ScheduledTaskTriggerKind.INTERVAL -> EditorField("间隔分钟数", interval) { interval = it }
                        ScheduledTaskTriggerKind.CRON -> EditorField("Cron（分钟 小时 日 月 星期）", rule.cronExpression) { value -> change { it.copy(schedule = it.schedule.copy(cronExpression = value)) } }
                        else -> {
                            EditorField("运行时间（HH:mm）", time) { time = it }
                            if (rule.kind == ScheduledTaskTriggerKind.WEEKLY) {
                                listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { index, label ->
                                    val day = index + 1
                                    SwitchPreference(title = label, checked = day in rule.daysOfWeek,
                                        onCheckedChange = { checked -> change { value ->
                                            val days = value.schedule.daysOfWeek
                                            value.copy(schedule = value.schedule.copy(daysOfWeek = if (checked) days + day else days - day))
                                        } })
                                }
                            }
                        }
                    }
                    EditorField("时区（IANA，留空跟随设备）", rule.timezoneId) { value -> change { it.copy(schedule = it.schedule.copy(timezoneId = value)) } }
                    val hint = saveError ?: normalized?.exceptionOrNull()?.message ?: scheduleError ?: if (current.enabled)
                        "下次运行：${ScheduledTaskScheduleCalculator.formatNextRunLabel(preview, now)}" else "已暂停定时触发"
                    ScheduledPageNote(hint)
                }
            }
            item(key = "delivery") {
                SmallTitle(text = "交付与会话")
                ScheduledPageCard {
                    OverlayDropdownPreference(title = "结果通知", items = ScheduledTaskDeliveryMode.entries.map(ScheduledTaskUiFormatting::deliveryBadge),
                        selectedIndex = current.deliveryMode.ordinal, onSelectedIndexChange = { index -> change { it.copy(deliveryMode = ScheduledTaskDeliveryMode.entries[index]) } })
                    OverlayDropdownPreference(title = "会话归属", items = ScheduledTaskSessionMode.entries.map(ScheduledTaskUiFormatting::sessionModeLabel),
                        selectedIndex = current.sessionMode.ordinal, onSelectedIndexChange = { index -> change { it.copy(sessionMode = ScheduledTaskSessionMode.entries[index]) } })
                    SwitchPreference(title = "显示运行进度通知", summary = "控制执行期间的通知与胶囊；结果通知单独设置",
                        checked = current.showRunningNotification, onCheckedChange = { value -> change { it.copy(showRunningNotification = value) } })
                }
            }
            item(key = "advanced") {
                SmallTitle(text = "高级设置")
                ScheduledPageCard {
                    SwitchPreference(title = "展开高级设置", checked = advanced, onCheckedChange = { advanced = it })
                    if (advanced) {
                        val actual = models.indexOfFirst { it.second.id == current.modelId }
                        val missing = current.modelId != null && actual < 0
                        val modelLabels = listOf("跟随全局默认") + models.map { (provider, model) -> "${provider.name} / ${model.displayName}" } +
                            if (missing) listOf("所选模型已停用 · 回退全局默认") else emptyList()
                        OverlayDropdownPreference(title = "执行模型", items = modelLabels,
                            selectedIndex = if (missing) modelLabels.lastIndex else actual + 1,
                            onSelectedIndexChange = { index ->
                                if (index == 0 || index <= models.size) change { it.copy(modelId = models.getOrNull(index - 1)?.second?.id, reasoningEffort = null) }
                            })
                        val effortIndex = efforts.indexOfFirst { it?.wireValue == current.reasoningEffort }.coerceAtLeast(0)
                        OverlayDropdownPreference(title = "思考等级", items = efforts.map { it?.displayName ?: "跟随全局默认" },
                            selectedIndex = effortIndex, onSelectedIndexChange = { index -> change { it.copy(reasoningEffort = efforts[index]?.wireValue) } })
                        OverlayDropdownPreference(title = "错过触发时", items = listOf("窗口内补跑一次", "跳过"),
                            selectedIndex = current.misfirePolicy.ordinal, onSelectedIndexChange = { index -> change { it.copy(misfirePolicy = ScheduledTaskMisfirePolicy.entries[index]) } })
                        if (current.misfirePolicy == ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW) EditorField("补跑窗口（分钟）", window) { window = it }
                        if (current.schedule.kind != ScheduledTaskTriggerKind.ONCE) {
                            SwitchPreference(title = "免打扰时段", checked = current.quietHours.enabled,
                                onCheckedChange = { checked ->
                                    val previous = draft?.quietHours
                                    change { it.copy(quietHours = it.quietHours.copy(enabled = checked)) }
                                    previous?.let { quietStart = editorTime(it.startHour, it.startMinute); quietEnd = editorTime(it.endHour, it.endMinute) }
                                })
                            if (current.quietHours.enabled) {
                                EditorField("开始（HH:mm）", quietStart) { quietStart = it }
                                EditorField("结束（HH:mm）", quietEnd) { quietEnd = it }
                            }
                        }
                        EditorField("超时（秒）", timeout) { timeout = it }
                        val caps = current.capabilities
                        ScheduledPageNote("工具开关只缩小单个任务的能力，仍受全局设置控制。")
                        SwitchPreference(title = "网页工具", checked = caps.browserTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(browserTools = value)) } })
                        SwitchPreference(title = "设备读取", checked = caps.deviceReadTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(deviceReadTools = value)) } })
                        SwitchPreference(title = "设备操作", checked = caps.deviceActionTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(deviceActionTools = value)) } })
                        SwitchPreference(title = "终端工具", checked = caps.terminalTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(terminalTools = value)) } })
                        SwitchPreference(title = "MCP 工具", checked = caps.mcpTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(mcpTools = value)) } })
                        SwitchPreference(title = "界面操作", checked = caps.guiTools, onCheckedChange = { value -> change { it.copy(capabilities = it.capabilities.copy(guiTools = value)) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    TextField(value = value, onValueChange = onValueChange, label = label, singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp))
}

private fun editorTime(hour: Int, minute: Int) = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

private fun parseEditorTime(text: String): Pair<Int, Int> {
    val parts = text.trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()
    val minute = parts.getOrNull(1)?.toIntOrNull()
    require(parts.size == 2 && hour != null && minute != null && hour in 0..23 && minute in 0..59) { "请输入有效时间（HH:mm）" }
    return hour to minute
}

private fun validateEditorSchedule(task: ScheduledTask, now: Long): String? {
    val validationTime = if (!task.enabled && task.schedule.kind == ScheduledTaskTriggerKind.ONCE) 0L else now
    return ScheduledTaskScheduleCalculator.validateSchedule(task.schedule, validationTime)
}
