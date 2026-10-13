package io.github.mangi.eta.agent.dsh

import org.json.JSONObject

/**
 * DeepSeek Harness (dsh) 子代理调用的结果封装。
 *
 * 抽成不依赖 Android / 网络的小结构，便于单元测试直接覆盖
 * "桥接结果 + git 现场 -> 主智能体可见 JSON" 的映射规则。
 */
internal data class DshSubagentOutcome(
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
            .put("tool", "delegate_to_dsh")
            .put("task", task)
            .put("project_path", projectPath)
            .put("content", content.takeLast(MAX_OUTPUT_CHARS))
            .put("output", content.takeLast(MAX_OUTPUT_CHARS))
            .put("git_modified_files", gitModifiedFiles.ifBlank { "无 git 变更或非 git 仓库" })
            .put("git_diff_stat", gitDiffStat.ifBlank { "无代码增删差异" })
        sessionId?.takeIf { it.isNotBlank() }?.let { json.put("session_id", it) }
        errorCode?.takeIf { it.isNotBlank() }?.let { json.put("error_code", it) }
        errorMessage?.takeIf { it.isNotBlank() }?.let { json.put("error_message", it) }
        if (ok) {
            json.put("message", "DeepSeek Harness (dsh) 子代理执行完成并已交付变更。")
        } else {
            json.put("code", errorCode ?: "SUBAGENT_FAILED")
            json.put(
                "message",
                buildString {
                    append("DeepSeek Harness (dsh) 子代理执行失败")
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
