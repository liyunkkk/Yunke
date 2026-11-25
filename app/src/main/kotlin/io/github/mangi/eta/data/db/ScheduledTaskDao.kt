package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY enabled DESC, COALESCE(next_run_at, 9223372036854775807) ASC, updated_at DESC")
    fun observeTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    fun observeTask(taskId: String): Flow<ScheduledTaskEntity?>

    @Query("SELECT * FROM scheduled_tasks ORDER BY enabled DESC, COALESCE(next_run_at, 9223372036854775807) ASC, updated_at DESC")
    suspend fun allTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at IS NOT NULL ORDER BY next_run_at ASC LIMIT 1")
    suspend fun nextEnabledTask(): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= :nowMs ORDER BY next_run_at ASC")
    suspend fun dueTasks(nowMs: Long): List<ScheduledTaskEntity>

    @Upsert
    suspend fun upsertTask(entity: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET name = :name, prompt = :prompt, enabled = :enabled, trigger_kind = :triggerKind, schedule_json = :scheduleJson, misfire_policy = :misfirePolicy, misfire_window_minutes = :misfireWindowMinutes, quiet_hours_json = :quietHoursJson, delivery_mode = :deliveryMode, show_running_notification = :showRunningNotification, session_mode = :sessionMode, dedicated_conversation_key = :dedicatedConversationKey, model_id = :modelId, reasoning_effort = :reasoningEffort, capabilities_json = :capabilitiesJson, timeout_seconds = :timeoutSeconds, next_run_at = CASE WHEN :updateSchedule THEN :nextRunAt ELSE next_run_at END, consecutive_failures = CASE WHEN :resetFailures THEN 0 ELSE consecutive_failures END, auto_disabled_reason = CASE WHEN :resetFailures THEN NULL ELSE auto_disabled_reason END, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskConfiguration(
        taskId: String,
        name: String,
        prompt: String,
        enabled: Boolean,
        triggerKind: String,
        scheduleJson: String,
        misfirePolicy: String,
        misfireWindowMinutes: Int,
        quietHoursJson: String,
        deliveryMode: String,
        showRunningNotification: Boolean,
        sessionMode: String,
        dedicatedConversationKey: String?,
        modelId: String?,
        reasoningEffort: String?,
        capabilitiesJson: String,
        timeoutSeconds: Int,
        nextRunAt: Long?,
        updateSchedule: Boolean,
        updatedAt: Long,
        resetFailures: Boolean = false,
    ): Int

    @Query("UPDATE scheduled_tasks SET enabled = CASE WHEN :disableTask THEN 0 ELSE enabled END, next_run_at = :nextRunAt, updated_at = :updatedAt WHERE id = :taskId AND enabled = 1 AND next_run_at = :expectedRunAt AND updated_at = :expectedUpdatedAt")
    suspend fun claimOccurrence(taskId: String, expectedRunAt: Long, expectedUpdatedAt: Long, nextRunAt: Long?, disableTask: Boolean, updatedAt: Long): Int

    @Query("UPDATE scheduled_tasks SET memo = :memo, memo_updated_at = :updatedAt, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateMemo(taskId: String, memo: String, updatedAt: Long): Int

    @Query("UPDATE scheduled_tasks SET last_run_at = :lastRunAt, last_status = 'running', updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskRunStarted(taskId: String, lastRunAt: Long, updatedAt: Long): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, next_run_at = CASE WHEN :updateSchedule THEN :nextRunAt ELSE next_run_at END, last_run_at = :lastRunAt, last_status = :lastStatus, last_summary = :lastSummary, consecutive_failures = :consecutiveFailures, auto_disabled_reason = :autoDisabledReason, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskRunFinished(
        taskId: String,
        enabled: Boolean,
        nextRunAt: Long?,
        updateSchedule: Boolean,
        lastRunAt: Long,
        lastStatus: String,
        lastSummary: String,
        consecutiveFailures: Int,
        autoDisabledReason: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, next_run_at = :nextRunAt, consecutive_failures = :consecutiveFailures, auto_disabled_reason = :autoDisabledReason, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskEnabled(
        taskId: String,
        enabled: Boolean,
        nextRunAt: Long?,
        consecutiveFailures: Int,
        autoDisabledReason: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, next_run_at = :nextRunAt, last_status = :lastStatus, last_summary = :lastSummary, updated_at = :updatedAt WHERE id = :taskId AND updated_at = :expectedUpdatedAt AND next_run_at IS :expectedNextRunAt")
    suspend fun updateTaskRecoveryState(
        taskId: String,
        enabled: Boolean,
        nextRunAt: Long?,
        lastStatus: String?,
        lastSummary: String,
        updatedAt: Long,
        expectedUpdatedAt: Long,
        expectedNextRunAt: Long?,
    ): Int

    @Query("DELETE FROM scheduled_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("SELECT * FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
    fun observeRuns(taskId: String, limit: Int = 50): Flow<List<ScheduledTaskRunEntity>>

    @Query("SELECT * FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
    suspend fun listRuns(taskId: String, limit: Int = 50): List<ScheduledTaskRunEntity>

    @Query("SELECT * FROM scheduled_task_runs WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): ScheduledTaskRunEntity?

    @Query("SELECT * FROM scheduled_task_runs WHERE status = 'running'")
    suspend fun runningRuns(): List<ScheduledTaskRunEntity>

    @Upsert
    suspend fun upsertRun(entity: ScheduledTaskRunEntity)

    @Query(
        "DELETE FROM scheduled_task_runs WHERE task_id = :taskId AND id NOT IN (" +
            "SELECT id FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :keepCount" +
            ")"
    )
    suspend fun trimRuns(taskId: String, keepCount: Int = 100)
}
