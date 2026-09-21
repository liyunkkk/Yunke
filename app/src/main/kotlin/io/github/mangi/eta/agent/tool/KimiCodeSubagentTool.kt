package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.kimi.FileKimiSessionBindingStore
import io.github.mangi.eta.agent.kimi.KimiCodeConfig
import io.github.mangi.eta.agent.kimi.KimiReplyExtractor
import io.github.mangi.eta.agent.kimi.KimiSubagentOutcome
import io.github.mangi.eta.agent.kimi.KimiWebApiClient
import io.github.mangi.eta.agent.kimi.KimiWebApiException
import io.github.mangi.eta.agent.kimi.KimiWebService
import io.github.mangi.eta.agent.kimi.SupervisorKimiDaemonGateway
import io.github.mangi.eta.agent.kimi.isSessionMissing
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
 *
 * 两个曾导致现场故障的契约细节：
 * 1. **必须随提示词下发模型**。服务端 `POST /sessions/{id}/prompts` 会消费
 *    `body.model` 并写回会话 profile；不传则 profile 保持空串，每一轮都在
 *    `turn.ended` 里以 `model.not_configured` 失败。模型取自容器内
 *    `config.toml` 的 `default_model`（见 [KimiCodeConfig]）。
 * 2. **不能把 failed 当成功**。`/status` 只回 `busy`，执行失败时同样会收敛为
 *    `busy == false`，必须再读会话的 `last_turn_reason` 才能发现失败。
 *
 * 会话按 Eta 对话绑定（`conversation_id` 参数），同一对话的多轮委派共享同一个
 * Kimi 上下文；缺省时退化到 `default_main_session`，兼容不传该参数的老调用。
 */
