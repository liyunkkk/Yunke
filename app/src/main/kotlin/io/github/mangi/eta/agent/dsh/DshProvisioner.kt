package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONObject

internal data class DshProvisionStatus(
    val ok: Boolean,
    val installed: Boolean = false,
    val version: String? = null,
    val keyExists: Boolean = false,
    val runtimePath: String? = null,
    val keyFile: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val rawJson: JSONObject? = null,
)

internal data class DshProvisionInstallResult(
    val ok: Boolean,
    val installed: Boolean = false,
    val alreadyInstalled: Boolean = false,
    val version: String? = null,
    val runtimePath: String? = null,
    val message: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val rawJson: JSONObject? = null,
)

/**
 * 负责 dsh 运行时在 Linux 容器内的就绪状态查询与一键安装/修复。
 */
internal class DshProvisioner(
    private val script: String,
    private val executor: DshCommandExecutor,
) {
    constructor(
        context: Context,
        terminalController: RootShellTerminalController? = null,
    ) : this(
        script = loadScriptFromAssets(context),
        executor = TerminalControllerDshExecutor(
            context = context,
            terminalController = terminalController ?: createDefaultTerminalController(context),
        ),
    )

    fun status(
        keyFile: String = DshSettings.DEFAULT_KEY_FILE_PATH,
        runtimeBin: String = DshBridge.DEFAULT_RUNTIME_BIN,
        timeoutMs: Int = 30_000,
    ): DshProvisionStatus {
        val args = JSONObject()
            .put("action", "status")
            .put("key_file", keyFile)
            .put("runtime_bin", runtimeBin)
        val command = buildShellCommand(script, args.toString())
        val rawResponse = runCatching {
            executor.execute("/workspace", command, timeoutMs)
        }.getOrElse { failure ->
            return DshProvisionStatus(
                ok = false,
                errorCode = "PROVISION_EXEC_FAILED",
                errorMessage = "终端执行异常：${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        return parseStatusOutput(rawResponse)
    }

    fun install(
        version: String? = null,
        force: Boolean = false,
        timeoutMs: Int = 900_000,
    ): DshProvisionInstallResult {
        val args = JSONObject()
            .put("action", "install")
            .put("force", force)
        version?.takeIf { it.isNotBlank() }?.let { args.put("version", it) }
        val command = buildShellCommand(script, args.toString())
        val rawResponse = runCatching {
            executor.execute("/workspace", command, timeoutMs)
        }.getOrElse { failure ->
            return DshProvisionInstallResult(
                ok = false,
                errorCode = "PROVISION_EXEC_FAILED",
                errorMessage = "终端执行异常：${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        return parseInstallOutput(rawResponse)
    }

    companion object {
        const val SCRIPT_ASSET_PATH = "agent/dsh_provision.py"

        fun loadScriptFromAssets(context: Context): String =
            context.assets.open(SCRIPT_ASSET_PATH).bufferedReader().use { it.readText() }

        internal fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

        internal fun buildShellCommand(script: String, argsJson: String): String {
            val quotedScript = quote(script)
            val quotedArgs = quote(argsJson)
            return "command -v python3 >/dev/null 2>&1 || " +
                "{ printf '%s' '{\"ok\":false,\"code\":\"RUNTIME_MISSING\",\"hint\":\"python3 is required\"}'; exit 0; }; " +
                "python3 -I -c $quotedScript $quotedArgs"
        }

        internal fun parseStatusOutput(rawEnvelope: String): DshProvisionStatus {
            val (jsonObj, errorResult) = extractTerminalJson(rawEnvelope)
            if (jsonObj == null) {
                return DshProvisionStatus(
                    ok = false,
                    errorCode = errorResult?.first ?: "PROVISION_FAILED",
                    errorMessage = errorResult?.second ?: "未能从终端输出解析 JSON",
                )
            }
            val ok = jsonObj.optBoolean("ok", false)
            if (ok) {
                return DshProvisionStatus(
                    ok = true,
                    installed = jsonObj.optBoolean("installed", false),
                    version = jsonObj.optString("version").takeIf { it.isNotBlank() && it != "null" },
                    keyExists = jsonObj.optBoolean("key_exists", false),
                    runtimePath = jsonObj.optString("runtime_path").takeIf { it.isNotBlank() },
                    keyFile = jsonObj.optString("key_file").takeIf { it.isNotBlank() },
                    rawJson = jsonObj,
                )
            } else {
                val code = jsonObj.optString("code").ifBlank { "PROVISION_FAILED" }
                val hint = jsonObj.optString("hint").ifBlank {
                    jsonObj.optString("message").ifBlank { "状态检查失败（错误码：$code）" }
                }
                return DshProvisionStatus(
                    ok = false,
                    errorCode = code,
                    errorMessage = hint,
                    rawJson = jsonObj,
                )
            }
        }

        internal fun parseInstallOutput(rawEnvelope: String): DshProvisionInstallResult {
            val (jsonObj, errorResult) = extractTerminalJson(rawEnvelope)
            if (jsonObj == null) {
                return DshProvisionInstallResult(
                    ok = false,
                    errorCode = errorResult?.first ?: "PROVISION_FAILED",
                    errorMessage = errorResult?.second ?: "未能从终端输出解析 JSON",
                )
            }
            val ok = jsonObj.optBoolean("ok", false)
            if (ok) {
                return DshProvisionInstallResult(
                    ok = true,
                    installed = jsonObj.optBoolean("installed", true),
                    alreadyInstalled = jsonObj.optBoolean("already_installed", false),
                    version = jsonObj.optString("version").takeIf { it.isNotBlank() && it != "null" },
                    runtimePath = jsonObj.optString("runtime_path").takeIf { it.isNotBlank() },
                    message = jsonObj.optString("message").takeIf { it.isNotBlank() },
                    rawJson = jsonObj,
                )
            } else {
                val code = jsonObj.optString("code").ifBlank { "INSTALL_FAILED" }
                val hint = jsonObj.optString("hint").ifBlank {
                    jsonObj.optString("message").ifBlank { "安装失败（错误码：$code）" }
                }
                return DshProvisionInstallResult(
                    ok = false,
                    errorCode = code,
                    errorMessage = hint,
                    rawJson = jsonObj,
                )
            }
        }

        private fun extractTerminalJson(rawEnvelope: String): Pair<JSONObject?, Pair<String, String>?> {
            val envelope = runCatching { JSONObject(rawEnvelope) }.getOrNull()
            val textToParse = if (envelope != null && envelope.has("stdout")) {
                if (envelope.optBoolean("timed_out")) {
                    return Pair(null, Pair("TIMEOUT", "执行超时"))
                }
                val stdout = envelope.optString("stdout")
                val exitCode = envelope.optInt("exit_code", 0)
                if (exitCode != 0 && stdout.isBlank()) {
                    val stderr = envelope.optString("stderr")
                    return Pair(null, Pair("RUNTIME_EXITED", stderr.ifBlank { "进程异常退出（exit_code=$exitCode）" }))
                }
                stdout
            } else {
                rawEnvelope
            }

            val clean = textToParse.trim()
            if (clean.isBlank()) {
                return Pair(null, Pair("EMPTY_OUTPUT", "未获得任何输出"))
            }
            val jsonLine = clean.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .lastOrNull { it.startsWith("{") && it.endsWith("}") }
                ?: return Pair(null, Pair("INVALID_JSON", "输出中无合法 JSON 行：${clean.take(200)}"))

            val obj = runCatching { JSONObject(jsonLine) }.getOrNull()
                ?: return Pair(null, Pair("INVALID_JSON", "解析 JSON 失败"))

            return Pair(obj, null)
        }

        fun createDefaultTerminalController(context: Context): RootShellTerminalController {
            val distribution = LinuxEnvironmentSettingsRepository.current(context)
            val rootfsDir = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            return RootShellTerminalController(
                logger = AndroidAgentLogger,
                linuxRootfsPath = rootfsDir,
                linuxRootfsPathProvider = { env ->
                    env.linuxDistribution?.let { dist ->
                        LinuxEnvironmentPaths.rootfsDir(context, dist).absolutePath
                    }
                },
                selectedLinuxEnvironmentProvider = {
                    LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
                },
            )
        }
    }
}
