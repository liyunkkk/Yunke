package io.github.mangi.eta.agent.delegation

import org.json.JSONArray
import org.json.JSONObject

/** Runtime Git receipts prove a candidate exists, not that the user's requirements were met. */
internal object SubAgentDeliveryEvidence {
    private val objectId = Regex("(?:[a-f0-9]{40}|[a-f0-9]{64})")
    const val INVALID = "IMPLEMENTATION_EVIDENCE_INVALID"
    const val EMPTY = "NO_IMPLEMENTATION_CHANGES"

    fun verify(result: JSONObject, workspaceId: String, expectedBase: String): JSONObject {
        fun invalid(): Nothing = throw WorkspaceOperationException(INVALID)
        if (!objectId.matches(expectedBase) || result.opt("ok") != true ||
            result.opt("id") != workspaceId || result.opt("state") != "ready" ||
            result.opt("base") != expectedBase) invalid()
        val commit = result.opt("commit") as? String ?: invalid()
        if (!objectId.matches(commit)) invalid()
        val evidence = result.optJSONObject("artifact_evidence") ?: invalid()
        if (evidence.opt("schema_version") != 1 || evidence.opt("source") != "runtime_git" ||
            evidence.opt("workspace_id") != workspaceId || evidence.opt("base_commit") != expectedBase ||
            evidence.opt("artifact_commit") != commit || evidence.opt("net_diff_verified") != true ||
            evidence.opt("base_is_ancestor") != true || evidence.opt("clean_worktree") != true ||
            evidence.opt("head_matches_commit") != true) invalid()
        val countValue = evidence.opt("changed_file_count")
        if (countValue !is Int && countValue !is Long) invalid()
        val count = (countValue as Number).toLong()
        if (count < 0 || count > Int.MAX_VALUE) invalid()
        if (count == 0L) throw WorkspaceOperationException(EMPTY)
        if (commit == expectedBase) invalid()
        // Return only allowlisted, bounded, validated metadata, never a model-supplied verdict.
        val files = evidence.optJSONArray("changed_files") ?: invalid()
        if (files.length() > 20 || files.length() > count || evidence.opt("changed_files_truncated") !is Boolean) invalid()
        val sample = JSONArray()
        var chars = 0
        for (i in 0 until files.length()) {
            val file = files.opt(i) as? String ?: invalid()
            chars += file.length
            if (file.isBlank() || chars > 1200) invalid()
            sample.put(file)
        }
        return JSONObject().put("schema_version", 1).put("source", "runtime_git")
            .put("workspace_id", workspaceId).put("base_commit", expectedBase).put("artifact_commit", commit)
            .put("net_diff_verified", true).put("base_is_ancestor", true)
            .put("clean_worktree", true).put("head_matches_commit", true)
            .put("changed_file_count", count).put("changed_files", sample)
            .put("changed_files_truncated", evidence.getBoolean("changed_files_truncated"))
    }
}
