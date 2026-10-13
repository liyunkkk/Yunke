package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import org.json.JSONObject

/**
 * DeepSeek Harness (dsh) 编程子代理协同调度工具。
 *
 * 主智能体将复杂编码、重构或多文件修改任务委派给隔离 Linux 环境中的 dsh CLI。
 * 会话按 Eta 对话绑定（`conversation_id` 参数），同一对话的多轮委派共享同一个
 * dsh 上下文；缺省时退化到 `default_main_session`。
 *
 * 执行流程：
 * 1. 校验 project_path 合法性；
 * 2. 检查 Linux 环境就绪状态并申请后台执行租约；
 * 3. 关联/恢复 conversationId 对应的 sessionId；
 * 4. 调起 DshBridge 执行提示词并等待结束；
 * 5. 采集容器内的 Git 变更现场（git status --porcelain 与 git diff --stat）；
 * 6. 将结果打包为结构化 DshSubagentOutcome 并序列化返回。
 */
internal class DshSubagentTool(
    private val context: Context,
    private val terminalController: RootShellTerminalController,
    private val bindingStore: DshSessionBindingStore = FileDshSessionBindingStore(context),
    private val bridge: DshBridge = DshBridge(context, terminalController),
    private val conversationId: String = DEFAULT_CONVERSATION_ID,
) {

    constructor(
        context: Context,
        terminalController: RootShellTerminalController,
        conversationId: String = DEFAULT_CONVERSATION_ID,
    ) : this(
        context = context,
        terminalController = terminalController,
        bindingStore = FileDshSessionBindingStore(context),
        bridge = DshBridge(context, terminalController),
        conversationId = conversationId,
    )

    fun delegate(args: JSONObject): String {
        val task = args.optString("task").trim()
        if (task.isBlank()) {
            return outcomeError("INVALID_ARGUMENTS", "必须提供 task 任务描述", task = "")
        }

        val rawProjectPath = args.optString("project_path").trim()
        val projectPath = if (rawProjectPath.isBlank()) DEFAULT_PROJECT_PATH else rawProjectPath
        if (!isValidProjectPath(projectPath)) {
            return outcomeError(
                "INVALID_ARGUMENTS",
                "project_path 必须是合法的绝对路径且不能包含路径穿越：$projectPath",
                task = task,
                projectPath = projectPath,
            )
        }

        val timeoutSeconds = resolveTimeoutSeconds(
            if (args.has("timeout_seconds")) args.optInt("timeout_seconds") else null,
        )

        val bindingKey = args.optString("conversation_id").trim()
            .ifBlank { conversationId }
            .ifBlank { DEFAULT_CONVERSATION_ID }

        // 1. 环境就绪校验：PRoot 容器必须就绪
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val terminalEnv = distribution.terminalEnvironment
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return outcomeError(
                "LINUX_NOT_READY",
                "Linux PRoot 容器环境尚未就绪，无法启动 DeepSeek Harness (dsh) 子代理。请先在应用内配置并启动 Linux 环境。",
                task = task,
                projectPath = projectPath,
            )
        }

        // 2. 用户身份拉起需要申请 FGS 租约保活
        val identity = TerminalRuntime.defaultIdentity(terminalEnv, rootfs.absolutePath)
        val leaseId = "dsh-subagent"
        if (identity == "user" && !AgentExecutionService.acquire(context, leaseId) {}) {
            return outcomeError(
                "BACKGROUND_START_NOT_ALLOWED",
                "无法启动后台执行服务，请返回 Eta 后重试",
                task = task,
                projectPath = projectPath,
            )
        }

        return try {
            runDelegation(task, projectPath, bindingKey, timeoutSeconds)
        } finally {
            if (identity == "user") AgentExecutionService.release(leaseId)
        }
    }

    private fun runDelegation(
        task: String,
        projectPath: String,
        bindingKey: String,
        timeoutSeconds: Int,
    ): String {
        val settings = DshSettings.load(context)
        val currentBindings = bindingStore.load().toMutableMap()
        val existingSessionId = currentBindings[bindingKey]

        val prompt = buildPrompt(task, projectPath)
        val bridgeResult = bridge.run(
            task = prompt,
            projectPath = projectPath,
            timeoutSeconds = timeoutSeconds,
            sessionId = existingSessionId,
            settings = settings,
        )

        // 维护 Eta 对话与 dsh 会话之间的绑定
        val activeSessionId = bridgeResult.sessionId ?: existingSessionId
        if (!bridgeResult.sessionId.isNullOrBlank() && bridgeResult.sessionId != existingSessionId) {
            currentBindings[bindingKey] = bridgeResult.sessionId
            bindingStore.save(currentBindings)
        }

        // 采集 Git 现场（修改文件列表与 diff 统计）
        val git = collectGitSnapshot(projectPath)

        return DshSubagentOutcome(
            ok = bridgeResult.ok,
            task = task,
            projectPath = projectPath,
            sessionId = activeSessionId,
            content = bridgeResult.response,
            gitModifiedFiles = git.first,
            gitDiffStat = git.second,
            errorCode = if (bridgeResult.ok) null else bridgeResult.errorCode,
            errorMessage = if (bridgeResult.ok) null else bridgeResult.errorMessage,
        ).toJson()
    }

    private fun buildPrompt(task: String, projectPath: String): String = buildString {
        append("你正在 Eta 的 Linux 容器中作为 DeepSeek Harness (dsh) 编程子代理工作。\n")
        append("工作目录：").append(projectPath).append("\n")
        append("请在该目录内完成下面的任务，直接修改文件，不要只给出建议或代码片段。\n")
        append("完成后用简洁的中文说明你改动了哪些文件、关键实现思路以及未完成的遗留点。\n\n")
        append("任务：\n").append(task)
    }

    /**
     * 采集容器内的 Git 现场（git status --porcelain 与 git diff --stat）。
     * 复用 KimiCodeSubagentTool 的实现方式，以隔离标记提取。
     */
    private fun collectGitSnapshot(projectPath: String): Pair<String, String> {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        val script = buildString {
            append("cd ").append(shellQuote(projectPath)).append(" 2>/dev/null || exit 0\n")
            append("echo '===GIT_STATUS_BEGIN==='\n")
            append("git status --porcelain 2>/dev/null || git status --short 2>/dev/null || true\n")
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

    private fun extractBetween(source: String, startTag: String, endTag: String): String {
        val startIdx = source.indexOf(startTag)
        if (startIdx == -1) return ""
        val contentStart = startIdx + startTag.length
        val endIdx = source.indexOf(endTag, contentStart)
        return if (endIdx != -1) source.substring(contentStart, endIdx) else source.substring(contentStart)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun isValidProjectPath(path: String): Boolean {
        if (!path.startsWith("/")) return false
        val segments = path.split('/')
        return segments.none { it == ".." || it == "." }
    }

    private fun outcomeError(
        code: String,
        message: String,
        task: String = "",
        projectPath: String = DEFAULT_PROJECT_PATH,
    ): String =
        DshSubagentOutcome(
            ok = false,
            task = task,
            projectPath = projectPath,
            sessionId = null,
            content = "",
            gitModifiedFiles = "",
            gitDiffStat = "",
            errorCode = code,
            errorMessage = message,
        ).toJson()

    internal companion object {
        const val DEFAULT_PROJECT_PATH = "/workspace"
        const val DEFAULT_CONVERSATION_ID = "default_main_session"
        const val DEFAULT_TIMEOUT_SECONDS = 180
        const val GIT_TIMEOUT_MS = 30_000

        internal fun resolveTimeoutSeconds(raw: Int?): Int =
            if (raw == null || raw <= 0) DEFAULT_TIMEOUT_SECONDS else raw.coerceIn(10, 600)
    }
}
