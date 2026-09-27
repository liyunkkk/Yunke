package io.github.mangi.eta.agent.delegation

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentWorkspaceOwnershipStore
import org.json.JSONArray
import org.json.JSONObject

/** Only runtime-owned Python is executed; app-private bindings, not project JSON, authorize access. */
internal class SubAgentWorkspace(
    private val script: String,
    private val ownership: Ownership?,
    private val executor: AgentModelClient.ToolExecutor,
) {
    data class Ownership(
        val ownerId: String,
        val store: AgentWorkspaceOwnershipStore,
        val environment: () -> String,
        val legacyIds: (String) -> Set<String> = { emptySet() },
        val initialEnvironment: String = environment(),
    )

    // Unscoped constructor is retained for isolated protocol fixtures, never used by the runtime.
    constructor(script: String, executor: AgentModelClient.ToolExecutor) : this(script, null, executor)

    constructor(
        context: Context, executor: AgentModelClient.ToolExecutor, ownerId: String,
        environment: () -> String, legacyIds: (String) -> Set<String>,
        initialEnvironment: String = environment(),
    ) : this(
        context.assets.open("agent/workspace.py").bufferedReader().use { it.readText() },
        Ownership(ownerId, AgentWorkspaceOwnershipStore(context.filesDir), environment, legacyIds, initialEnvironment), executor,
    )

    private fun ownedIds(project: String, environment: String, migrate: Boolean = true): Set<String> {
        val scope = requireNotNull(ownership)
        try {
            val persisted = scope.store.ids(scope.ownerId, environment, project)
            if (!migrate) return persisted
            val legacy = scope.legacyIds(project).filterTo(linkedSetOf()) { Regex("[a-f0-9]{32}").matches(it) }
            if (legacy.isNotEmpty()) scope.store.rememberAll(scope.ownerId, environment, project, legacy)
            return persisted + legacy
        } catch (_: Exception) {
            throw WorkspaceOwnershipException()
        }
    }

    fun ownsWorkspace(project: String, id: String?): Boolean {
        val scope = ownership ?: return false
        val environment = scope.environment()
        if (environment != scope.initialEnvironment) return false
        val ids = ownedIds(project, environment)
        return if (id == null) ids.isNotEmpty() else id in ids
    }

    fun operation(project: String, action: String, id: String? = null, arguments: JSONObject = JSONObject()): JSONObject {
        require(Regex("/workspace/[^/]+").matches(project) && project.substringAfterLast('/') !in setOf(".", ".."))
        val scope = ownership
        val environment = scope?.environment()
        if (scope != null && environment != scope.initialEnvironment) return error("WORKSPACE_ENVIRONMENT_CHANGED", false)
        val allowed = if (scope != null) {
            try { ownedIds(project, requireNotNull(environment), migrate = action != "prepare") }
            catch (_: WorkspaceOwnershipException) { return ownershipFailure() }
        } else emptySet()
        if (scope != null && action !in setOf("prepare", "list") && (id == null || id !in allowed)) {
            return error("WORKSPACE_NOT_OWNED", false)
        }
        if (scope != null && action == "list" && allowed.isEmpty()) {
            return JSONObject().put("ok", true).put("workspaces", JSONArray())
                .put("total_count", 0).put("truncated", false).put("next_offset", JSONObject.NULL)
                .put("shell_executed", false)
        }
        val args = JSONObject(arguments.toString()).put("project", project).put("action", action)
        if (id != null) args.put("workspace_id", id)
        if (scope != null) {
            args.remove("workspace_ids")
            if (action == "list") args.put("workspace_ids", JSONArray(allowed.sorted()))
        }
        if (args.toString().toByteArray(Charsets.UTF_8).size > 100_000) return error("WORKSPACE_ARGUMENTS_TOO_LARGE", false)
        val command = "command -v python3 >/dev/null 2>&1 && command -v git >/dev/null 2>&1 || " +
            "{ printf '%s' '{\"ok\":false,\"code\":\"WORKSPACE_LINUX_PYTHON_GIT_REQUIRED\"}'; exit 0; }; " +
            "python3 -I -c ${quote(script)} ${quote(args.toString())}"
        val raw = executor.execute(AgentModelClient.ToolCall("workspace-runtime", "terminal", JSONObject()
            .put("action", "open_and_exec").put("environment", "linux").put("cwd", project)
            .put("command", command).put("timeout_ms", 60000).toString()))
        val envelope = runCatching { JSONObject(raw.content) }.getOrElse { return error("WORKSPACE_INVALID_RESPONSE", true) }
        if (!envelope.optBoolean("ok") || envelope.optInt("exit_code", -1) != 0) {
            return error(envelope.optString("code").ifBlank { "WORKSPACE_EXECUTION_FAILED" }, true)
        }
        if (scope != null && envelope.optString("environment") != environment) {
            return error("WORKSPACE_ENVIRONMENT_CHANGED", true)
        }
        if (envelope.optBoolean("stdout_truncated")) return error("WORKSPACE_OUTPUT_TOO_LARGE", true)
        val result = runCatching { JSONObject(envelope.getString("stdout")) }
            .getOrElse { return error("WORKSPACE_INVALID_RESPONSE", true) }
        if (!result.optBoolean("ok") || scope == null) return result
        if (action == "prepare") {
            val preparedId = result.optString("id")
            if (!Regex("[a-f0-9]{32}").matches(preparedId) ||
                result.optString("path") != "$project/.agent/worktrees/$preparedId") {
                return error("WORKSPACE_INVALID_RESPONSE", true)
            }
            try { scope.store.remember(scope.ownerId, requireNotNull(environment), project, preparedId) }
            catch (_: Exception) {
                return error("WORKSPACE_OWNERSHIP_RECORD_FAILED", true).put("workspace_id", preparedId)
            }
        } else if (action == "list") {
            val rows = result.optJSONArray("workspaces") ?: return error("WORKSPACE_INVALID_RESPONSE", true)
            val filtered = JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("id") in allowed) filtered.put(row)
            }
            result.put("workspaces", filtered)
            result.optJSONArray("unavailable_workspace_ids")?.let { unavailable ->
                result.put("unavailable_workspace_ids", JSONArray((0 until unavailable.length())
                    .map { unavailable.optString(it) }.filter { it in allowed }))
            }
        }
        return result
    }

    private fun error(code: String, executed: Boolean) = JSONObject().put("ok", false)
        .put("code", code).put("shell_executed", executed)
    private fun ownershipFailure() = error("WORKSPACE_OWNERSHIP_STORE_UNAVAILABLE", false)
        .put("message", "工作区归属账本无法读取或更新，未执行 shell；不会从磁盘记录自动接管。")

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

internal class WorkspaceOwnershipException : IllegalStateException("Workspace ownership store unavailable")
