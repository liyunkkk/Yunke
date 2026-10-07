package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.delegation.SubAgentResultPage
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
        add("get_task_result", "Running polls omit prose. Read text_fields/text_page; use text_field + text_offset + text_limit for bounded prose pages, independent of after_seq. partial_result/model_report_unverified are opt-in. Read an old task's status, actual model and result; omit task_id to list this conversation's tasks. Read the current result/checkpoint before any successor and confirm the old execution has stopped. Parent network failure is not evidence of child model failure. Never replay uncertain paid media. wait_ms up to 10000, after_seq/event_limit page supervision events.",
            SubAgentResultPage.addProperties(JSONObject()).put("task_id", id).put("offset", number).put("wait_ms", number)
                .put("after_seq", number).put("event_limit", number))
        add("manage_agent_workspace", "Inspect or manage persistently owned workspaces from this conversation, including after restart. list accepts offset/limit and returns next_offset; empty is success; do not discard useful unmerged work without user intent. Configuration changes do not authorize workspace takeover or direct replacement; preserve ownership checks and isolated handoff.",
            JSONObject().put("action", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("list", "inspect", "merge", "discard"))))
                .put("project", JSONObject().put("type", "string").put("minLength", 1))
                .put("workspace_id", id)
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 4096))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)),
            JSONArray().put("action").put("project"))
        add("cancel_task", "Request cancellation of an old background child by task_id without stopping the parent. Cancellation is not proof that execution has stopped and does not authorize revival/replay; read its handoff result and verify stopped execution before any supported successor.",
            JSONObject().put("task_id", id), JSONArray().put("task_id"))
        add("continue_task", "Resume an old child awaiting a safe decision boundary using its original task ID, model, reasoning/configuration snapshot, context and workspace, even if the user has edited child settings. Healthy retained tasks and ordinary follow-up dispatch stay frozen; replace_task_id must not bypass this. Cancelled tasks cannot be revived by continue_task.",
            JSONObject().put("task_id", id), JSONArray().put("task_id"))
        add("supervise_task", "Guide/checkpoint/pause a running old text child; no media replay. Pausing or losing the parent connection does not unfreeze its configuration.",
            JSONObject().put("task_id", id).put("action", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("guide", "checkpoint", "pause"))))
                .put("guidance", JSONObject().put("type", "string").put("maxLength", 2000)),
            JSONArray().put("task_id").put("action"))
    }
}
