package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.kimi.KimiReplyExtractor
import io.github.mangi.eta.agent.kimi.KimiSubagentOutcome
import io.github.mangi.eta.agent.kimi.KimiWebApiClient
import io.github.mangi.eta.agent.kimi.KimiWebApiException
import io.github.mangi.eta.agent.kimi.KimiWebService
import io.github.mangi.eta.agent.kimi.SupervisorKimiDaemonGateway
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import org.json.JSONObject
import java.io.File

/**
 * 内置 Kimi Code 编程子代理协同调度工具。
 *
 * 实现方式在 v23 之后从「在前台终端里跑 `kimi --prompt` 无头命令」改为
 * 「直连本机 `kimi web` 的 REST API」：
 *
 * | 维度 | 旧（无头命令） | 新（REST 直连） |
 * |---|---|---|
 * | 上下文 | 每次冷启动，无历史 | 复用会话，多轮共享 workspace |
 * | 指令安全 | 需 shell 转义，特殊字符会损坏 | JSON 递交，零转义风险 |
 * | 生命周期 | 依赖前台终端存活 | 守护任务 + 短连接，不受回收影响 |
 * | 附件 | 不支持 | 原生支持 image/file content 片段 |
 *
 * 端点与鉴权契约见 `KimiWebEndpoint`（横幅 ANSI 剥离、端口递增、base64url Token），
 * 请求/响应契约见 `KimiWebApiClient`。子代理完成后仍会在容器内采集 Git 现场，
 * 与答复一并回传给主智能体。
 */
