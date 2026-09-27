package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/** Keep older tasks accessible even when no new worker is configured. */
internal object ExistingChildTaskTools {
    fun appendTo(tools: JSONArray) {
        val id = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 80)
        val number = JSONObject().put("type", "integer").put("minimum", 0)
        fun add(name: String, description: String, properties: JSONObject, required: JSONArray = JSONArray()) {
            tools.put(AgentToolSchema.function(name, description,
                JSONObject().put("type", "object").put("properties", properties)
                    .put("required", required).put("additionalProperties", false)))
        }
        add("get_task_result", "Read an old task's status, actual model and result; omit task_id to list this conversation's tasks. wait_ms up to 10000, after_seq/event_limit page supervision events.",
            JSONObject().put("task_id", id).put("offset", number).put("wait_ms", number)
                .put("after_seq", number).put("event_limit", number))
        add("manage_agent_workspace", "Inspect or manage persistently owned workspaces from this conversation, including after restart. list accepts offset/limit and returns next_offset; empty is success; do not discard useful unmerged work without user intent.",
            JSONObject().put("action", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("list", "inspect", "merge", "discard"))))
                .put("project", JSONObject().put("type", "string").put("minLength", 1))
                .put("workspace_id", id)
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 4096))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)),
            JSONArray().put("action").put("project"))
        add("cancel_task", "Cancel an old background child by task_id without stopping the parent.",
            JSONObject().put("task_id", id), JSONArray().put("task_id"))
        add("continue_task", "Resume an old child awaiting a safe decision boundary using its original task ID, model, context and workspace.",
            JSONObject().put("task_id", id), JSONArray().put("task_id"))
        add("supervise_task", "Guide/checkpoint/pause a running old text child; no media replay.",
            JSONObject().put("task_id", id).put("action", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("guide", "checkpoint", "pause"))))
                .put("guidance", JSONObject().put("type", "string").put("maxLength", 2000)),
            JSONArray().put("task_id").put("action"))
    }
}
