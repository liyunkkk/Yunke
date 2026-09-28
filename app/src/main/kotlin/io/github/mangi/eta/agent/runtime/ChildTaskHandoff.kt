package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Trusted, process-local observations. Tool arguments never supply stop evidence or read receipts. */
internal class ChildTaskHandoff {
    private data class Observation(val fingerprint: String, val version: Long)
    private val observations = mutableMapOf<String, Observation>()
    private val reads = mutableMapOf<String, Long>()

    @Synchronized fun observe(json: JSONObject): JSONObject {
        val id = json.optString("task_id")
        if (id.isBlank() || !json.optBoolean("ok")) return json
        val stopped = json.optBoolean("execution_exited", false)
        val evidence = JSONObject()
        listOf("status", "error_code", "result", "partial_result", "workspace_id", "workspace_path",
            "workspace_ownership_verified", "can_replace", "successor_task_id").forEach { key ->
            evidence.put(key, json.opt(key) ?: JSONObject.NULL)
        }
        evidence.put("execution_stopped", stopped)
        evidence.put("checkpoint", json.optJSONObject("supervision")?.optString("checkpoint").orEmpty())
        val fingerprint = evidence.toString()
        val old = observations[id]
        val observed = if (old != null && old.fingerprint == fingerprint) old else Observation(fingerprint, (old?.version ?: 0L) + 1)
        observations[id] = observed
        return json.put("execution_stopped", stopped).put("handoff_version", observed.version)
    }

    /** Only the external single-task get_task_result response calls this; list/replacement probes do not. */
    @Synchronized fun recordRead(json: JSONObject) {
        val id = json.optString("task_id")
        val version = json.optLong("handoff_version", -1)
        if (json.optBoolean("ok") && observations[id]?.version == version) reads[id] = version
    }

    @Synchronized fun readVersion(id: String): Long? = reads[id]
    @Synchronized fun matchesRead(id: String, version: Long): Boolean =
        observations[id]?.version == version && reads[id] == version
}