internal class KimiCodeSubagentTool(
    private val context: Context,
    private val terminalController: RootShellTerminalController,
    private val service: KimiWebService,
) {

    /** 兼容旧调用点：由工具装配处传入守护任务宿主。 */
    constructor(
        context: Context,
        terminalController: RootShellTerminalController,
        gateway: SupervisorKimiDaemonGateway,
    ) : this(context, terminalController, KimiWebService(gateway))

    fun delegate(args: JSONObject): String {
        val task = args.optString("task").trim()
        if (task.isBlank()) {
            return errorJson("INVALID_ARGUMENTS", "必须提供 task 任务描述")
        }

        val projectPath = args.optString("project_path").trim().ifBlank { DEFAULT_PROJECT_PATH }
        val timeoutSeconds = args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS).coerceIn(10, 600)

        // 1. 环境就绪校验：PRoot 容器与 Kimi 扩展包缺一不可
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val terminalEnv = distribution.terminalEnvironment
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return errorJson(
                "LINUX_NOT_READY",
                "Linux PRoot 容器环境尚未就绪，无法启动 Kimi Code 子代理。请先在应用内配置并启动 Linux 环境。",
            )
        }
        if (!File(rootfs, AlpineEnvironmentPaths.KIMI_TOOLS_MARKER).exists()) {
            return errorJson(
                "KIMI_NOT_INSTALLED",
                "Linux 环境中尚未安装 Kimi Code 组件。请在 Eta 的 Linux 环境管理里安装 kimi 扩展包后重试。",
            )
        }

        // 2. 用户身份拉起守护进程需要 FGS 引用；与 KimiWebLauncher 相同的租约模型
        val identity = TerminalRuntime.defaultIdentity(terminalEnv, rootfs.absolutePath)
        val leaseId = "kimi-subagent"
        if (identity == "user" && !AgentExecutionService.acquire(context, leaseId) {}) {
            return errorJson("BACKGROUND_START_NOT_ALLOWED", "无法启动后台执行服务，请返回 Eta 后重试")
        }

        // 3. 端点解析 → 会话复用 → 提示词提交 → 轮询终止
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
        return try {
            runSession(task, projectPath, deadline)
        } finally {
            if (identity == "user") AgentExecutionService.release(leaseId)
        }
    }

    private fun runSession(task: String, projectPath: String, deadlineAt: Long): String {
        val endpoint = service.ensureEndpoint { millis -> Thread.sleep(millis.coerceAtLeast(1L)) }
            ?: return errorJson(
                "ENDPOINT_UNAVAILABLE",
                "无法获取 Kimi 本机服务端点。请确认 `kimi web` 能在 Linux 容器内正常启动。",
            )

        // 服务端若因故退出，ensureEndpoint 缓存里的端点会变成死地址；
        // 连接失败时清缓存重解析一次，避免用户必须手动重启 App 才能恢复。
        val client = service.clientFor(endpoint)
        val sessionId = try {
            service.sessionFor(client, projectPath)
        } catch (failure: KimiWebApiException) {
            return errorJson("SESSION_FAILED", "创建 Kimi 会话失败：${failure.message}")
        }

        return try {
            executeAndCollect(client, sessionId, task, projectPath, deadlineAt)
        } catch (failure: KimiWebApiException) {
            if (isSessionGone(failure)) {
                // 服务端重启后旧 sessionId 会失效；丢弃缓存重开一轮，只重试一次
                service.forgetSession(projectPath)
                val fresh = service.sessionFor(client, projectPath)
                runCatching { executeAndCollect(client, fresh, task, projectPath, deadlineAt) }
                    .getOrElse { errorJson("SUBAGENT_FAILED", it.message ?: "Kimi 会话执行失败") }
            } else {
                errorJson("SUBAGENT_FAILED", failure.message)
            }
        } catch (failure: java.io.IOException) {
            // 连接被拒通常意味着服务端已退出或端口变更：丢弃端点缓存，下次调用重新解析
            service.forgetEndpoint()
            errorJson("SUBAGENT_UNREACHABLE", failure.message ?: "无法连接 Kimi 本机服务")
        }
    }

    private fun executeAndCollect(
        client: KimiWebApiClient,
        sessionId: String,
        task: String,
        projectPath: String,
        deadlineAt: Long,
    ): String {
        val prompt = buildPrompt(task, projectPath)
        client.submitPrompt(sessionId, prompt, attachments = emptyList())
        awaitIdle(client, sessionId, deadlineAt)

        val reply = KimiReplyExtractor.extract(client.listMessages(sessionId, limit = MESSAGE_PAGE_SIZE))
        val git = collectGitSnapshot(projectPath)
        return KimiSubagentOutcome(
            ok = true,
            task = task,
            projectPath = projectPath,
            sessionId = sessionId,
            content = reply,
            gitModifiedFiles = git.first,
            gitDiffStat = git.second,
        ).toJson()
    }

    /**
     * 轮询到 `busy == false` 为止。
     *
     * 单次非 busy 不足以判定结束：提示词入队与真正开始执行之间有一小段空窗，
     * 因此要求「首轮延迟 + 连续两次非 busy」才收敛，同时受总超时约束；
     * 超时先 `abort` 再返回，避免服务端继续占用上下文窗口。
     */
    private fun awaitIdle(client: KimiWebApiClient, sessionId: String, deadlineAt: Long) {
        Thread.sleep(INITIAL_SETTLE_MS)
        var idleStreak = 0
        while (true) {
            val busy = client.sessionStatus(sessionId).busy
            idleStreak = if (busy) 0 else idleStreak + 1
            if (idleStreak >= IDLE_STREAK_REQUIRED) return
            if (SystemClock.elapsedRealtime() >= deadlineAt) {
                runCatching { client.abort(sessionId) }
                throw KimiWebApiException("TIMEOUT", "子代理执行超过 ${timeoutSecondsLabel(deadlineAt)} 的预算，已中止本轮")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun timeoutSecondsLabel(deadlineAt: Long): String {
        val remaining = (deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return "${remaining / 1000}s"
    }

    /** 采集容器内的 Git 现场；失败时返回空串，不影响答复回传。 */
    private fun collectGitSnapshot(projectPath: String): Pair<String, String> {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        val script = buildString {
            append("cd ").append(shellQuote(projectPath)).append(" 2>/dev/null || exit 0\n")
            append("echo '===GIT_STATUS_BEGIN==='\n")
            append("git status --short 2>/dev/null || true\n")
            append("echo '===GIT_DIFF_STAT==='\n")
            append("git diff --stat 2>/dev/null || true\n")
            append("echo '===SUBAGENT_END==='\n")
        }
        val raw = runCatching {
            terminalController.terminalAction(
                action = "open_and_exec",
                command = script,
                cwd = projectPath,
                timeoutMs = GIT_TIMEOUT_MS,
                identity = identity,
                mergeStderr = true,
                sessionId = null,
                jobId = null,
                async = false,
                offsetChars = 0,
                maxChars = 16_000,
                closeIfDone = true,
                environment = environment.wireName,
            )
        }.getOrNull() ?: return "" to ""

        val stdout = runCatching { JSONObject(raw).optString("stdout") }.getOrDefault("")
        val status = extractBetween(stdout, "===GIT_STATUS_BEGIN===", "===GIT_DIFF_STAT===").trim()
        val diff = extractBetween(stdout, "===GIT_DIFF_STAT===", "===SUBAGENT_END===").trim()
        return status to diff
    }

    private fun buildPrompt(task: String, projectPath: String): String = buildString {
        append("你正在 Eta 的 Linux 容器中作为编程子代理工作。\n")
        append("工作目录：").append(projectPath).append("\n")
        append("请在该目录内完成下面的任务，直接修改文件，不要只给出建议或代码片段。\n")
        append("完成后用简洁的中文说明你改动了哪些文件、关键实现思路以及未完成的遗留点。\n\n")
        append("任务：\n").append(task)
    }

    /** 依据 [KimiWebApiException.code] 判断是否为「会话不存在」类错误。 */
    private fun isSessionGone(failure: KimiWebApiException): Boolean {
        val code = failure.code.uppercase()
        if (code.contains("SESSION_NOT_FOUND") || code.contains("SESSION_NOT_EXIST")) return true
        if (code == "HTTP_404") return true
        return failure.message.contains("session not found", ignoreCase = true)
    }

    private fun extractBetween(source: String, startTag: String, endTag: String): String {
        val startIdx = source.indexOf(startTag)
        if (startIdx == -1) return ""
        val contentStart = startIdx + startTag.length
        val endIdx = source.indexOf(endTag, contentStart)
        return if (endIdx != -1) source.substring(contentStart, endIdx) else source.substring(contentStart)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("tool", "delegate_to_kimi_code")
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val DEFAULT_PROJECT_PATH = "/workspace"
        const val DEFAULT_TIMEOUT_SECONDS = 180
        const val MESSAGE_PAGE_SIZE = 20
        const val INITIAL_SETTLE_MS = 1_000L
        const val POLL_INTERVAL_MS = 1_500L
        const val IDLE_STREAK_REQUIRED = 2
        const val GIT_TIMEOUT_MS = 30_000
    }
}