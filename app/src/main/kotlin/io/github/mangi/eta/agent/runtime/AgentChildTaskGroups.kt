package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local ownership. Finished generations become bounded detached result records. */
internal object AgentChildTaskGroups {
    data class StopTarget(val ownerId: String, val generations: Set<String>)
    data class Worker(val id: String, val role: String, val providerId: String)
    private class Group(val ownerId: String, val runId: String, val generation: String, val leaseId: String,
        var coordinator: SubAgentCoordinator?, var releaseTools: (() -> Unit)?, val workers: List<Worker>,
        var binding: AgentRuntimeConnection.Lease?) {
        var attached = true
        var closed = false
        var retiring = false
        var inFlight = 0
        var operationVersion = 0L
        var leaseHeld = true
        var workspaceEnvironment: String? = null
        var snapshots: Map<String, String> = emptyMap()
        var snapshotBytes = 0
    }
    private data class Claim(val predecessorGeneration: String, val successorGeneration: String, val successorId: String)
    private data class Lineage(val predecessorId: String, val predecessorGeneration: String, val successorGeneration: String)
    private const val MAX_ARCHIVED_GROUPS = 24
    private const val MAX_ARCHIVED_BYTES = 2 * 1024 * 1024
    private const val MAX_TASKS_PER_ARCHIVE = 64
    private const val MAX_RESULT_CHARS = 2048
    private const val MAX_CHECKPOINT_CHARS = 1024
    private const val HANDOFF_WAIT_MS = 2000L
    private val groups = linkedMapOf<String, Group>()
    // A predecessor is claimed before dispatch. Unknown means dispatch may have succeeded.
    private val claimed = mutableMapOf<String, Claim>()
    private val replacedBy = mutableMapOf<String, Lineage>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private fun changed() { changes.update { it + 1 } }
    private fun releaseChild(block: () -> Unit) = AgentChildToolOwnership.releaseChild(block)

    /** Acquire binding on the executor worker: acquire waits for service callbacks on main. */
    fun register(context: Context, ownerId: String, runId: String, coordinator: SubAgentCoordinator,
        releaseTools: () -> Unit, workers: List<Worker> = emptyList(), workspaceEnvironment: String? = null): String? {
        val binding = AgentRuntimeConnection.acquire(context, AndroidAgentLogger) ?: return null
        val generation = UUID.randomUUID().toString()
        val leaseId = "child:$generation"
        val stopRequested = AtomicBoolean(false)
        if (!AgentExecutionService.acquire(context, leaseId, onStop = {
            stopRequested.set(true)
            stopGeneration(generation)
        })) {
            binding.close()
            return null
        }
        try {
            synchronized(this) {
                groups[generation] = Group(ownerId, runId, generation, leaseId, coordinator, releaseTools, workers, binding)
                    .also { it.workspaceEnvironment = workspaceEnvironment }
                changed()
            }
        } catch (failure: Throwable) {
            AgentExecutionService.release(leaseId)
            binding.close()
            throw failure
        }
        if (stopRequested.get()) stopGeneration(generation)
        return synchronized(this) { generation.takeIf { groups[it]?.closed == false } }
    }
    fun hasActive(ownerId: String): Boolean = synchronized(this) {
        groups.values.filter { it.ownerId == ownerId && !it.closed }.mapNotNull { it.coordinator }
    }.any { it.hasActiveTasks() }
    fun hasAnyActive(): Boolean = synchronized(this) {
        groups.values.filter { !it.closed }.mapNotNull { it.coordinator }
    }.any { it.hasActiveTasks() }
    fun captureStopTarget(ownerId: String): StopTarget? = synchronized(this) {
        groups.values.filter { it.ownerId == ownerId && !it.closed }.mapTo(linkedSetOf()) { it.generation }
            .takeIf { it.isNotEmpty() }?.let { StopTarget(ownerId, it) }
    }
    fun captureRunStopTargets(runId: String): List<StopTarget> = synchronized(this) {
        groups.values.filter { !it.closed && it.runId == runId }.groupBy { it.ownerId }
            .map { (owner, owned) -> StopTarget(owner, owned.map { it.generation }.toSet()) }
    }
    fun captureActiveStopTargets(): List<StopTarget> {
        val current = synchronized(this) { groups.values.filter { !it.closed }.map { it.ownerId to (it.generation to it.coordinator) } }
        return current.filter { it.second.second?.hasActiveTasks() == true }.groupBy { it.first }
            .map { (owner, entries) -> StopTarget(owner, entries.map { it.second.first }.toSet()) }
    }
    fun stop(target: StopTarget): Boolean {
        val selected = synchronized(this) { groups.values.filter {
            it.ownerId == target.ownerId && it.generation in target.generations && !it.closed
        }.toList() }
        selected.forEach(::close)
        return selected.isNotEmpty()
    }
    fun stopAll() { synchronized(this) { groups.values.filter { !it.closed }.toList() }.forEach(::close) }
    fun stopRun(runId: String): Boolean {
        val selected = synchronized(this) { groups.values.filter { it.runId == runId && !it.closed }.toList() }
        selected.forEach(::close)
        return selected.isNotEmpty()
    }
    private fun stopGeneration(generation: String) { synchronized(this) { groups[generation] }?.let(::close) }

