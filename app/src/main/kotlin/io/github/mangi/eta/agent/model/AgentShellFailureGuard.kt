package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Run-local, evidence-only protection against a missing executable repair loop.
 * This is a pure transition: it neither executes commands nor changes environments.
 * The caller must finish and append the entire tool batch before acting on stopMessage.
 */
internal object AgentShellFailureGuard {
    const val MAX_FAILURES = 3
    const val FAILURE_CODE = "SHELL_COMMAND_NOT_FOUND"
    const val STOP_CODE = "SHELL_COMMAND_NOT_FOUND_REPAIR_EXHAUSTED"

    data class Key(val tool: String, val environment: String, val executable: String)
    data class State(val failures: Map<Key, Int> = emptyMap())
    data class Decision(
        val state: State,
        val result: AgentModelClient.ToolResult,
        val stopMessage: String? = null,
    )

    private val environments = setOf("android", "debian", "alpine")
    private val executionActions = setOf("exec", "open_and_exec", "daemon_start")
    // Anchor whole lines; do not classify arbitrary warnings, stdout or exit 127 alone.
    private val missingCommand = Regex(
        """^\s*(?:(?:/[^\s:]+/)?(?:sh|bash|dash|ash|ksh|mksh|zsh):\s*(?:(?:line\s+)?\d+:\s*)?)?["']?([A-Za-z0-9_./+@-]+)["']?:\s*(?:command not found|not found)\s*$""",
    )
    private val simpleInvocation = Regex("""^\s*([A-Za-z0-9_./+@-]+)(?:\s+[^\r\n;&|<>`$(){}]*)?\s*$""")

    fun observe(
        state: State,
        call: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): Decision {
        if (call.name != "run_command" && call.name != "terminal") return Decision(state, result)
        val args = parseObject(call.argumentsJson) ?: return Decision(state, result)
        if (call.name == "terminal" && args.optString("action") !in executionActions) return Decision(state, result)
        val command = args.optString("command").trim()
        if (command.isBlank()) return Decision(state, result)
        val body = parseObject(result.content) ?: return Decision(state, result)
        // The requested "linux" alias is not proof of the environment actually used.
        val environment = body.opt("environment") as? String ?: return Decision(state, result)
        if (environment !in environments) return Decision(state, result)
        val stderr = body.opt("stderr") as? String ?: return Decision(state, result)
        val missing = stderr.lineSequence().mapNotNull { line ->
            missingCommand.matchEntire(line)?.groupValues?.get(1)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }.toSet()
        if (missing.isEmpty()) {
            // Only a successful, simple invocation is positive evidence for this executable.
            // `echo git`, `git || true`, a pipeline, or a different environment cannot reset it.
            val exitCode = body.opt("exit_code") as? Number
            val executable = simpleInvocation.matchEntire(command)
                ?.groupValues?.get(1)?.substringAfterLast('/')
            if (exitCode?.toInt() == 0 && body.opt("ok") != false && executable != null) {
                val key = Key(call.name, environment, executable)
                if (key in state.failures) return Decision(State(state.failures - key), result)
            }
            return Decision(state, result)
        }

        val counts = state.failures.toMutableMap()
        val diagnostics = JSONArray()
        var stopMessage: String? = null
        for (executable in missing) {
            val key = Key(call.name, environment, executable)
            val count = ((counts[key] ?: 0) + 1).coerceAtMost(MAX_FAILURES)
            counts[key] = count
            diagnostics.put(JSONObject()
                .put("tool", call.name)
                .put("environment", environment)
                .put("executable", executable)
                .put("attempt", count)
                .put("max_attempts", MAX_FAILURES))
            if (count >= MAX_FAILURES && stopMessage == null) {
                stopMessage = "$STOP_CODE: Shell 修复已停止：${call.name} 在 $environment 中确认缺少 $executable（$count 次）。" +
                    "已保留本批工具的真实结果；不会自动切换环境或重放命令，请检查该环境中的可执行程序。"
            }
        }
        val originalOk = body.opt("ok") ?: JSONObject.NULL
        val originalCode = body.opt("code") ?: JSONObject.NULL
        val diagnostic = JSONObject()
            .put("code", FAILURE_CODE)
            .put("failures", diagnostics)
            .put("stop_after_batch", stopMessage != null)
            .put("original_ok", originalOk)
            .put("original_code", originalCode)
            .put("message", "stderr 确认命令不存在，即使 exit_code 为 0 也不能视为该命令成功。" +
                "不会自动切换环境或重放命令。")
        val annotated = JSONObject(result.content)
            .put("ok", false)
            .put("code", FAILURE_CODE)
            .put("message", "Shell 在 $environment 中确认缺少 $missing；不会自动切换环境或重放命令。")
            .put("shell_failure_diagnostic", diagnostic)
        return Decision(State(counts.toMap()), result.copy(content = annotated.toString()), stopMessage)
    }

    private fun parseObject(content: String): JSONObject? = try {
        JSONObject(content)
    } catch (_: org.json.JSONException) {
        null
    }
}
