package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import org.json.JSONObject

/**
 * 终端命令执行抽象，支持生产环境（RootShellTerminalController）与单元测试驱动。
 */
internal fun interface DshCommandExecutor {
    fun execute(cwd: String, command: String, timeoutMs: Int): String
}

/**
 * 桥接脚本执行结果的结构化封装。
 */
internal data class DshBridgeResult(
    val ok: Boolean,
    val sessionId: String? = null,
    val resumed: Boolean = false,
    val response: String = "",
    val responseTruncated: Boolean = false,
    val stopReason: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val rawJson: JSONObject? = null,
)

/**
 * Kotlin 与 assets/agent/dsh_bridge.py 之间的无状态桥接执行器。
 *
 * 职责：
 * 1. 负责从 assets 读取桥接 Python 脚本；
 * 2. 拼装带有 python3 探测兜底与 DSH 环境变量注入的单行命令；
 * 3. 通过 Linux 容器终端执行并解析 stdout 那一行 JSON；
 * 4. 将任何执行失败或异常收敛映射为稳定错误码。
 */
internal class DshBridge(
    private val script: String,
    private val executor: DshCommandExecutor,
    private val dshRuntimeBin: String = DEFAULT_RUNTIME_BIN,
    private val dshHome: String = DEFAULT_DSH_HOME,
) {

    constructor(
        context: Context,
        terminalController: RootShellTerminalController,
        dshRuntimeBin: String = DEFAULT_RUNTIME_BIN,
        dshHome: String = DEFAULT_DSH_HOME,
    ) : this(
        script = loadScriptFromAssets(context),
        executor = TerminalControllerDshExecutor(context, terminalController),
        dshRuntimeBin = dshRuntimeBin,
        dshHome = dshHome,
    )

    /**
     * 执行编码委派任务（action=run）。
     */
    fun run(
        task: String,
        projectPath: String,
        timeoutSeconds: Int,
        sessionId: String? = null,
        settings: DshSettings,
    ): DshBridgeResult {
        val args = JSONObject()
            .put("action", "run")
            .put("cwd", projectPath)
            .put("home", dshHome)
            .put("key_file", settings.keyFilePath)
            .put("timeout", timeoutSeconds)
            .put("task", task)

        sessionId?.takeIf { it.isNotBlank() }?.let { args.put("session", it) }
        settings.model.takeIf { it.isNotBlank() }?.let { args.put("model_value", it) }
        settings.effort.takeIf { it.isNotBlank() }?.let { args.put("effort", it) }
        settings.endpoint.takeIf { it.isNotBlank() }?.let { args.put("base_url", it) }

        return executeAction(projectPath, args.toString(), timeoutSeconds * 1000)
    }

    /**
     * 检查运行时与鉴权就绪状态（action=check）。
     */
    fun check(cwd: String = "/workspace", keyFile: String = ""): DshBridgeResult {
        val args = JSONObject()
            .put("action", "check")
            .put("cwd", cwd)
            .put("key_file", keyFile)
        return executeAction(cwd, args.toString(), 30_000)
    }

    /**
     * 获取支持的模型与推理配置列表（action=models）。
     */
    fun models(cwd: String = "/workspace", keyFile: String = ""): DshBridgeResult {
        val args = JSONObject()
            .put("action", "models")
            .put("cwd", cwd)
            .put("key_file", keyFile)
        return executeAction(cwd, args.toString(), 60_000)
    }

    private fun executeAction(cwd: String, argsJson: String, timeoutMs: Int): DshBridgeResult {
        val command = buildShellCommand(script, argsJson, dshRuntimeBin, dshHome)
        val rawResponse = runCatching {
            executor.execute(cwd, command, timeoutMs)
        }.getOrElse { failure ->
            return DshBridgeResult(
                ok = false,
                errorCode = "DSH_BRIDGE_FAILED",
                errorMessage = "终端执行异常：${failure.message ?: failure.javaClass.simpleName}",
            )
        }

        return parseTerminalOutput(rawResponse)
    }

    companion object {
        const val SCRIPT_ASSET_PATH = "agent/dsh_bridge.py"
        const val DEFAULT_RUNTIME_BIN = "/usr/local/bin/dsh"
        const val DEFAULT_DSH_HOME = "/root/.dsh"

        fun loadScriptFromAssets(context: Context): String =
            context.assets.open(SCRIPT_ASSET_PATH).bufferedReader().use { it.readText() }

        /** 单引号包裹转义，符合 POSIX sh 规范，对标 SubAgentWorkspace。 */
        internal fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

        /**
         * 拼装命令：
         * 1. 探测系统 python3，若缺失以固定 JSON 格式输出兜底错误并退出；
         * 2. 在环境变量中注入 DSH_RUNTIME_BIN 与 DSH_HOME；
         * 3. 执行 python3 -I -c '<script>' '<argsJson>'。
         */
        internal fun buildShellCommand(
            script: String,
            argsJson: String,
            runtimeBin: String = DEFAULT_RUNTIME_BIN,
            dshHome: String = DEFAULT_DSH_HOME,
        ): String {
            val quotedScript = quote(script)
            val quotedArgs = quote(argsJson)
            val quotedRuntime = quote(runtimeBin)
            val quotedHome = quote(dshHome)
            return "command -v python3 >/dev/null 2>&1 || " +
                "{ printf '%s' '{\"ok\":false,\"code\":\"RUNTIME_MISSING\",\"hint\":\"python3 is required\"}'; exit 0; }; " +
                "DSH_RUNTIME_BIN=$quotedRuntime DSH_HOME=$quotedHome python3 -I -c $quotedScript $quotedArgs"
        }

        /**
         * 解析终端执行器信封返回（或裸 stdout）。
         */
        internal fun parseTerminalOutput(rawEnvelope: String): DshBridgeResult {
            val envelope = runCatching { JSONObject(rawEnvelope) }.getOrNull()
            if (envelope != null && envelope.has("stdout")) {
                if (envelope.optBoolean("timed_out")) {
                    return DshBridgeResult(
                        ok = false,
                        errorCode = "PROMPT_TIMEOUT",
                        errorMessage = "DeepSeek Harness 终端执行超时",
                    )
                }
                val stdout = envelope.optString("stdout")
                val parsed = parseBridgeOutput(stdout)
                if (parsed.ok || parsed.errorCode != "DSH_BRIDGE_FAILED") {
                    return parsed
                }
                val exitCode = envelope.optInt("exit_code", 0)
                if (exitCode != 0) {
                    val stderr = envelope.optString("stderr")
                    return DshBridgeResult(
                        ok = false,
                        errorCode = "RUNTIME_EXITED",
                        errorMessage = stderr.ifBlank { "进程异常退出（exit_code=$exitCode）" },
                    )
                }
                return parsed
            }
            return parseBridgeOutput(rawEnvelope)
        }

        /**
         * 从标准输出文本中提取并解析桥接脚本打印的那一行 JSON。
         */
        internal fun parseBridgeOutput(stdout: String): DshBridgeResult {
            val clean = stdout.trim()
            if (clean.isBlank()) {
                return DshBridgeResult(
                    ok = false,
                    errorCode = "DSH_BRIDGE_FAILED",
                    errorMessage = "桥接脚本未返回任何输出",
                )
            }
            val jsonLine = clean.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .lastOrNull { it.startsWith("{") && it.endsWith("}") }
                ?: return DshBridgeResult(
                    ok = false,
                    errorCode = "DSH_BRIDGE_FAILED",
                    errorMessage = "未能从输出中提取合法 JSON：${clean.take(200)}",
                )

            val obj = runCatching { JSONObject(jsonLine) }.getOrElse {
                return DshBridgeResult(
                    ok = false,
                    errorCode = "DSH_BRIDGE_FAILED",
                    errorMessage = "解析桥接返回 JSON 失败：${it.message}",
                )
            }

            val ok = obj.optBoolean("ok", false)
            if (ok) {
                return DshBridgeResult(
                    ok = true,
                    sessionId = obj.optString("session_id").takeIf { it.isNotBlank() },
                    resumed = obj.optBoolean("resumed", false),
                    response = obj.optString("response"),
                    responseTruncated = obj.optBoolean("response_truncated", false),
                    stopReason = obj.optString("stop_reason").takeIf { it.isNotBlank() },
                    rawJson = obj,
                )
            } else {
                val code = obj.optString("code").takeIf { it.isNotBlank() } ?: "DSH_BRIDGE_FAILED"
                val message = buildString {
                    obj.optString("hint").takeIf { it.isNotBlank() }?.let { append(it) }
                    obj.optString("detail").takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" (").append(it).append(")") else append(it)
                    }
                    if (isEmpty()) {
                        obj.optString("message").takeIf { it.isNotBlank() }?.let { append(it) }
                    }
                    if (isEmpty()) {
                        obj.optString("partial_response").takeIf { it.isNotBlank() }?.let {
                            append("部分答复：").append(it.takeLast(500))
                        }
                    }
                    if (isEmpty()) {
                        append("dsh 子代理执行失败（错误码：").append(code).append("）")
                    }
                }
                return DshBridgeResult(
                    ok = false,
                    sessionId = obj.optString("session_id").takeIf { it.isNotBlank() },
                    errorCode = code,
                    errorMessage = message,
                    rawJson = obj,
                )
            }
        }
    }
}

/**
 * 基于 RootShellTerminalController 的终端执行器实现。
 */
internal class TerminalControllerDshExecutor(
    private val context: Context,
    private val terminalController: RootShellTerminalController,
) : DshCommandExecutor {
    override fun execute(cwd: String, command: String, timeoutMs: Int): String {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        return terminalController.terminalAction(
            action = "open_and_exec",
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            identity = identity,
            mergeStderr = false,
            sessionId = null,
            jobId = null,
            async = false,
            offsetChars = 0,
            maxChars = 64_000,
            closeIfDone = true,
            environment = "linux",
        )
    }
}
