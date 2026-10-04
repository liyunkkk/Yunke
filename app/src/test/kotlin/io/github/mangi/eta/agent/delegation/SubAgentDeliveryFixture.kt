package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

/** Protocol fixture only: no production bypass. Python tests exercise actual temporary Git repos. */
internal object SubAgentDeliveryFixture {
    const val ID = "0123456789abcdef0123456789abcdef"
    val BASE = "a".repeat(40)
    val COMMIT = "b".repeat(40)

    fun ready(id: String = ID, project: String = "/workspace/project") = JSONObject()
        .put("ok", true).put("id", id).put("path", "$project/.agent/worktrees/$id")
        .put("state", "ready").put("base", BASE).put("commit", COMMIT).put("reviewed", false)
        .put("artifact_evidence", JSONObject().put("schema_version", 1).put("source", "runtime_git")
            .put("workspace_id", id).put("base_commit", BASE).put("artifact_commit", COMMIT)
            .put("net_diff_verified", true).put("base_is_ancestor", true)
            .put("clean_worktree", true).put("head_matches_commit", true)
            .put("changed_file_count", 1).put("changed_files", JSONArray(listOf("Main.kt")))
            .put("changed_files_truncated", false))

    fun response(call: AgentModelClient.ToolCall, prepareId: String = ID,
        mutate: (String, JSONObject) -> Unit = { _, _ -> }): AgentModelClient.ToolResult {
        val command = JSONObject(call.argumentsJson).getString("command")
        fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(command)?.groupValues?.get(1)
        val action = requireNotNull(field("action"))
        val id = field("workspace_id") ?: prepareId
        val result = ready(id, requireNotNull(field("project")))
        if (action == "prepare") result.put("state", "editing").remove("artifact_evidence")
        mutate(action, result)
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("exit_code", 0)
            .put("stdout", result.toString()).toString())
    }
}