    /** Parent event callback must independently guard against detached delivery (executor sink gate). */
    fun detach(generation: String) {
        synchronized(this) { groups[generation]?.also { it.attached = false; changed() } }
        onTaskChanged(generation)
    }
    fun onTaskChanged(generation: String) {
        val probe = synchronized(this) {
            groups[generation]?.takeIf { !it.closed && !it.retiring }?.let { group ->
                group.coordinator?.let { Triple(group, it, group.operationVersion) }
            }
        } ?: return
        val (group, coordinator, version) = probe
        val active = coordinator.hasActiveTasks()
        val retire = synchronized(this) {
            if (groups[generation] !== group || group.closed || group.retiring || group.coordinator !== coordinator ||
                group.operationVersion != version) return@synchronized false
            changed()
            if (!group.attached && !active && group.inFlight == 0) {
                group.retiring = true
                (this as java.lang.Object).notifyAll()
                true
            } else false
        }
        if (retire) retire(group, coordinator)
    }
    private fun archiveSnapshot(json: JSONObject): String {
        val snapshot = JSONObject()
        listOf("ok", "task_id", "worker", "agent_id", "agent_name", "model", "model_display_name",
            "provider_id", "provider_name", "status", "role", "project", "error_code", "workspace_id",
            "workspace_path", "workspace_ownership_verified", "review_required", "can_continue", "can_replace", "replace_reason",
            "continuation_count", "parallel_limit").forEach { key -> if (json.has(key)) snapshot.put(key, json.get(key)) }
        snapshot.put("result", json.optString("result").take(MAX_RESULT_CHARS))
        snapshot.put("result_truncated", json.optString("result").length > MAX_RESULT_CHARS)
        json.optJSONObject("supervision")?.optString("checkpoint")?.take(MAX_CHECKPOINT_CHARS)?.let {
            snapshot.put("supervision", JSONObject().put("checkpoint", it))
        }
        snapshot.put("archived", true)
        return snapshot.toString()
    }
    private fun retire(group: Group, coordinator: SubAgentCoordinator) {
        val snapshots = linkedMapOf<String, String>()
        runCatching {
            coordinator.taskIds().take(MAX_TASKS_PER_ARCHIVE).forEach { id ->
                val response = coordinator.execute(AgentModelClient.ToolCall("archive-$id", "get_task_result",
                    JSONObject().put("task_id", id).toString()))
                snapshots[id] = archiveSnapshot(JSONObject(response.content))
            }
        }
        val binding: AgentRuntimeConnection.Lease?
        val release: (() -> Unit)?
        val held: Boolean
        synchronized(this) {
            if (groups[group.generation] !== group || group.closed || group.coordinator !== coordinator) return
            group.snapshots = snapshots
            group.snapshotBytes = snapshots.values.sumOf { it.length * 2 }
            group.coordinator = null
            binding = group.binding.also { group.binding = null }
            release = group.releaseTools.also { group.releaseTools = null }
            held = group.leaseHeld.also { group.leaseHeld = false }
            changed()
            (this as java.lang.Object).notifyAll()
        }
        try { coordinator.close() } finally {
            if (held) AgentExecutionService.release(group.leaseId)
            binding?.close()
            release?.let { runCatching { releaseChild(it) } }
            prune()
        }
    }
    private fun forgetGeneration(generation: String) {
        // A successful or uncertain successor must not become replayable when its group closes.
        claimed.entries.removeAll { (_, claim) -> claim.predecessorGeneration == generation }
        replacedBy.entries.removeAll { (_, lineage) -> lineage.predecessorGeneration == generation || lineage.successorGeneration == generation }
    }
    private fun prune() {
        synchronized(this) {
            val archived = groups.values.filter { !it.attached && it.coordinator == null }
            var count = archived.size
            var bytes = archived.sumOf { it.snapshotBytes }
            archived.forEach { old ->
                if (count > MAX_ARCHIVED_GROUPS || bytes > MAX_ARCHIVED_BYTES) {
                    groups.remove(old.generation)
                    old.closed = true
                    count--
                    bytes -= old.snapshotBytes
                    forgetGeneration(old.generation)
                    changed()
                    (this as java.lang.Object).notifyAll()
                }
            }
        }
    }
    private fun close(group: Group) {
        val coordinator: SubAgentCoordinator?
        val binding: AgentRuntimeConnection.Lease?
        val release: (() -> Unit)?
        val held: Boolean
        synchronized(this) {
            if (group.closed) return
            group.closed = true
            groups.remove(group.generation)
            coordinator = group.coordinator.also { group.coordinator = null }
            binding = group.binding.also { group.binding = null }
            release = group.releaseTools.also { group.releaseTools = null }
            held = group.leaseHeld.also { group.leaseHeld = false }
            forgetGeneration(group.generation)
            changed()
            (this as java.lang.Object).notifyAll()
        }
        try { coordinator?.close() } finally {
            if (held) AgentExecutionService.release(group.leaseId)
            binding?.close()
            release?.let { runCatching { releaseChild(it) } }
        }
    }
    private fun begin(group: Group): SubAgentCoordinator? = synchronized(this) {
        if (groups[group.generation] !== group || group.closed || group.retiring) null
        else group.coordinator?.also { group.inFlight++; group.operationVersion++ }
    }
    private fun end(group: Group) {
        synchronized(this) { group.inFlight--; group.operationVersion++; (this as java.lang.Object).notifyAll() }
        onTaskChanged(group.generation)
    }
    private fun ownerGroups(owner: String) = synchronized(this) {
        groups.values.filter { it.ownerId == owner && !it.closed }.toList()
    }
    private fun owns(group: Group, id: String): Boolean {
        val coordinator = synchronized(this) { if (id in group.snapshots) return true else group.coordinator }
        return coordinator?.ownsTask(id) == true
    }
    // A retiring group still owns its task. Wait for the snapshot handoff, never report it as absent.
    private fun result(group: Group, id: String, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val deadline = System.nanoTime() + HANDOFF_WAIT_MS * 1_000_000
        while (true) {
            val snapshot = synchronized(this) { group.snapshots[id] }
            if (snapshot != null) return AgentModelClient.ToolResult(snapshot, sensitive = true)
            val coordinator = begin(group)
            if (coordinator != null) return try { coordinator.execute(call) } finally { end(group) }
            val waiting = synchronized(this) {
                if (group.snapshots[id] != null) false
                else if (groups[group.generation] !== group || group.closed || !group.retiring || group.coordinator == null) false
                else {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining > 0) (this as java.lang.Object).wait(remaining.coerceAtLeast(1))
                    true
                }
            }
            if (!waiting) {
                val archived = synchronized(this) { group.snapshots[id] }
                return if (archived != null) AgentModelClient.ToolResult(archived, sensitive = true)
                    else error(if (synchronized(this) { group.retiring && !group.closed }) "TASK_RESULT_PENDING" else "TASK_NOT_FOUND")
            }
            if (System.nanoTime() >= deadline) return error("TASK_RESULT_PENDING")
        }
    }
    /** Workspace authorization uses only this owner's task records, never an old coordinator's workspace backend. */
    fun ownedWorkspaceIds(ownerId: String, project: String, environment: String? = null): Set<String> {
        if (project.isBlank()) return emptySet()
        val ids = linkedSetOf<String>()
        for (group in ownerGroups(ownerId)) {
            if (environment != null && group.workspaceEnvironment != environment) continue
            val archived = synchronized(this) { group.snapshots.values.toList() }
            archived.forEach { workspaceIdFor(it, project)?.let(ids::add) }
            val coordinator = begin(group)
            if (coordinator == null) {
                val handedOff = synchronized(this) {
                    if (groups[group.generation] === group && group.retiring &&
                        !group.closed && group.coordinator != null
                    ) (this as java.lang.Object).wait(HANDOFF_WAIT_MS)
                    group.snapshots.values.toList()
                }
                handedOff.forEach { workspaceIdFor(it, project)?.let(ids::add) }
                continue
            }
            try {
                for (id in coordinator.taskIds()) {
                    val dto = coordinator.execute(AgentModelClient.ToolCall(
                        "workspace-check-$id", "get_task_result",
                        JSONObject().put("task_id", id).toString()
                    )).content
                    workspaceIdFor(dto, project)?.let(ids::add)
                }
            } finally { end(group) }
        }
        return ids
    }
    fun ownsWorkspace(ownerId: String, project: String, workspaceId: String?): Boolean {
        val ids = ownedWorkspaceIds(ownerId, project)
        return if (workspaceId == null) ids.isNotEmpty() else workspaceId in ids
    }
    private val workspaceIdPattern = Regex("[0-9a-f]{32}")
    private fun workspaceIdFor(dto: String, project: String): String? {
        val json = runCatching { JSONObject(dto) }.getOrNull() ?: return null
        if (!json.optBoolean("ok", true) || json.optString("project") != project) return null
        // Missing/legacy fields and caller-supplied IDs are not ownership evidence.
        if (json.opt("workspace_ownership_verified") != true) return null
        return json.optString("workspace_id").takeIf(workspaceIdPattern::matches)
    }
    fun execute(ownerId: String, currentGeneration: String?, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return error("INVALID_TASK_ARGUMENTS")
        val id = args.optString("task_id")
        if (call.name == "get_task_result" && id.isBlank()) return list(ownerId, args)
        val candidates = ownerGroups(ownerId)
        val current = candidates.firstOrNull { it.generation == currentGeneration && it.attached }
        if (call.name == "delegate_task" && args.optString("replace_task_id").isNotBlank())
            return replace(candidates, current, call, args)
        val group = if (id.isNotBlank()) candidates.firstOrNull { owns(it, id) } else current
        if (group == null) return error(if (id.isNotBlank()) "TASK_NOT_FOUND" else "RUN_CLOSED")
        if (synchronized(this) { group.coordinator == null && !group.retiring } && call.name != "get_task_result") return error("TASK_FINISHED")
        val response = result(group, id, call)
        val json = runCatching { JSONObject(response.content) }.getOrNull() ?: return response
        val taskId = id.ifBlank { json.optString("task_id") }
        synchronized(this) { replacedBy[taskId]?.predecessorId }?.let { json.put("replaces_task_id", it) }
        return AgentModelClient.ToolResult(json.toString(), sensitive = true)
    }
    private fun replace(candidates: List<Group>, current: Group?, call: AgentModelClient.ToolCall,
        args: JSONObject): AgentModelClient.ToolResult {
        if (current == null) return error("RUN_CLOSED")
        val predecessorId = args.optString("replace_task_id")
        val old = candidates.firstOrNull { owns(it, predecessorId) } ?: return error("TASK_NOT_FOUND")
        // Same-group and cross-group replacements take exactly the same claim path.
        val snapshot = runCatching { JSONObject(result(old, predecessorId, AgentModelClient.ToolCall(
            "replacement-check", "get_task_result", JSONObject().put("task_id", predecessorId).toString())).content) }
            .getOrNull() ?: return error("REPLACEMENT_EVIDENCE_UNAVAILABLE")
        if (snapshot.optString("status") != "failed" || !snapshot.optBoolean("can_replace")) return error("REPLACEMENT_NOT_ALLOWED")
        if (old.coordinator?.hasActiveTasks() == true) return error("REPLACE_PENDING_STOP")
        val role = snapshot.optString("role")
        if (role in setOf("image_generation", "video_generation")) return error("MEDIA_DELIVERY_UNCERTAIN")
        if (role == "implementation" || snapshot.optString("workspace_path").isNotBlank() ||
            snapshot.optString("workspace_id").isNotBlank()) return error("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW")
        if (args.has("role") && args.optString("role") != role) return error("REPLACEMENT_ROLE_MISMATCH")
        val checkpoint = snapshot.optJSONObject("supervision")?.optString("checkpoint").orEmpty()
        if (checkpoint.isBlank()) return error("REPLACEMENT_CHECKPOINT_REQUIRED")
        val provider = snapshot.optString("provider_id")
        val alternative = current.workers.withIndex().filter { (_, worker) ->
            worker.providerId.isNotBlank() && worker.providerId != provider &&
                (if (role == "research") worker.role !in setOf("image_generation", "video_generation")
                 else worker.role == if (role == "summary") "review" else role)
        }
        val chosen = when {
            args.has("agent_id") -> alternative.firstOrNull { it.value.id == args.optString("agent_id") }
            args.has("worker") -> alternative.firstOrNull { it.index == args.optInt("worker") - 1 }
            else -> alternative.firstOrNull()
        } ?: return error("REPLACEMENT_PROVIDER_UNAVAILABLE")
        if (args.has("agent_id") && args.has("worker") && args.optInt("worker") != chosen.index + 1)
            return error("WORKER_ID_MISMATCH")
        val originalContext = args.optString("context")
        val evidence = "\n\nPrevious failed task $predecessorId (provider $provider, model ${snapshot.optString("model")}) " +
            "reported this unverified checkpoint: $checkpoint. Continue only unfinished work; " +
            "verify the checkpoint independently. Never replay uncertain external side effects."
        if (originalContext.length + evidence.length > 20000) return error("INVALID_TASK_ARGUMENTS")
        val next = JSONObject(args.toString()).apply {
            // Keep the predecessor for a same-coordinator replacement: its successorId gate is authoritative.
            if (old !== current) remove("replace_task_id")
            put("role", role).put("agent_id", chosen.value.id).put("context", originalContext + evidence)
        }
        val coordinator = begin(current) ?: return error("RUN_CLOSED")
        val pending = Claim(old.generation, current.generation, "pending")
        val reserved = synchronized(this) {
            if (groups[old.generation] !== old || old.closed || groups[current.generation] !== current || current.closed ||
                claimed.containsKey(predecessorId)) false
            else { claimed[predecessorId] = pending; true }
        }
        if (!reserved) { end(current); return error("REPLACEMENT_ALREADY_CLAIMED") }
        var dispatchStarted = false
        var definitive = false
        try {
            if (old.coordinator?.hasActiveTasks() == true) return error("REPLACE_PENDING_STOP")
            // Execute may enqueue before throwing or returning an unreadable response.
            dispatchStarted = true
            val response = coordinator.execute(AgentModelClient.ToolCall(call.id, call.name, next.toString()))
            val payload = runCatching { JSONObject(response.content) }.getOrNull() ?: return error("REPLACEMENT_DISPATCH_UNKNOWN")
            if (payload.optBoolean("ok") && payload.optString("task_id").isNotBlank()) {
                synchronized(this) {
                    if (groups[old.generation] !== old || old.closed || groups[current.generation] !== current ||
                        current.closed || claimed[predecessorId] != pending) return@synchronized
                    val successor = payload.getString("task_id")
                    claimed[predecessorId] = pending.copy(successorId = successor)
                    replacedBy[successor] = Lineage(predecessorId, old.generation, current.generation)
                    definitive = true
                }
                if (!definitive) return error("REPLACEMENT_DISPATCH_UNKNOWN")
                payload.put("replaces_task_id", predecessorId).put("replacement_checkpoint_unverified", true)
            } else if (!payload.optBoolean("ok")) definitive = true
            else return error("REPLACEMENT_DISPATCH_UNKNOWN")
            return AgentModelClient.ToolResult(payload.toString(), sensitive = true)
        } catch (_: Exception) {
            return error(if (dispatchStarted) "REPLACEMENT_DISPATCH_UNKNOWN" else "REPLACE_PENDING_STOP")
        } finally {
            synchronized(this) {
                if (claimed[predecessorId] == pending) {
                    if (!dispatchStarted || definitive) claimed.remove(predecessorId)
                    else claimed[predecessorId] = pending.copy(successorId = "unknown")
                }
            }
            end(current)
        }
    }
    fun requestCompact(ownerId: String, id: String, keep: Int?, model: AgentModelClient.ModelConfig?): Boolean {
        val group = ownerGroups(ownerId).firstOrNull { owns(it, id) } ?: return false
        val coordinator = begin(group) ?: return false
        return try { coordinator.requestCompact(id, keep, model) } finally { end(group) }
    }
    private fun list(ownerId: String, args: JSONObject): AgentModelClient.ToolResult {
        val offset = args.optInt("offset", 0).coerceIn(0, 10_000)
        val groups = ownerGroups(ownerId)
        val ids = groups.flatMap { group -> synchronized(this) { group.snapshots.keys.toList() } +
            (group.coordinator?.taskIds() ?: emptyList()) }.take(10_000)
        val page = JSONArray()
        for (id in ids.drop(offset).take(20)) {
            val group = groups.firstOrNull { owns(it, id) } ?: continue
            val json = runCatching { JSONObject(result(group, id, AgentModelClient.ToolCall(
                "list-$id", "get_task_result", JSONObject().put("task_id", id).toString())).content) }.getOrNull() ?: continue
            val item = JSONObject().put("task_id", id).put("status", json.optString("status"))
                .put("role", json.optString("role")).put("agent_id", json.optString("agent_id"))
            synchronized(this) { replacedBy[id]?.predecessorId }?.let { item.put("replaces_task_id", it) }
            page.put(item)
        }
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("tasks", page)
            .put("total", ids.size).put("next_offset", if (offset + page.length() < ids.size) offset + page.length() else JSONObject.NULL)
            .toString(), sensitive = true)
    }
    private fun error(code: String) = AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", code).toString(), sensitive = true)
}
