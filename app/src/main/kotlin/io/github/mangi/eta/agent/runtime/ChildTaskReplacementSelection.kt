package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Configuration selection only. The group still owns stop/read evidence and the atomic lineage claim. */
internal object ChildTaskReplacementSelection {
    data class Decision(val index: Int? = null, val error: String? = null)

    fun choose(ownerId: String, generation: String, oldWorkers: List<AgentChildTaskGroups.Worker>,
        currentWorkers: List<AgentChildTaskGroups.Worker>, snapshot: JSONObject, args: JSONObject,
        readVersion: Long?, successorClaimed: Boolean): Decision {
        fun denied(code: String) = Decision(error = code)
        val originalId = snapshot.optString("agent_id")
        val role = snapshot.optString("role")
        val chosen = when {
            args.has("agent_id") -> currentWorkers.withIndex().firstOrNull { it.value.id == args.optString("agent_id") }
            args.has("worker") -> currentWorkers.withIndex().firstOrNull { it.index == args.optInt("worker") - 1 }
            // Omitted selector means the same stable worker, never the first available alternative.
            else -> currentWorkers.withIndex().firstOrNull { it.value.id == originalId }
        } ?: return denied("AGENT_NOT_CONFIGURED")
        if (args.has("agent_id") && args.has("worker") && args.optInt("worker") != chosen.index + 1)
            return denied("WORKER_ID_MISMATCH")
        val compatible = if (role == "research") chosen.value.role !in setOf("image_generation", "video_generation")
            else chosen.value.role == if (role == "summary") "review" else role
        if (!compatible) return denied("REPLACEMENT_ROLE_MISMATCH")
        if (chosen.value.id != originalId) {
            // Preserve the existing explicit cross-worker/provider escape hatch. C's same-worker policy does not apply.
            if (chosen.value.providerId.isBlank() || chosen.value.providerId == snapshot.optString("provider_id"))
                return denied("REPLACEMENT_PROVIDER_UNAVAILABLE")
            return Decision(index = chosen.index)
        }
        val original = oldWorkers.firstOrNull { it.id == originalId }?.configuration
            ?: return denied("FROZEN_CONFIGURATION_MISSING")
        val frozenConfig = original.configuration ?: return denied("FROZEN_CONFIGURATION_MISSING")
        val revision = original.configurationRevision ?: return denied("FROZEN_CONFIGURATION_MISSING")
        val latest = chosen.value.configuration ?: return denied("NEW_CONFIGURATION_UNAVAILABLE")
        if (original.worker.ownerId != ownerId || latest.worker.ownerId != ownerId) return denied("OWNER_MISMATCH")
        val task = ChildTaskConfigPolicy.TaskKey(original.worker, generation, snapshot.getString("task_id"))
        val evidence = ChildTaskConfigPolicy.Evidence(task, role, snapshot.optString("status"),
            errorCode = snapshot.optString("error_code"), observationVersion = snapshot.optLong("handoff_version", -1),
            handoffReadVersion = readVersion, executionStopped = snapshot.optBoolean("execution_stopped"),
            canReplace = snapshot.optBoolean("can_replace"), successorClaimed = successorClaimed,
            mediaResultUncertain = role in setOf("image_generation", "video_generation"),
            workspaceId = (snapshot.opt("workspace_id") as? String)?.takeIf { it.isNotBlank() },
            workspacePath = (snapshot.opt("workspace_path") as? String).orEmpty())
        val frozen = ChildTaskConfigPolicy.Snapshot(original.worker, generation, revision, frozenConfig)
        val decision = ChildTaskConfigPolicy.successor(task, latest.worker, role, frozen, evidence) { latest }
        return if (decision.available) Decision(index = chosen.index) else denied(decision.code.name)
    }
}
