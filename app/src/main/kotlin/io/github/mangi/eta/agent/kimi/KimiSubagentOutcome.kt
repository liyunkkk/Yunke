package io.github.mangi.eta.agent.kimi

import org.json.JSONObject

/**
 * Kimi 子代理调用的结果封装。
 *
 * 抽成不依赖 Android / 网络的小结构，便于单元测试直接覆盖
 * "REST 结果 + git 现场 → 主智能体可见 JSON" 的映射规则。
 */
internal data class KimiSubagentOutcome(
    val ok: Boolean,
    val task: String,
    val projectPath: String,
    val sessionId: String?,
    val content: String,
    val gitModifiedFiles: String,
    val gitDiffStat: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {

    fun toJson(): String {
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "delegate_to_kimi_code")
            .put("task", task)
            .put("project_path", projectPath)
            .put("output", content.takeLast(MAX_OUTPUT_CHARS))
            .put("git_modified_files", gitModifiedFiles.ifBlank { "无 git 变更或非 git 仓库" })
            .put("git_diff_stat", gitDiffStat.ifBlank { "无代码增删差异" })
        sessionId?.takeIf { it.isNotBlank() }?.let { json.put("session_id", it) }
        if (ok) {
            json.put("message", "Kimi Code 子代理执行完成并已交付变更。")
        } else {
            json.put("code", errorCode ?: "SUBAGENT_FAILED")
            json.put(
                "message",
                buildString {
                    append("Kimi Code 子代理执行失败")
                    errorMessage?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                },
            )
        }
        return json.toString()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 4000
    }
}

/**
 * 从 Kimi 会话消息列表中提取可回传的最终答复。
 *
 * Kimi 的 assistant 消息里可能混有 `thinking` 与工具调用片段，
 * [KimiMessage] 已在解析阶段只保留 `text` 部分；这里从后往前找
 * 最后一条非空 assistant 文本，找不到时退化为提示性说明。
 */
internal object KimiReplyExtractor {

    fun extract(messages: List<KimiMessage>): String {
        val reply = messages
            .asReversed()
            .firstOrNull { it.role == "assistant" && it.text.isNotBlank() }
            ?.text
            ?.trim()
        return reply ?: "Kimi 会话已结束，但未返回文本答复（可能仅包含工具调用）。"
    }
}