internal class KimiCodeSubagentTool(
    private val context: Context,
    private val terminalController: RootShellTerminalController,
    private val service: KimiWebService,
    /** 兜底绑定键：装配处传入当前 Eta 对话 id，工具调用未显式给出时使用。 */
    private val conversationId: String = DEFAULT_CONVERSATION_ID,
) {

    /** 兼容旧调用点：由工具装配处传入守护任务宿主。 */
    constructor(
        context: Context,
        terminalController: RootShellTerminalController,
        gateway: SupervisorKimiDaemonGateway,
        conversationId: String = DEFAULT_CONVERSATION_ID,
    ) : this(
        context,
        terminalController,
        KimiWebService(
            daemon = gateway,
            bindingStore = FileKimiSessionBindingStore(context),
        ),
        conversationId = conversationId,
    )

    fun delegate(args: JSONObject): String {
        val task = args.optString("task").trim()
        if (task.isBlank()) {
            return errorJson("INVALID_ARGUMENTS", "必须提供 task 任务描述")
        }

        val projectPath = args.optString("project_path").trim().ifBlank { DEFAULT_PROJECT_PATH }
        val timeoutSeconds = args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS).coerceIn(10, 600)
        // 绑定键：同一个 Eta 对话始终复用同一个 Kimi 会话，避免上下文散乱重建。
        // 模型可以显式指定，缺省时用装配处注入的当前对话 id。
        val bindingKey = args.optString("conversation_id").trim()
            .ifBlank { conversationId }
            .ifBlank { DEFAULT_CONVERSATION_ID }
        val conversationTitle = args.optString("conversation_title").trim()

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
            runSession(task, projectPath, bindingKey, conversationTitle, rootfs, deadline)
        } finally {
            if (identity == "user") AgentExecutionService.release(leaseId)
        }
    }

    private fun runSession(
        task: String,
        projectPath: String,
        bindingKey: String,
        conversationTitle: String,
        rootfs: File,
        deadlineAt: Long,
    ): String {
        val endpoint = service.ensureEndpoint { millis -> Thread.sleep(millis.coerceAtLeast(1L)) }
            ?: return errorJson(
                "ENDPOINT_UNAVAILABLE",
                "无法获取 Kimi 本机服务端点。请确认 `kimi web` 能在 Linux 容器内正常启动。",
            )

        // 服务端若因故退出，ensureEndpoint 缓存里的端点会变成死地址；
        // 连接失败时清缓存重解析一次，避免用户必须手动重启 App 才能恢复。
        val client = service.clientFor(endpoint)
        val model = resolveModel(client, rootfs)
        val sessionId = try {
            service.sessionFor(
                client = client,
                cwd = projectPath,
                bindingKey = bindingKey,
                title = conversationTitle,
            )
        } catch (failure: KimiWebApiException) {
            return errorJson("SESSION_FAILED", "创建 Kimi 会话失败：${failure.message}")
        }

        return try {
            executeAndCollect(client, sessionId, task, projectPath, model, deadlineAt)
        } catch (failure: KimiWebApiException) {
            if (failure.isSessionMissing()) {
                // 服务端重启后旧 sessionId 会失效；丢弃绑定重开一轮，只重试一次
                service.forgetSession(bindingKey)
                val fresh = service.sessionFor(
                    client = client,
                    cwd = projectPath,
                    bindingKey = bindingKey,
                    title = conversationTitle,
                )
                runCatching { executeAndCollect(client, fresh, task, projectPath, model, deadlineAt) }
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
        model: String?,
        deadlineAt: Long,
    ): String {
        val prompt = buildPrompt(task, projectPath)
        // model 必须随提示词下发：服务端据此写回会话 profile，不传则本轮
        // 会在 turn.ended 里以 model.not_configured 失败。
        client.submitPrompt(sessionId, prompt, attachments = emptyList(), model = model)
        awaitIdle(client, sessionId, deadlineAt)

        val git = collectGitSnapshot(projectPath)
        // 执行失败时 busy 同样会收敛为 false，必须回读 last_turn_reason，
        // 否则会把一次模型未配置/鉴权失败的轮次当作成功交付给主智能体。
        val failure = detectTurnFailure(client, sessionId)
        if (failure != null) {
            return KimiSubagentOutcome(
                ok = false,
                task = task,
                projectPath = projectPath,
                sessionId = sessionId,
                content = "",
                gitModifiedFiles = git.first,
                gitDiffStat = git.second,
                errorCode = failure.code,
                errorMessage = failure.message,
            ).toJson()
        }

        val reply = KimiReplyExtractor.extract(client.listMessages(sessionId, limit = MESSAGE_PAGE_SIZE))
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

    /** 上一轮失败的码与描述；未失败或无法判定时返回 null。 */
    private data class TurnFailure(val code: String, val message: String)

    /**
     * 判定刚刚结束的那一轮是否失败。
     *
     * 先用会话摘要的 `last_turn_reason` 定性（服务端 `sessionSchema` 的取值域
     * 只有 completed / cancelled / failed），确认为 failed 后再去容器内
     * `wire.jsonl` 捞该轮的 `turn.ended.error`，把真实的 code / message 透出。
     * 拿不到明细时也要带上 `last_turn_reason=failed`，绝不返回伪成功。
     */
    private fun detectTurnFailure(client: KimiWebApiClient, sessionId: String): TurnFailure? {
        val summary = runCatching { client.sessionSummary(sessionId) }.getOrNull()
        val reason = summary?.lastTurnReason
        if (reason != null && reason != "failed") return null

        val detail = readLastTurnError(sessionId)
        if (detail != null) return detail

        // 摘要读不到（如接口异常）且容器里也没有失败记录时，不阻断成功路径。
        if (reason == null) return null
        return TurnFailure(
            code = "TURN_FAILED",
            message = "Kimi 本轮以 failed 结束，但未能从容器日志中读到具体错误。",
        )
    }

    /**
     * 从容器内会话目录的 `wire.jsonl` 中取最后一条 `turn.ended` 的 error。
     *
     * 服务端只在 `turn.ended` 事件里携带 `error.code` / `error.message`
     * （如 `model.not_configured`、`provider.auth_error`），REST 层的
     * `last_turn_reason` 只有粗粒度结论，因此这里在容器内取文件尾部，
     * 由 Kotlin 侧解析最后一条 `turn.ended`。
     *
     * 命令刻意保持「无引号变量、无换行拼接」的极简形态：会话目录名用 glob
     * 匹配（`sessions/<workspaceId>/session_<uuid>` 的 workspaceId 在 App 侧
     * 不可知），避免 shell 转义在跨层传递中再次损坏脚本。
     */
    private fun readLastTurnError(sessionId: String): TurnFailure? {
        if (!SESSION_ID_PATTERN.matches(sessionId)) return null
        val script = "grep -a " + TURN_ENDED_KEY + " " + KIMI_SESSIONS_ROOT + "/*/" +
            sessionId + "/agents/main/wire.jsonl 2>/dev/null | tail -n 1\n"
        val raw = runCatching {
            terminalController.terminalAction(
                action = "open_and_exec",
                command = script,
                cwd = KIMI_SESSIONS_ROOT,
                timeoutMs = GIT_TIMEOUT_MS,
                identity = currentIdentity(),
                mergeStderr = false,
                sessionId = null,
                jobId = null,
                async = false,
                offsetChars = 0,
                maxChars = WIRE_OUTPUT_MAX_CHARS,
                closeIfDone = true,
                environment = currentEnvironment(),
            )
        }.getOrNull() ?: return null

        val stdout = runCatching { JSONObject(raw).optString("stdout") }.getOrDefault("")
        val line = stdout.lineSequence().lastOrNull { it.contains(TURN_ENDED_KEY) } ?: return null
        val record = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (record.optString("reason") != "failed") return null
        val error = record.optJSONObject("error") ?: return TurnFailure(
            code = "TURN_FAILED",
            message = "Kimi 本轮以 failed 结束，服务端未提供错误详情。",
        )
        val code = error.optString("code").ifBlank { "TURN_FAILED" }
        val message = error.optString("message").ifBlank { "Kimi 本轮执行失败。" }
        return TurnFailure(code, message)
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

    private fun extractBetween(source: String, startTag: String, endTag: String): String {
        val startIdx = source.indexOf(startTag)
        if (startIdx == -1) return ""
        val contentStart = startIdx + startTag.length
        val endIdx = source.indexOf(endTag, contentStart)
        return if (endIdx != -1) source.substring(contentStart, endIdx) else source.substring(contentStart)
    }

    /**
     * 解析应随提示词下发的模型别名，取值即容器内 `config.toml` 的 `default_model`。
     *
     * 优先走 REST `GET /config`：服务端已把该配置文件解析成顶层 `default_model`
     * 字段，而 rootfs 里的 `config.toml` 是 `600 root:root`，App 进程直读会失败，
     * 直读只作为兜底。读不到时返回 null，此时仍会提交提示词，失败会经
     * [detectTurnFailure] 如实上报。
     */
    private fun resolveModel(client: KimiWebApiClient, rootfs: File): String? =
        client.defaultModel() ?: KimiCodeConfig.parseDefaultModel(KimiCodeConfig.read(rootfs))

    private fun currentIdentity(): String {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        return TerminalRuntime.defaultIdentity(distribution.terminalEnvironment, rootfsPath)
    }

    private fun currentEnvironment(): String =
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.wireName

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

        /** 未显式传入对话 id 时的绑定键，保证老调用仍能复用同一个会话。 */
        const val DEFAULT_CONVERSATION_ID = "default_main_session"

        /** 容器内会话根目录（`KIMI_CODE_HOME` 固定为 `/root/.kimi-code`）。 */
        const val KIMI_SESSIONS_ROOT = "/root/.kimi-code/sessions"

        /** 会话 id 形如 `session_<uuid>`；不匹配时不必去容器里翻文件。 */
        val SESSION_ID_PATTERN = Regex("^session_[0-9a-fA-F-]{8,}$")

        /**
         * `wire.jsonl` 中 `turn.ended` 行的判别串。
         *
         * 兼作 grep 模式与 Kotlin 侧的行过滤条件。刻意不含引号：该字符串要穿过
         * Kotlin → 终端控制器 → PRoot shell 三层，带引号的模式在任一层都可能被
         * 转义破坏；命中后仍有 `reason == "failed"` 校验兜底。
         */
        const val TURN_ENDED_KEY = "turn.ended"

        /** 读取 `wire.jsonl` 尾行的输出上限。 */
        const val WIRE_OUTPUT_MAX_CHARS = 8_000
        const val DEFAULT_TIMEOUT_SECONDS = 180
        const val MESSAGE_PAGE_SIZE = 20
        const val INITIAL_SETTLE_MS = 1_000L
        const val POLL_INTERVAL_MS = 1_500L
        const val IDLE_STREAK_REQUIRED = 2
        const val GIT_TIMEOUT_MS = 30_000
    }
}