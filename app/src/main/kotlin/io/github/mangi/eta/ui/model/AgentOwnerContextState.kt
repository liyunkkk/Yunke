package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.delegation.SubAgentContextStats

/**
 * UI-only context telemetry for ONE conversation owner, not one parent run or worker.
 *
 * Keep this instance in owner-scoped application state across parent runs and menu
 * recreation. Feed it the owner's read-only registry snapshots (including queued tasks
 * without usage), and refresh on registry telemetry revisions. Partial delivery merges
 * by task_id; absence never means completion/cancellation. A different owner must use a
 * different instance. No prompt, runtime context, workspace, archive or continuation
 * capability is owned or removed by this projection.
 *
 * All calls belong on the UI dispatcher. [nowMs] and optional source status timestamps
 * must use the same monotonic clock. A pause request is `pausing`; only the confirmed
 * `awaiting_decision` (or `paused`) state starts the one-time 30-second grace period.
 */
internal class AgentOwnerContextState(
    val ownerId: String,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(ownerId.isNotBlank()) }

    /**
     * Optional richer source adapter. [revision] is monotonic per task; a coherent
     * owner-wide revision also works. [statusVersion] changes on EVERY status transition
     * but stays stable on usage-only refreshes. Persist it with retained snapshots.
     * [statusChangedAtMs] uses the same clock as [nowMs]; otherwise leave it null and
     * the first observation of this status version starts its one-time grace period.
     */
    data class TaskSnapshot(
        val stats: SubAgentContextStats,
        val revision: Long,
        val statusVersion: Long,
        val statusChangedAtMs: Long? = null,
    )

    /** Capture this exact token when scheduling, not merely a task ID or worker index. */
    data class HideToken(
        val ownerId: String,
        val taskId: String,
        val statusVersion: Long,
        val deadlineMs: Long,
    )

    data class Projection(
        val children: List<SubAgentContextStats>,
        val selectedTaskId: String?,
    )

    private data class Entry(
        val snapshot: TaskSnapshot,
        val hideToken: HideToken?,
        val hidden: Boolean = false,
    )

    private val entries = linkedMapOf<String, Entry>()
    private var selectedTaskId: String? = null
    private var lastOwnerRevision: Long? = null

    /**
     * Direct adapter for contextStats(ownerId): List<SubAgentContextStats> + revision.
     * Capture the owner when reading, not when a delayed result arrives. The revision
     * must describe that read, not be reassigned on delivery. Each accepted status
     * transition gets a stable local token; polling/usage refreshes do not restart it.
     * Never mix this adapter and [refresh] with externally assigned status versions on
     * the same instance. If the source conflates pause/resume/pause between snapshots,
     * supply explicit TaskSnapshots instead so the unobserved transition is preserved.
     */
    fun refresh(sourceOwnerId: String, revision: Long, contexts: List<SubAgentContextStats>): Boolean {
        if (sourceOwnerId != ownerId) return false
        if (lastOwnerRevision?.let { revision <= it } == true) return false
        lastOwnerRevision = revision
        val unique = contexts.filter { it.taskId.isNotBlank() }.associateBy { it.taskId }
        return refresh(sourceOwnerId, unique.values.map { stats ->
            val previous = entries[stats.taskId]?.snapshot
            val version = when {
                previous == null -> 1L
                previous.stats.status != stats.status -> previous.statusVersion + 1L
                else -> previous.statusVersion
            }
            TaskSnapshot(stats, revision, version)
        })
    }

    /** Reject foreign/stale events. No worker/model deduplication and no token estimation. */
    fun refresh(sourceOwnerId: String, snapshots: List<TaskSnapshot>): Boolean {
        if (sourceOwnerId != ownerId) return false
        val now = nowMs()
        var changed = false
        for (incoming in snapshots) {
            val id = incoming.stats.taskId
            if (id.isBlank()) continue
            val old = entries[id]
            val live = incoming.stats.status in LIVE_STATUS
            if (old != null) {
                if (incoming.revision <= old.snapshot.revision) continue
                // A resumed task must reappear even if its status token was reset or reused.
                // Other backwards or same-token status changes stay incoherent.
                if (!live && incoming.statusVersion < old.snapshot.statusVersion) continue
                if (!live && incoming.statusVersion == old.snapshot.statusVersion &&
                    incoming.stats.status != old.snapshot.stats.status) continue
            }
            val snapshot = incoming.copy(stats = incoming.stats.measuredOnly())
            val sameStatusVersion = old != null && snapshot.statusVersion == old.snapshot.statusVersion
            val token = when {
                live -> null
                sameStatusVersion -> old?.hideToken
                snapshot.stats.status in HIDE_AFTER_DELAY -> {
                    val enteredAt = (snapshot.statusChangedAtMs ?: now).coerceAtMost(now)
                    HideToken(ownerId, id, snapshot.statusVersion, enteredAt + HIDE_AFTER_MS)
                }
                else -> null
            }
            entries[id] = Entry(snapshot, token, hidden = !live && sameStatusVersion && old?.hidden == true)
            changed = true
        }
        return changed
    }

    /** Selection is explicit; a telemetry refresh never chooses the newest/first worker. */
    fun select(sourceOwnerId: String, taskId: String?): Boolean {
        if (sourceOwnerId != ownerId) return false
        if (taskId != null && entries[taskId]?.isVisible(nowMs()) != true) return false
        if (selectedTaskId == taskId) return false
        selectedTaskId = taskId
        return true
    }

    /** Reading at/after a deadline also hides it, even if Android delayed the timer. */
    fun projection(): Projection {
        val now = nowMs()
        val children = entries.values.filter { it.isVisible(now) }.map { it.snapshot.stats }
        // Only real expiry clears selection. Refreshes cannot drop a still-visible task.
        if (selectedTaskId != null && children.none { it.taskId == selectedTaskId }) selectedTaskId = null
        return Projection(children, selectedTaskId)
    }

    /** Retained telemetry is available for inspection; hiding never discards this record. */
    fun latest(taskId: String): SubAgentContextStats? = entries[taskId]?.snapshot?.stats

    /** Schedule each token once. An already due token can be expired immediately. */
    fun pendingHides(): List<HideToken> = entries.values.filterNot { it.hidden }.mapNotNull { it.hideToken }

    fun remainingMs(token: HideToken): Long = (token.deadlineMs - nowMs()).coerceAtLeast(0)

    /**
     * An old timer cannot hide a resumed task, a subsequent pause, or another owner.
     * Returns whether to republish the projection. It never touches the task registry.
     */
    fun expire(token: HideToken): Boolean {
        if (token.ownerId != ownerId) return false
        val current = entries[token.taskId] ?: return false
        if (current.hidden || current.hideToken != token || nowMs() < token.deadlineMs) return false
        entries[token.taskId] = current.copy(hidden = true)
        if (selectedTaskId == token.taskId) selectedTaskId = null
        return true
    }

    private fun Entry.isVisible(now: Long): Boolean =
        !hidden && (hideToken == null || now < hideToken.deadlineMs)

    private fun SubAgentContextStats.measuredOnly(): SubAgentContextStats = copy(
        // Null is significant (e.g. invalidation after compaction): do not reuse a stale
        // bill, sum cumulative input/output, or turn a projected value into occupancy.
        contextTokens = contextTokens?.takeIf { !projected && it > 0 },
        contextWindow = contextWindow?.takeIf { it > 0 },
        beforeCompactionTokens = beforeCompactionTokens?.takeIf { !projected && it > 0 },
        afterCompactionTokens = afterCompactionTokens?.takeIf { !projected && it > 0 },
    )

    companion object {
        const val HIDE_AFTER_MS = 30_000L
        // Preserve the existing terminal grace period; awaiting_decision is confirmed pause.
        val HIDE_AFTER_DELAY = setOf("completed", "cancelled", "timed_out", "failed", "awaiting_decision", "paused")
        val LIVE_STATUS = setOf("queued", "running", "pausing")
    }
}
