package io.github.mangi.eta.agent.scheduler

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时任务执行协调器。
 *
 * - 统一承接精确闹钟到期触发与 UI/工具触发的「立即试跑」；
 * - 严格复用 [AgentRuntimeClient] / [AgentRuntimeService]，共享工具、权限、MCP、虚拟屏、归档与取消链路；
 * - 负责单任务防重入、Android Doze 10 秒窗口内的 FGS 与有界 WakeLock 接管、锁屏 GUI 守门、
 *   单次硬超时守护、连续失败熔断与结果通知。
 */
internal object ScheduledTaskRunner {
    private const val RUNTIME_STOP_GRACE_MS = 17_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ActiveExecution(
        val taskId: String,
        val runId: String,
        val job: Job,
        val timedOut: AtomicBoolean = AtomicBoolean(false),
        val userCancelled: AtomicBoolean = AtomicBoolean(false),
        val ready: AtomicBoolean = AtomicBoolean(false),
    )

    private val activeByTaskId = ConcurrentHashMap<String, ActiveExecution>()
    private val _activeTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val activeTaskIds: StateFlow<Set<String>> = _activeTaskIds.asStateFlow()

    fun isTaskRunning(taskId: String): Boolean = activeByTaskId.containsKey(taskId)

    /** 取消某个正在运行的定时任务，并同步通知 AgentRuntimeService 停止模型流与工具资源。 */
    fun cancelRunningTask(context: Context, taskId: String): Boolean {
        val active = activeByTaskId[taskId] ?: return false
        active.userCancelled.set(true)
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(active.runId)
            }
            delay(RUNTIME_STOP_GRACE_MS)
            if (activeByTaskId[taskId] === active) active.job.cancel(CancellationException("scheduled task stopped"))
        }
        return true
    }

    /** 系统闹钟触发入口：扫描全部到期任务并按漏跑/安静时段策略调度，再立刻同步下一次闹钟。 */
    suspend fun onAlarmFired(context: Context) {
        val appContext = context.applicationContext
        val nowMs = System.currentTimeMillis()
        val dueTasks = ScheduledTaskRepository.dueTasks(nowMs, appContext)
        for (task in dueTasks) {
            val scheduledFor = task.nextRunAt ?: nowMs
            val decision = ScheduledTaskScheduleCalculator.evaluateMisfire(
                task = task,
                scheduledForAt = scheduledFor,
                nowMs = nowMs,
            )
            when (decision) {
                is ScheduledTaskScheduleCalculator.MisfireDecision.Skip -> {
                    if (!ScheduledTaskRepository.claimOccurrence(task, scheduledFor, nowMs, context = appContext)) continue
                    recordSkippedAndAdvance(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
                        scheduledForAt = scheduledFor,
                        reason = decision.reason,
                        advanceSchedule = false,
                    )
                }
                ScheduledTaskScheduleCalculator.MisfireDecision.RunNow -> {
                    launchExecution(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
                        scheduledForAt = scheduledFor,
                        advanceSchedule = true,
                    )
                }
                ScheduledTaskScheduleCalculator.MisfireDecision.RunCatchUp -> {
                    launchExecution(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.MISFIRE_CATCHUP,
                        scheduledForAt = scheduledFor,
                        advanceSchedule = true,
                    )
                }
            }
        }
        ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
    }

    /** 立即试跑（不修改原计划排期，也不受安静时段限制）。 */
    suspend fun triggerManualRun(context: Context, taskId: String): ManualTriggerResult {
        val appContext = context.applicationContext
        val task = ScheduledTaskRepository.getTask(taskId, appContext)
            ?: return ManualTriggerResult.NotFound
        if (isTaskRunning(taskId)) {
            return ManualTriggerResult.AlreadyRunning
        }
        return when (launchExecution(
            context = appContext,
            task = task,
            triggerSource = ScheduledTaskTriggerSource.MANUAL,
            scheduledForAt = null,
            advanceSchedule = false,
        )) {
            LaunchResult.STARTED -> ManualTriggerResult.Started
            LaunchResult.ALREADY_RUNNING -> ManualTriggerResult.AlreadyRunning
            LaunchResult.BACKGROUND_START_DENIED -> ManualTriggerResult.BackgroundStartDenied
        }
    }

    sealed interface ManualTriggerResult {
        data object Started : ManualTriggerResult
        data object AlreadyRunning : ManualTriggerResult
        data object NotFound : ManualTriggerResult
        data object BackgroundStartDenied : ManualTriggerResult
    }

    private enum class LaunchResult { STARTED, ALREADY_RUNNING, BACKGROUND_START_DENIED }

    private suspend fun launchExecution(
        context: Context,
        task: ScheduledTask,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        advanceSchedule: Boolean,
    ): LaunchResult {
        val startedAt = System.currentTimeMillis()
        val runId = "sched-${UUID.randomUUID()}"
        val ownerJob = Job()
        val active = ActiveExecution(taskId = task.id, runId = runId, job = ownerJob)
        val previous = activeByTaskId.putIfAbsent(task.id, active)
        if (previous != null) {
            ownerJob.complete()
            if (advanceSchedule && scheduledForAt != null && previous.ready.get() &&
                ScheduledTaskRepository.claimOccurrence(task, scheduledForAt, System.currentTimeMillis(), context = context)
            ) {
                recordSkippedAndAdvance(context, task, triggerSource, scheduledForAt,
                    "上一次执行尚未结束，已跳过本次重叠触发", advanceSchedule = false)
            }
            return LaunchResult.ALREADY_RUNNING
        }
        publishActiveTasks()
        val leaseId = "scheduled:$runId"
        val allowBoundFallback = RootAccess.isGranted
        val needForegroundLease = task.showRunningNotification || !canRunSilentlyWithoutForegroundService(context)
        var foregroundAcquired = false
        var wakeLock: PowerManager.WakeLock? = null
        try {
            if (advanceSchedule && scheduledForAt != null &&
                !ScheduledTaskRepository.claimOccurrence(task, scheduledForAt, System.currentTimeMillis(), context = context)
            ) {
                activeByTaskId.remove(task.id, active)
                ownerJob.complete()
                publishActiveTasks()
                return LaunchResult.ALREADY_RUNNING
            }
            if (triggerSource != ScheduledTaskTriggerSource.MANUAL &&
                task.capabilities.guiTools && shouldDeferLockedGuiTask(context)
            ) {
                val reason = "设备当前处于锁屏状态且未启用后台虚拟屏，已改为发送提醒，解锁后点按通知即可执行"
                recordSkippedAndAdvance(context, task, triggerSource, scheduledForAt, reason, advanceSchedule = false)
                activeByTaskId.remove(task.id, active)
                ownerJob.complete()
                publishActiveTasks()
                ScheduledTaskNotificationManager.notifyGuiReadyForUser(context, task, reason)
                return LaunchResult.STARTED
            }
            if (needForegroundLease) {
                foregroundAcquired = AgentExecutionService.acquire(
                    context = context,
                    id = leaseId,
                    allowBoundFallback = allowBoundFallback,
                ) { cancelRunningTask(context, task.id) }
                if (!foregroundAcquired && !allowBoundFallback) {
                    val reason = "系统拒绝在后台启动前台执行服务，请检查精确闹钟与电池无限制权限"
                    activeByTaskId.remove(task.id, active)
                    ownerJob.complete()
                    publishActiveTasks()
                    if (advanceSchedule && scheduledForAt != null) {
                        recordSkippedAndAdvance(context, task, triggerSource, scheduledForAt, reason, advanceSchedule = false)
                    }
                    return LaunchResult.BACKGROUND_START_DENIED
                }
            }
            val timeoutMs = task.timeoutSeconds.coerceIn(
                ScheduledTask.MIN_TIMEOUT_SECONDS,
                ScheduledTask.MAX_TIMEOUT_SECONDS,
            ) * 1000L
            wakeLock = acquireBoundedWakeLock(context, timeoutMs + 30_000L)
            val child = scope.launch(context = ownerJob, start = CoroutineStart.LAZY) {
                val watchdog = launch {
                    delay(timeoutMs)
                    active.timedOut.set(true)
                    runCatching { AgentRuntimeClient(context, AndroidAgentLogger).cancelRun(runId) }
                }
                try {
                    executeInternal(
                        context = context,
                        task = task,
                        runId = runId,
                        triggerSource = triggerSource,
                        scheduledForAt = scheduledForAt,
                        startedAt = startedAt,
                        advanceSchedule = false,
                        timeoutMs = timeoutMs,
                        execution = active,
                    )
                } catch (cancelled: CancellationException) {
                    val finishedAt = System.currentTimeMillis()
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            commitCancelledRun(context, task, runId, triggerSource, scheduledForAt, startedAt, finishedAt)
                        }
                    }.onFailure { failure ->
                        AndroidAgentLogger.warn("Scheduled cancellation commit failed: type=${failure.safeLogType()}")
                    }
                    throw cancelled
                } catch (failure: Throwable) {
                    AndroidAgentLogger.warn("Scheduled task execution crashed: type=${failure.safeLogType()}")
                    val finishedAt = System.currentTimeMillis()
                    val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
                    val commit = ScheduledTaskRepository.recordRunFinished(
                        run = ScheduledTaskRun(
                            id = runId, taskId = task.id, triggerSource = triggerSource,
                            scheduledForAt = scheduledForAt, startedAt = startedAt, finishedAt = finishedAt,
                            status = ScheduledTaskRunStatus.FAILED, summary = reason.take(300), notified = false,
                        ),
                        advanceSchedule = false,
                        nowMs = finishedAt,
                        context = context,
                    )
                    if (commit != null) ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                        context, commit.task, commit.task.autoDisabledReason ?: reason, commit.autoDisabledNow,
                    )
                } finally {
                    watchdog.cancel()
                    ScheduledTaskRunReportRegistry.clear(runId)
                    activeByTaskId.remove(task.id, active)
                    publishActiveTasks()
                    if (foregroundAcquired) AgentExecutionService.release(leaseId)
                    releaseWakeLockSafely(wakeLock)
                    ownerJob.complete()
                }
            }
            active.ready.set(true)
            child.start()
            return LaunchResult.STARTED
        } catch (failure: Throwable) {
            activeByTaskId.remove(task.id, active)
            ownerJob.complete()
            publishActiveTasks()
            if (foregroundAcquired) AgentExecutionService.release(leaseId)
            releaseWakeLockSafely(wakeLock)
            throw failure
        }
    }

    private suspend fun commitCancelledRun(
        context: Context,
        task: ScheduledTask,
        runId: String,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        startedAt: Long,
        finishedAt: Long,
    ) {
        ScheduledTaskRepository.recordRunFinished(
            run = ScheduledTaskRun(
                id = runId, taskId = task.id, triggerSource = triggerSource,
                scheduledForAt = scheduledForAt, startedAt = startedAt, finishedAt = finishedAt,
                status = ScheduledTaskRunStatus.CANCELLED, summary = "任务执行已取消", notified = false,
            ),
            advanceSchedule = false,
            nowMs = finishedAt,
            context = context,
        )
    }

    private suspend fun executeInternal(
        context: Context,
        task: ScheduledTask,
        runId: String,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        startedAt: Long,
        advanceSchedule: Boolean,
        timeoutMs: Long,
        execution: ActiveExecution?,
    ) {
        val archiveKey = when (task.sessionMode) {
            ScheduledTaskSessionMode.DEDICATED ->
                task.dedicatedConversationKey?.takeIf { it.isNotBlank() } ?: "task-${task.id}"
            ScheduledTaskSessionMode.ISOLATED -> runId
        }
        val baseModelConfig = resolveTaskModelConfig(task)
        val preflightStopStatus = when {
            execution?.timedOut?.get() == true -> ScheduledTaskRunStatus.TIMEOUT
            execution?.userCancelled?.get() == true -> ScheduledTaskRunStatus.CANCELLED
            else -> null
        }
        if (preflightStopStatus != null) {
            val finishedAt = System.currentTimeMillis()
            val summary = if (preflightStopStatus == ScheduledTaskRunStatus.TIMEOUT) {
                "任务运行超过 ${task.timeoutSeconds} 秒上限，已自动中止"
            } else "已手动停止"
            ScheduledTaskRepository.recordRunFinished(
                run = ScheduledTaskRun(
                    id = runId, taskId = task.id, triggerSource = triggerSource,
                    scheduledForAt = scheduledForAt, startedAt = startedAt, finishedAt = finishedAt,
                    status = preflightStopStatus, summary = summary, notified = false,
                ),
                advanceSchedule = false, nowMs = finishedAt, context = context,
            )
            return
        }
        if (baseModelConfig == null) {
            val finishedAt = System.currentTimeMillis()
            val message = "未配置可用的模型，请在「模型提供商」中配置后再试"
            val failedRun = ScheduledTaskRun(
                id = runId,
                taskId = task.id,
                triggerSource = triggerSource,
                scheduledForAt = scheduledForAt,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = ScheduledTaskRunStatus.FAILED,
                summary = message,
                notified = false,
            )
            val commit = ScheduledTaskRepository.recordRunFinished(
                run = failedRun,
                advanceSchedule = advanceSchedule,
                nowMs = finishedAt,
                context = context,
            )
            if (commit != null) {
                ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                    context = context,
                    task = commit.task,
                    reason = commit.task.autoDisabledReason ?: message,
                    autoDisabled = commit.autoDisabledNow,
                )
            }
            return
        }

        val modelLabel = listOf(baseModelConfig.providerName, baseModelConfig.modelDisplayName.ifBlank { baseModelConfig.model })
            .filter { it.isNotBlank() }
            .joinToString(" / ")

        ScheduledTaskRepository.recordRunStarted(
            run = ScheduledTaskRun(
                id = runId,
                taskId = task.id,
                triggerSource = triggerSource,
                scheduledForAt = scheduledForAt,
                startedAt = startedAt,
                status = ScheduledTaskRunStatus.RUNNING,
                archiveConversationKey = archiveKey,
                modelLabel = modelLabel,
            ),
            context = context,
        )

        val runContext = ScheduledTaskRunContext(
            taskId = task.id,
            taskName = task.name,
            runId = runId,
            triggerSource = triggerSource,
            deliveryMode = task.deliveryMode,
            showRunningNotification = task.showRunningNotification,
            sessionMode = task.sessionMode,
            capabilities = task.capabilities,
            memo = task.memo,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
        )
        val archivePayload = AgentExternalArchivePayload(
            userText = task.prompt,
            conversationKey = archiveKey,
            title = task.name.ifBlank { "定时任务" },
            thinkingEnabled = baseModelConfig.thinkingEnabled,
            reasoningEffort = baseModelConfig.effectiveReasoningEffort,
            adapterPayload = runContext.toAdapterPayload(),
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = task.prompt,
            images = emptyList(),
            config = baseModelConfig,
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.SCHEDULED_TASK_HANDOFF_SOURCE,
                payload = archivePayload.toJson(),
                dismissEntrySurfaceOnForegroundOperation = false,
            ),
            modelSessionId = archiveKey,
        )

        // 硬超时看门狗：超出单次任务墙钟预算时主动取消 Runtime run，防止无限耗电或死循环。
        val watchdog = scope.launch {
            delay(timeoutMs)
            execution?.timedOut?.set(true)
            runCatching {
                AgentRuntimeClient(context, AndroidAgentLogger).cancelRun(runId)
            }
        }
        val runResult = try {
            AgentRuntimeClient(context, AndroidAgentLogger).run(
                request = request,
                onEvent = { _ -> },
                isStopRequested = {
                    execution?.timedOut?.get() == true || execution?.userCancelled?.get() == true
                },
            )
        } finally {
            watchdog.cancel()
        }

        val finishedAt = System.currentTimeMillis()
        val reported = ScheduledTaskRunReportRegistry.consume(runId)
        val timedOut = execution?.timedOut?.get() == true
        val userCancelled = execution?.userCancelled?.get() == true

        val (status, summary, shouldNotifySuccess) = when {
            timedOut -> Triple(
                ScheduledTaskRunStatus.TIMEOUT,
                "任务运行超过 ${task.timeoutSeconds} 秒上限，已自动中止",
                false,
            )
            userCancelled -> Triple(
                ScheduledTaskRunStatus.CANCELLED,
                "已手动停止",
                false,
            )
            !runResult.ok -> Triple(
                ScheduledTaskRunStatus.FAILED,
                runResult.error?.takeIf { it.isNotBlank() } ?: "执行失败",
                false,
            )
            task.deliveryMode == ScheduledTaskDeliveryMode.ON_CHANGE &&
                reported?.outcome == ScheduledTaskReportedOutcome.Outcome.NO_CHANGE -> Triple(
                ScheduledTaskRunStatus.NO_CHANGE,
                reported.summary.ifBlank { "已检查，无明显变化" },
                false,
            )
            task.deliveryMode == ScheduledTaskDeliveryMode.SILENT -> Triple(
                ScheduledTaskRunStatus.NO_CHANGE,
                reported?.summary?.takeIf { it.isNotBlank() } ?: summarizeOutput(runResult.content),
                false,
            )
            else -> Triple(
                ScheduledTaskRunStatus.DELIVERED,
                reported?.summary?.takeIf { it.isNotBlank() } ?: summarizeOutput(runResult.content),
                true,
            )
        }

        val finalRun = ScheduledTaskRun(
            id = runId,
            taskId = task.id,
            triggerSource = triggerSource,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status,
            summary = summary.take(300),
            notified = false,
            archiveConversationKey = archiveKey,
            modelLabel = modelLabel,
        )
        val notified = when {
            shouldNotifySuccess -> ScheduledTaskNotificationManager.notifyResult(
                context = context,
                task = task,
                run = finalRun,
                summaryText = summary,
            )
            status.countsAsFailure -> false // 失败通知在提交熔断判定后统一发送，避免连发两条。
            else -> false
        }

        val committedRun = finalRun.copy(notified = notified)
        val commit = ScheduledTaskRepository.recordRunFinished(
            run = committedRun,
            advanceSchedule = advanceSchedule,
            nowMs = finishedAt,
            context = context,
        )
        if (commit != null && status.countsAsFailure) {
            ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                context = context,
                task = commit.task,
                reason = commit.task.autoDisabledReason ?: summary,
                autoDisabled = commit.autoDisabledNow,
            )
        }
    }

    private suspend fun recordSkippedAndAdvance(
        context: Context,
        task: ScheduledTask,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        reason: String,
        advanceSchedule: Boolean = true,
    ) {
        val nowMs = System.currentTimeMillis()
        val skippedRun = ScheduledTaskRun(
            id = "sched-skip-${UUID.randomUUID()}",
            taskId = task.id,
            triggerSource = triggerSource,
            scheduledForAt = scheduledForAt,
            startedAt = nowMs,
            finishedAt = nowMs,
            status = ScheduledTaskRunStatus.SKIPPED,
            summary = reason,
            notified = false,
        )
        ScheduledTaskRepository.recordRunFinished(
            run = skippedRun,
            advanceSchedule = advanceSchedule,
            nowMs = nowMs,
            context = context,
        )
    }

    private suspend fun resolveTaskModelConfig(task: ScheduledTask): AgentModelClient.ModelConfig? {
        // Preserve Yunke's OAuth/custom-header/assistant configuration path.
        ProviderRepository.ensureBuiltInsMerged()
        val requestedModelId = task.modelId?.takeIf { it.isNotBlank() }
        val provider = requestedModelId?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull {
            it.id == requestedModelId && it.isEnabled && !it.supportsSpeechSynthesis &&
                !it.supportsImageGeneration && !it.supportsVideoGeneration
        }
        val requested = if (provider != null && model != null) {
            RuntimeConfigRepository.configForProviderAndModel(provider.id, model.id, AssistantRepository.active())
        } else null
        val base = requested ?: RuntimeConfigRepository.currentRuntimeConfig() ?: return null
        return applyTaskPolicyToModelConfig(
            base = base,
            task = task,
            globalThinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            globalBrowserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            globalTerminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            globalDeviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            globalDeviceSensitiveReadTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            globalDeviceSensitiveActionTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
        )
    }

    /**
     * 将单任务配置与全局用户开关取交集。
     *
     * `RuntimeConfigRepository.buildRuntimeConfig()` 不填充本地工具开关（使用 ModelConfig 构造默认值），
     * 因此这里必须显式以全局开关为基线再按任务能力裁剪，不能直接读取 `base` 的默认布尔值。
     */
    internal fun applyTaskPolicyToModelConfig(
        base: AgentModelClient.ModelConfig,
        task: ScheduledTask,
        globalThinkingEnabled: Boolean,
        globalBrowserTools: Boolean,
        globalTerminalTools: Boolean,
        globalDeviceDirectTools: Boolean,
        globalDeviceSensitiveReadTools: Boolean,
        globalDeviceSensitiveActionTools: Boolean,
    ): AgentModelClient.ModelConfig {
        val explicitEffort = task.resolvedReasoningEffort
        val capabilities = base.reasoningCapabilities
        val effort = when {
            explicitEffort != null -> when {
                capabilities != null -> capabilities.normalize(explicitEffort)
                explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                else -> base.effectiveReasoningEffort
            }
            !globalThinkingEnabled -> capabilities?.normalize(ReasoningEffort.OFF) ?: ReasoningEffort.OFF
            else -> base.effectiveReasoningEffort
        }
        return base.copy(
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            browserTools = globalBrowserTools && task.capabilities.browserTools,
            terminalTools = globalTerminalTools && task.capabilities.terminalTools,
            deviceDirectTools = globalDeviceDirectTools &&
                (task.capabilities.deviceReadTools || task.capabilities.deviceActionTools),
            deviceSensitiveReadTools = globalDeviceSensitiveReadTools && task.capabilities.deviceReadTools,
            deviceSensitiveActionTools = globalDeviceSensitiveActionTools && task.capabilities.deviceActionTools,
        )
    }

    /** 仅当设备处于锁屏状态，且不满足 Android 14+ 后台受信任虚拟屏条件时，避免后台强闯物理主屏。 */
    private fun shouldDeferLockedGuiTask(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val isLocked = keyguard?.isKeyguardLocked == true
        if (!isLocked) return false
        val virtualDisplayPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED) &&
            (RootAccess.isGranted || EtaApp.serviceInstance != null)
        return !virtualDisplayPossible
    }

    private fun summarizeOutput(raw: String): String {
        val normalized = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[#>*`]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.take(220).ifBlank { "已完成执行" }
    }

    /**
     * 判断当前设备是否允许静默定时任务跳过前台执行服务：
     * 处于前台、已授权 Root，或已加入电池优化白名单（Doze 不在 10 秒后切断后台网络）时安全放行。
     */
    internal fun canRunSilentlyWithoutForegroundService(context: Context): Boolean =
        MainActivity.isInForeground ||
            RootAccess.isGranted ||
            ScheduledTaskHealthChecker.inspect(context).ignoringBatteryOptimizations

    private fun acquireBoundedWakeLock(context: Context, timeoutMs: Long): PowerManager.WakeLock? =
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:ScheduledTaskRun").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()

    private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun publishActiveTasks() {
        _activeTaskIds.value = activeByTaskId.keys.toSet()
    }
}
