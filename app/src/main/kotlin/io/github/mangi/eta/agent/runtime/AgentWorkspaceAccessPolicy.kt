package io.github.mangi.eta.agent.runtime

import org.json.JSONObject

/** Preflight workspace access before any backend operation can start a shell. */
internal object AgentWorkspaceAccessPolicy {
    private val ACTIONS = setOf("list", "inspect", "merge", "discard")

    data class Request(
        val project: String,
        val action: String,
        val workspaceId: String?,
        val offset: Int = 0,
        val limit: Int = 50,
    )

    data class Decision(
        val request: Request?,
        val code: String?,
        val message: String,
        val shellExecuted: Boolean = false,
    ) {
        val allowed: Boolean get() = code == null
    }

    fun preflight(
        argumentsJson: String,
        requestAllowsTerminal: Boolean,
        runtimeAllowsTerminal: Boolean,
        backendAvailable: Boolean,
        ownsWorkspace: (project: String, workspaceId: String?) -> Boolean,
    ): Decision {
        val request = parseRequest(argumentsJson) ?: return reject(
            code = "WORKSPACE_INVALID_ARGUMENTS",
            message = "工作区参数不合法，未执行 shell",
        )
        if (request.action !in ACTIONS) return reject(
            code = "WORKSPACE_INVALID_ACTION",
            message = "不支持的工作区 action，未执行 shell",
        )
        if (request.action != "list" && request.workspaceId == null) return reject(
            code = "WORKSPACE_INVALID_ARGUMENTS",
            message = "${request.action} 必须提供非空 workspace_id，未执行 shell",
        )
        if (!requestAllowsTerminal || !runtimeAllowsTerminal) return reject(
            code = "WORKSPACE_TERMINAL_UNAVAILABLE",
            message = "工作区所需终端工具未获配置或实时授权，未执行 shell",
        )
        if (!backendAvailable) return reject(
            code = "WORKSPACE_BACKEND_UNAVAILABLE",
            message = "工作区 backend 不可用，未执行 shell",
        )
        // Listing is allowed to return an empty, scoped result. The bound backend filters exact IDs.
        if (request.action != "list" && !ownsWorkspace(request.project, request.workspaceId)) return reject(
            code = "WORKSPACE_NOT_OWNED",
            message = "当前会话没有对应工作区的所有权记录，未执行 shell",
        )
        return Decision(request = request, code = null, message = "")
    }

    fun execute(
        argumentsJson: String,
        requestAllowsTerminal: Boolean,
        runtimeAllowsTerminal: Boolean,
        backendAvailable: Boolean,
        ownsWorkspace: (project: String, workspaceId: String?) -> Boolean,
        backendOperation: (Request) -> JSONObject,
    ): JSONObject {
        val decision = preflight(
            argumentsJson = argumentsJson,
            requestAllowsTerminal = requestAllowsTerminal,
            runtimeAllowsTerminal = runtimeAllowsTerminal,
            backendAvailable = backendAvailable,
            ownsWorkspace = ownsWorkspace,
        )
        if (!decision.allowed) {
            return JSONObject()
                .put("ok", false)
                .put("code", decision.code)
                .put("message", decision.message)
                .put("shell_executed", decision.shellExecuted)
        }
        return backendOperation(requireNotNull(decision.request))
    }

    private fun parseRequest(argumentsJson: String): Request? = runCatching {
        val args = JSONObject(argumentsJson)
        val project = requiredString(args, "project")
        val action = requiredString(args, "action")
        val workspaceId = when {
            !args.has("workspace_id") || args.isNull("workspace_id") -> null
            args.opt("workspace_id") is String -> args.optString("workspace_id").ifBlank { null }
            else -> return null
        }
        if (!Regex("/workspace/[^/]+").matches(project) ||
            project.substringAfterLast('/') in setOf(".", "..") || action.isBlank()) return null
        val offset = pageNumber(args, "offset", 0, 0..4096)
        val limit = pageNumber(args, "limit", 50, 1..50)
        Request(project = project, action = action, workspaceId = workspaceId, offset = offset, limit = limit)
    }.getOrNull()

    private fun pageNumber(args: JSONObject, key: String, default: Int, range: IntRange): Int {
        if (!args.has(key)) return default
        val raw = args.get(key)
        require(raw is Int || raw is Long)
        val value = (raw as Number).toLong()
        require(value in range.first.toLong()..range.last.toLong())
        return value.toInt()
    }

    private fun requiredString(args: JSONObject, key: String): String {
        require(args.has(key) && !args.isNull(key))
        return args.opt(key) as? String ?: error("$key must be a string")
    }

    private fun reject(code: String, message: String) = Decision(
        request = null,
        code = code,
        message = message,
        shellExecuted = false,
    )
}
