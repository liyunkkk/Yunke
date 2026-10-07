package io.github.mangi.eta.agent.runtime

import org.json.JSONObject
import java.security.MessageDigest

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
        listOf("status", "error_code", "workspace_id", "workspace_path",
            "workspace_ownership_verified", "can_replace", "successor_task_id").forEach { key ->
            evidence.put(key, json.opt(key) ?: JSONObject.NULL)
        }
        // Public pages and archive availability are projections, not changed task evidence.
        // A retained revision remains stable even if archive capacity later evicts prose.
        if (json.has("text_revision")) evidence.put("text_revision", json.get("text_revision"))
        else listOf("result", "partial_result").forEach { evidence.put(it, json.opt(it) ?: JSONObject.NULL) }
        evidence.put("execution_stopped", stopped)
        evidence.put("checkpoint", json.optJSONObject("supervision")?.optString("checkpoint").orEmpty())
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(evidence.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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

    @Synchronized fun retainOnly(ids: Set<String>) {
        observations.keys.retainAll(ids)
        reads.keys.retainAll(ids)
    }

    @Synchronized fun readVersion(id: String): Long? = reads[id]
    @Synchronized fun matchesRead(id: String, version: Long): Boolean =
        observations[id]?.version == version && reads[id] == version
}
