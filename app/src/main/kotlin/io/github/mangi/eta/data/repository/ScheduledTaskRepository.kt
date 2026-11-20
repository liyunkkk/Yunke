package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.scheduler.ScheduledTaskAlarmScheduler
import io.github.mangi.eta.agent.scheduler.ScheduledTaskScheduleCalculator
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ScheduledTaskDao
import io.github.mangi.eta.data.db.ScheduledTaskEntity
import io.github.mangi.eta.data.db.ScheduledTaskRunEntity
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskMisfirePolicy
import io.github.mangi.eta.data.model.ScheduledTaskQuietHours
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSchedule
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerKind
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object ScheduledTaskRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var appContext: Context? = null

    private val reconcileMutex = Mutex()

    enum class ReconcileCause { STARTUP, CLOCK_CHANGE, LIFECYCLE }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun dao(context: Context? = null): ScheduledTaskDao {
        val ctx = context?.applicationContext ?: appContext
            ?: error("ScheduledTaskRepository 未初始化")
        return EtaDatabase.get(ctx).scheduledTaskDao()
    }

    fun observeTasks(context: Context? = null): Flow<List<ScheduledTask>> =
        dao(context).observeTasks().map { list -> list.map { it.toDomain() } }

    fun observeTask(taskId: String, context: Context? = null): Flow<ScheduledTask?> =
        dao(context).observeTask(taskId).map { it?.toDomain() }

    fun observeRuns(taskId: String, limit: Int = 50, context: Context? = null): Flow<List<ScheduledTaskRun>> =
        dao(context).observeRuns(taskId, limit).map { list -> list.map { it.toDomain() } }

    suspend fun allTasks(context: Context? = null): List<ScheduledTask> =
        dao(context).allTasks().map { it.toDomain() }

    suspend fun getTask(taskId: String, context: Context? = null): ScheduledTask? =
        dao(context).getTask(taskId)?.toDomain()

    suspend fun nextEnabledTask(context: Context? = null): ScheduledTask? =
        dao(context).nextEnabledTask()?.toDomain()

    suspend fun dueTasks(nowMs: Long = System.currentTimeMillis(), context: Context? = null): List<ScheduledTask> =
        dao(context).dueTasks(nowMs).map { it.toDomain() }

    suspend fun listRuns(taskId: String, limit: Int = 50, context: Context? = null): List<ScheduledTaskRun> =
        dao(context).listRuns(taskId, limit).map { it.toDomain() }

    suspend fun getRun(runId: String, context: Context? = null): ScheduledTaskRun? =
        dao(context).getRun(runId)?.toDomain()

    /**
     * 新建或更新定时任务，并自动重算下一次触发时间与系统闹钟。
     *
     * 手动启用或修改排期时会清除旧的自动熔断原因和连续失败计数。
     */
    suspend fun saveTask(
        task: ScheduledTask,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): ScheduledTask {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        val taskDao = database.scheduledTaskDao()
        val normalizedId = task.id.ifBlank { UUID.randomUUID().toString() }
        val normalized = database.withTransaction {
            val existing = taskDao.getTask(normalizedId)?.toDomain()
            val dedicatedKey = if (task.sessionMode == ScheduledTaskSessionMode.DEDICATED) {
                task.dedicatedConversationKey?.takeIf { it.isNotBlank() }
                    ?: existing?.dedicatedConversationKey?.takeIf { it.isNotBlank() }
                    ?: "task-$normalizedId"
            } else {
                task.dedicatedConversationKey
            }
            val nextRunAt = if (task.enabled) {
                ScheduledTaskScheduleCalculator.computeNextRunAt(
                    schedule = task.schedule,
                    afterMs = nowMs,
                    quietHours = task.quietHours,
                    defaultZone = defaultZone,
                )
            } else null
            val expiredOneShot = task.enabled && task.schedule.kind == ScheduledTaskTriggerKind.ONCE && nextRunAt == null
            require(!task.enabled || nextRunAt != null || expiredOneShot) {
                "已启用任务无法计算下一次运行时间，请调整排期或免打扰时段后重试"
            }
            val effectiveEnabled = task.enabled && !expiredOneShot
            val normalizedTask = task.copy(
                id = normalizedId,
                name = task.name.trim(),
                prompt = task.prompt.trim(),
                enabled = effectiveEnabled,
                dedicatedConversationKey = dedicatedKey,
                timeoutSeconds = task.timeoutSeconds.coerceIn(
                    ScheduledTask.MIN_TIMEOUT_SECONDS,
                    ScheduledTask.MAX_TIMEOUT_SECONDS,
                ),
                misfireWindowMinutes = task.misfireWindowMinutes.coerceIn(5, 24 * 60),
                // Runtime-owned fields must come from the row read inside this write transaction,
                // not from an editor snapshot captured before an occurrence CAS or run commit.
                memo = existing?.memo ?: task.memo.take(ScheduledTask.MAX_MEMO_CHARS),
                memoUpdatedAt = existing?.memoUpdatedAt ?: task.memoUpdatedAt,
                lastRunAt = existing?.lastRunAt ?: task.lastRunAt,
                lastStatus = existing?.lastStatus ?: task.lastStatus,
                lastSummary = existing?.lastSummary ?: task.lastSummary,
                consecutiveFailures = if (effectiveEnabled) 0 else existing?.consecutiveFailures ?: task.consecutiveFailures,
                autoDisabledReason = if (effectiveEnabled) null else existing?.autoDisabledReason ?: task.autoDisabledReason,
                nextRunAt = if (effectiveEnabled) nextRunAt else null,
                createdAt = existing?.createdAt?.takeIf { it > 0L } ?: task.createdAt.takeIf { it > 0L } ?: nowMs,
                updatedAt = nowMs,
            )
            taskDao.upsertTask(normalizedTask.toEntity())
            normalizedTask
        }
        if (rescheduleAlarm) ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
        return normalized
    }

    suspend fun setEnabled(
        taskId: String,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): ScheduledTask? {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        val updated = database.withTransaction {
            val taskDao = database.scheduledTaskDao()
            val current = taskDao.getTask(taskId)?.toDomain() ?: return@withTransaction null
            val nextRunAt = if (enabled) ScheduledTaskScheduleCalculator.computeNextRunAt(
                schedule = current.schedule,
                afterMs = nowMs,
                quietHours = current.quietHours,
                defaultZone = defaultZone,
            ) else null
            val expiredOneShot = enabled && current.schedule.kind == ScheduledTaskTriggerKind.ONCE && nextRunAt == null
            require(!enabled || nextRunAt != null || expiredOneShot) {
                "已启用任务无法计算下一次运行时间，请调整排期或免打扰时段后重试"
            }
            val effectiveEnabled = enabled && !expiredOneShot
            check(taskDao.updateTaskEnabled(
                taskId = taskId,
                enabled = effectiveEnabled,
                nextRunAt = nextRunAt.takeIf { effectiveEnabled },
                consecutiveFailures = if (effectiveEnabled) 0 else current.consecutiveFailures,
                autoDisabledReason = if (effectiveEnabled) null else current.autoDisabledReason,
                updatedAt = nowMs,
            ) == 1)
            taskDao.getTask(taskId)?.toDomain()
        }
        if (rescheduleAlarm) ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
        return updated
    }

    suspend fun updateMemo(
        taskId: String,
        memo: String,
        nowMs: Long = System.currentTimeMillis(),
        context: Context? = null,
    ): Boolean {
        val trimmed = memo.trim().take(ScheduledTask.MAX_MEMO_CHARS)
        return dao(context).updateMemo(taskId, trimmed, nowMs) == 1
    }

    /** Atomically advances a due occurrence before launching work so repeated alarm broadcasts cannot claim it twice. */
    suspend fun claimOccurrence(
        task: ScheduledTask,
        expectedRunAt: Long,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        context: Context? = null,
    ): Boolean {
        if (!task.enabled || task.nextRunAt != expectedRunAt) return false
        val next = ScheduledTaskScheduleCalculator.computeNextRunAt(
            schedule = task.schedule,
            afterMs = nowMs,
            quietHours = task.quietHours,
            defaultZone = defaultZone,
        )
        val disableOneShot = task.schedule.kind == ScheduledTaskTriggerKind.ONCE && next == null
        val claimed = dao(context).claimOccurrence(task.id, expectedRunAt, task.updatedAt, next, disableOneShot, nowMs) == 1
        if (claimed) resolveContext(context)?.let { ScheduledTaskAlarmScheduler.syncNextAlarm(it) }
        return claimed
    }

    suspend fun deleteTask(
        taskId: String,
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ) {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        database.withTransaction {
            database.scheduledTaskDao().deleteTask(taskId)
        }
        if (rescheduleAlarm) ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
    }

    suspend fun recordRunStarted(
        run: ScheduledTaskRun,
        context: Context? = null,
    ) {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        database.withTransaction {
            val taskDao = database.scheduledTaskDao()
            // Check the parent before inserting the child; delete cascades and wins atomically.
            if (taskDao.getTask(run.taskId) == null) return@withTransaction
            taskDao.upsertRun(run.toEntity())
            check(taskDao.updateTaskRunStarted(run.taskId, run.startedAt, run.startedAt) == 1)
        }
    }

    /**
     * 提交单次执行结果，更新任务状态、推进下一次触发时间，并在连续失败达阈值时自动熔断暂停。
     *
     * 返回更新后的任务快照（含是否触发了自动熔断）。
     */
    suspend fun recordRunFinished(
        run: ScheduledTaskRun,
        advanceSchedule: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
    ): FinishedRunCommit? {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        require(run.status != ScheduledTaskRunStatus.RUNNING) { "recordRunFinished requires a terminal run status" }
        val commit = database.withTransaction {
            val taskDao = database.scheduledTaskDao()
            val currentEntity = taskDao.getTask(run.taskId) ?: return@withTransaction null
            val current = currentEntity.toDomain()
            val existingRun = taskDao.getRun(run.id)
            if (existingRun != null) {
                if (existingRun.taskId != run.taskId) return@withTransaction null
                if (existingRun.status != ScheduledTaskRunStatus.RUNNING.wireValue) {
                    // A duplicate terminal callback must not increment failures or rewrite a newer result.
                    return@withTransaction null
                }
            }

            // Parent verification and child write share this Room transaction with DELETE CASCADE.
            taskDao.upsertRun(run.toEntity())
            taskDao.trimRuns(run.taskId, keepCount = 100)

            val nextFailures = when {
                run.status.isSuccess -> 0
                run.status.countsAsFailure -> current.consecutiveFailures + 1
                else -> current.consecutiveFailures
            }
            val autoDisabled = run.status.countsAsFailure &&
                current.enabled &&
                nextFailures >= ScheduledTask.MAX_CONSECUTIVE_FAILURES
            val autoDisableReason = if (autoDisabled) {
                "连续失败 ${nextFailures} 次，已自动暂停以避免反复耗电与重试"
            } else if (run.status.isSuccess) {
                null
            } else {
                current.autoDisabledReason
            }

            val isOneShotFinished = advanceSchedule &&
                current.schedule.kind == ScheduledTaskTriggerKind.ONCE &&
                run.status != ScheduledTaskRunStatus.RUNNING
            val nextEnabled = when {
                autoDisabled || isOneShotFinished -> false
                else -> current.enabled
            }
            val updateSchedule = advanceSchedule || autoDisabled || isOneShotFinished
            val nextRunAt = when {
                !nextEnabled -> null
                advanceSchedule -> ScheduledTaskScheduleCalculator.computeNextRunAt(
                    schedule = current.schedule,
                    afterMs = maxOf(nowMs, run.scheduledForAt ?: nowMs),
                    quietHours = current.quietHours,
                    defaultZone = defaultZone,
                )
                else -> current.nextRunAt
            }
            val finishedAt = run.finishedAt ?: nowMs
            // Update only runtime-owned columns; never write a stale task snapshot over an occurrence CAS or edits.
            val changed = taskDao.updateTaskRunFinished(
                taskId = run.taskId,
                enabled = nextEnabled,
                nextRunAt = nextRunAt,
                updateSchedule = updateSchedule,
                lastRunAt = finishedAt,
                lastStatus = run.status.wireValue,
                lastSummary = run.summary.take(300),
                consecutiveFailures = nextFailures,
                autoDisabledReason = autoDisableReason,
                updatedAt = nowMs,
            )
            check(changed == 1) { "Scheduled task disappeared during run commit" }
            val updatedTask = taskDao.getTask(run.taskId)?.toDomain()
                ?: error("Scheduled task disappeared during run commit")
            FinishedRunCommit(task = updatedTask, autoDisabledNow = autoDisabled)
        }
        if (commit != null && rescheduleAlarm) ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
        return commit
    }

    /**
     * 启动恢复只结束上个进程遗留的 run；时钟/时区变化只重算排期，不改当前 run 状态。
     * 每个 taskId 先查询 Runner 活跃表，避免冷启动 reconcile 覆盖已经被本进程接管的运行。
     */
    suspend fun reconcileAfterRestartOrClockChange(
        nowMs: Long = System.currentTimeMillis(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
        recomputeAllSchedules: Boolean = false,
        rescheduleAlarm: Boolean = true,
        context: Context? = null,
        cause: ReconcileCause = ReconcileCause.STARTUP,
        isTaskRunning: (String) -> Boolean = { taskId ->
            io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner.isTaskRunning(taskId)
        },
    ) = reconcileMutex.withLock {
        val appContext = resolveContext(context) ?: error("ScheduledTaskRepository 未初始化")
        val database = EtaDatabase.get(appContext)
        database.withTransaction {
            val d = database.scheduledTaskDao()
            val tasks = d.allTasks()

            if (cause == ReconcileCause.STARTUP) {
                d.runningRuns().forEach { entity ->
                    if (isTaskRunning(entity.taskId) || d.getTask(entity.taskId) == null) return@forEach
                    d.upsertRun(
                        entity.copy(
                            status = ScheduledTaskRunStatus.FAILED.wireValue,
                            finishedAt = nowMs,
                            summary = entity.summary.ifBlank { "执行期间应用进程退出，任务已中断" },
                        )
                    )
                }
            }

            tasks.forEach { entity ->
                val task = entity.toDomain()
                val isActiveInThisProcess = isTaskRunning(task.id)
                val wasInterrupted = cause == ReconcileCause.STARTUP && !isActiveInThisProcess &&
                    task.lastStatus == ScheduledTaskRunStatus.RUNNING
                var updated = if (wasInterrupted) {
                    task.copy(
                        lastStatus = ScheduledTaskRunStatus.FAILED,
                        lastSummary = task.lastSummary.ifBlank { "执行期间应用进程退出，任务已中断" },
                        updatedAt = nowMs,
                    )
                } else task

                if (updated.enabled && !isActiveInThisProcess) {
                    val shouldRecompute = recomputeAllSchedules || updated.nextRunAt == null
                    if (shouldRecompute) {
                        val recomputed = ScheduledTaskScheduleCalculator.computeNextRunAt(
                            schedule = updated.schedule,
                            afterMs = nowMs,
                            quietHours = updated.quietHours,
                            defaultZone = defaultZone,
                        )
                        updated = if (recomputed == null && updated.schedule.kind == ScheduledTaskTriggerKind.ONCE) {
                            updated.copy(enabled = false, nextRunAt = null, updatedAt = nowMs)
                        } else {
                            updated.copy(nextRunAt = recomputed, updatedAt = nowMs)
                        }
                    }
                }
                if (updated != task) {
                    check(d.updateTaskRecoveryState(
                        taskId = task.id,
                        enabled = updated.enabled,
                        nextRunAt = updated.nextRunAt,
                        lastStatus = updated.lastStatus?.wireValue,
                        lastSummary = updated.lastSummary,
                        updatedAt = nowMs,
                        expectedUpdatedAt = entity.updatedAt,
                        expectedNextRunAt = entity.nextRunAt,
                    ) == 1) { "Scheduled task changed during reconciliation" }
                }
            }
        }
        if (rescheduleAlarm) ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
    }

    data class FinishedRunCommit(
        val task: ScheduledTask,
        val autoDisabledNow: Boolean,
    )

    private fun resolveContext(context: Context?): Context? =
        context?.applicationContext ?: appContext

    private fun ScheduledTaskEntity.toDomain(): ScheduledTask {
        val kind = ScheduledTaskTriggerKind.fromWireValue(triggerKind)
        val parsedSchedule = runCatching {
            json.decodeFromString<ScheduledTaskSchedule>(scheduleJson)
        }.getOrElse { ScheduledTaskSchedule(kind = kind) }.copy(kind = kind)
        val parsedQuietHours = runCatching {
            json.decodeFromString<ScheduledTaskQuietHours>(quietHoursJson)
        }.getOrElse { ScheduledTaskQuietHours() }
        val parsedCaps = runCatching {
            json.decodeFromString<ScheduledTaskCapabilities>(capabilitiesJson)
        }.getOrElse { ScheduledTaskCapabilities() }
        return ScheduledTask(
            id = id,
            name = name,
            prompt = prompt,
            enabled = enabled,
            schedule = parsedSchedule,
            misfirePolicy = ScheduledTaskMisfirePolicy.fromWireValue(misfirePolicy),
            misfireWindowMinutes = misfireWindowMinutes,
            quietHours = parsedQuietHours,
            deliveryMode = ScheduledTaskDeliveryMode.fromWireValue(deliveryMode),
            showRunningNotification = showRunningNotification,
            sessionMode = ScheduledTaskSessionMode.fromWireValue(sessionMode),
            dedicatedConversationKey = dedicatedConversationKey,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            capabilities = parsedCaps,
            timeoutSeconds = timeoutSeconds,
            memo = memo,
            memoUpdatedAt = memoUpdatedAt,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
            lastStatus = lastStatus?.let(ScheduledTaskRunStatus::fromWireValue),
            lastSummary = lastSummary,
            consecutiveFailures = consecutiveFailures,
            autoDisabledReason = autoDisabledReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ScheduledTask.toEntity(): ScheduledTaskEntity =
        ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            enabled = enabled,
            triggerKind = schedule.kind.wireValue,
            scheduleJson = json.encodeToString(schedule),
            misfirePolicy = misfirePolicy.wireValue,
            misfireWindowMinutes = misfireWindowMinutes,
            quietHoursJson = json.encodeToString(quietHours),
            deliveryMode = deliveryMode.wireValue,
            showRunningNotification = showRunningNotification,
            sessionMode = sessionMode.wireValue,
            dedicatedConversationKey = dedicatedConversationKey,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            capabilitiesJson = json.encodeToString(capabilities),
            timeoutSeconds = timeoutSeconds,
            memo = memo,
            memoUpdatedAt = memoUpdatedAt,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
            lastStatus = lastStatus?.wireValue,
            lastSummary = lastSummary,
            consecutiveFailures = consecutiveFailures,
            autoDisabledReason = autoDisabledReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun ScheduledTaskRunEntity.toDomain(): ScheduledTaskRun =
        ScheduledTaskRun(
            id = id,
            taskId = taskId,
            triggerSource = ScheduledTaskTriggerSource.fromWireValue(triggerSource),
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = ScheduledTaskRunStatus.fromWireValue(status),
            summary = summary,
            notified = notified,
            archiveConversationKey = archiveConversationKey,
            modelLabel = modelLabel,
        )

    private fun ScheduledTaskRun.toEntity(): ScheduledTaskRunEntity =
        ScheduledTaskRunEntity(
            id = id,
            taskId = taskId,
            triggerSource = triggerSource.wireValue,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status.wireValue,
            summary = summary,
            notified = notified,
            archiveConversationKey = archiveConversationKey,
            modelLabel = modelLabel,
        )
}
