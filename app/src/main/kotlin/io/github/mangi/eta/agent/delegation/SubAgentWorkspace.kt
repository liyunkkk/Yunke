package io.github.mangi.eta.agent.delegation

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** Only runtime-owned Python is executed, never child-provided commands. */
internal class SubAgentWorkspace(
    private val script: String,
    private val executor: AgentModelClient.ToolExecutor,
) {
    constructor(context: Context, executor: AgentModelClient.ToolExecutor) : this(
        context.assets.open("agent/workspace.py").bufferedReader().use { it.readText() }, executor,
    )

    fun operation(project: String, action: String, id: String? = null, arguments: JSONObject = JSONObject()): JSONObject {
        require(Regex("/workspace/[^/]+").matches(project) && project.substringAfterLast('/') !in setOf(".", ".."))
        val args = JSONObject(arguments.toString()).put("project", project).put("action", action)
        if (id != null) args.put("workspace_id", id)
        if (args.toString().toByteArray(Charsets.UTF_8).size > 100_000) {
            return JSONObject().put("ok", false).put("code", "WORKSPACE_ARGUMENTS_TOO_LARGE")
        }
        val command = "command -v python3 >/dev/null 2>&1 && command -v git >/dev/null 2>&1 || " +
            "{ printf '%s' '{\"ok\":false,\"code\":\"WORKSPACE_LINUX_PYTHON_GIT_REQUIRED\"}'; exit 0; }; " +
            "python3 -I -c ${quote(script)} ${quote(args.toString())}"
        val raw = executor.execute(AgentModelClient.ToolCall("workspace-runtime", "terminal", JSONObject()
            .put("action", "open_and_exec").put("environment", "linux").put("cwd", project)
            .put("command", command).put("timeout_ms", 60000).toString()))
        val envelope = JSONObject(raw.content)
        if (!envelope.optBoolean("ok") || envelope.optInt("exit_code", -1) != 0) {
            return JSONObject().put("ok", false).put("code", envelope.optString("code").ifBlank { "WORKSPACE_EXECUTION_FAILED" })
        }
        if (envelope.optBoolean("stdout_truncated")) return JSONObject().put("ok", false).put("code", "WORKSPACE_OUTPUT_TOO_LARGE")
        return runCatching { JSONObject(envelope.getString("stdout")) }
            .getOrElse { JSONObject().put("ok", false).put("code", "WORKSPACE_INVALID_RESPONSE") }
    }

    fun requireOperation(project: String, action: String, id: String? = null): JSONObject =
        operation(project, action, id).also {
            if (!it.optBoolean("ok")) throw WorkspaceOperationException(it.optString("code"))
        }

    fun childExecutor(project: String, id: String, writable: Boolean, controller: AgentRunController) =
        AgentModelClient.ToolExecutor { call ->
            controller.throwIfCancelled()
            val result = if (call.name != CHILD_TOOL) {
                JSONObject().put("ok", false).put("code", "SUB_AGENT_WORKSPACE_ONLY")
            } else {
                val args = JSONObject(call.argumentsJson)
                val action = args.getString("action")
                if (action !in READ_ACTIONS && !(writable && action in WRITE_ACTIONS)) {
                    JSONObject().put("ok", false).put("code", "SUB_AGENT_READ_ONLY")
                } else operation(project, action, id, args)
            }
            controller.throwIfCancelled()
            AgentModelClient.ToolResult(result.toString(), sensitive = true)
        }

    companion object {
        const val CHILD_TOOL = "workspace_file"
        private val READ_ACTIONS = setOf("read", "list_files", "diff")
        private val WRITE_ACTIONS = setOf("write", "delete")
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        fun childTools(writable: Boolean): JSONArray {
            val actions = READ_ACTIONS + if (writable) WRITE_ACTIONS else emptySet()
            val properties = JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actions.toList())))
                .put("path", JSONObject().put("type", "string").put("maxLength", 500))
                .put("content", JSONObject().put("type", "string").put("maxLength", 60000))
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 4000))
            val schema = JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", properties).put("required", JSONArray().put("action"))
            return JSONArray().put(AgentToolSchema.function(CHILD_TOOL,
                "Read or edit UTF-8 source files in your assigned isolated worktree. Paths are relative. No shell, Git metadata, symlinks or other projects. list_files/diff/read are paginated with offset/limit. Build/test and integration belong to the main agent.", schema))
        }
    }
}

internal class WorkspaceOperationException(code: String) : IllegalStateException() {
    val code: String = code.takeIf { Regex("[A-Z_]{1,80}").matches(it) } ?: "WORKSPACE_OPERATION_FAILED"
}
