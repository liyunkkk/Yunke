package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface RuntimeRunDao {
    @Query("SELECT * FROM runtime_results ORDER BY created_at ASC")
    suspend fun runtimeResults(): List<RuntimeResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntimeResult(result: RuntimeResultEntity)

    @Query("DELETE FROM runtime_results WHERE run_id = :runId")
    suspend fun deleteRuntimeResult(runId: String)

    @Query("DELETE FROM runtime_results")
    suspend fun deleteRuntimeResults()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuntimeResults(results: List<RuntimeResultEntity>)

    @Transaction
    suspend fun replaceRuntimeResults(results: List<RuntimeResultEntity>) {
        val retainedRunIds = results.mapTo(mutableSetOf()) { it.runId }
        val removedRunIds = runtimeResults()
            .map { it.runId }
            .filterNot { it in retainedRunIds }
        deleteRuntimeResults()
        if (results.isNotEmpty()) {
            insertRuntimeResults(results)
        }
        removedRunIds.forEach { runId -> deleteInFlightRun(runId) }
    }

    @Query("SELECT * FROM runtime_archive_runs ORDER BY created_at ASC")
    suspend fun archivedRunHeaders(): List<RuntimeArchiveRunEntity>

    @Query(
        "SELECT id, archive_run_id, sort_index, " +
            "CASE WHEN length(CAST(event_json AS BLOB)) <= 65536 " +
            "THEN event_json ELSE '{\"__eta_checkpoint_skipped\":true}' END AS event_json " +
            "FROM runtime_archive_events WHERE archive_run_id = :archiveRunId " +
            "ORDER BY sort_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun archivedEvents(
        archiveRunId: String,
        limit: Int,
        offset: Int,
    ): List<RuntimeArchiveEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArchivedRun(run: RuntimeArchiveRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedEvents(events: List<RuntimeArchiveEventEntity>)

    @Query("DELETE FROM runtime_archive_events WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedEvents(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedRunByArchiveId(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE run_id = :runId OR handoff_id = :runId")
    suspend fun deleteArchivedRun(runId: String)

    @Transaction
    suspend fun replaceArchivedRun(
        run: RuntimeArchiveRunEntity,
        events: List<RuntimeArchiveEventEntity>,
    ) {
        deleteArchivedEvents(run.archiveRunId)
        upsertArchivedRun(run)
        if (events.isNotEmpty()) {
            insertArchivedEvents(events)
        }
    }

    @Query("SELECT * FROM runtime_inflight_runs ORDER BY created_at ASC")
    suspend fun inFlightRunHeaders(): List<RuntimeInFlightRunEntity>

    @Query(
        "SELECT * FROM runtime_inflight_events " +
            "WHERE run_id = :runId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun inFlightEvents(
        runId: String,
        limit: Int,
        offset: Int,
    ): List<RuntimeInFlightEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInFlightRun(run: RuntimeInFlightRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInFlightEvent(event: RuntimeInFlightEventEntity)

    @Query("UPDATE runtime_inflight_runs SET updated_at = :updatedAt WHERE run_id = :runId")
    suspend fun touchInFlightRun(runId: String, updatedAt: Long)

    @Query("DELETE FROM runtime_inflight_events WHERE run_id = :runId")
    suspend fun deleteInFlightEvents(runId: String)

    @Query("DELETE FROM runtime_inflight_runs WHERE run_id = :runId")
    suspend fun deleteInFlightRun(runId: String)

    @Query("UPDATE runtime_inflight_runs SET recovery_incomplete = 1 WHERE run_id = :runId")
    suspend fun markInFlightRecoveryIncomplete(runId: String)

    @Transaction
    suspend fun acknowledgeRuntimeResult(runId: String) {
        deleteRuntimeResult(runId)
        deleteInFlightRun(runId)
    }

    @Transaction
    suspend fun replaceInFlightRun(run: RuntimeInFlightRunEntity) {
        deleteInFlightEvents(run.runId)
        upsertInFlightRun(run)
    }

    @Transaction
    suspend fun appendInFlightEvent(
        event: RuntimeInFlightEventEntity,
        updatedAt: Long,
        recoveryIncomplete: Boolean = false,
    ) {
        insertInFlightEvent(event)
        touchInFlightRun(event.runId, updatedAt)
        if (recoveryIncomplete) markInFlightRecoveryIncomplete(event.runId)
    }
}
