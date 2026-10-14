package io.github.mangi.eta.agent.model

/**
 * Antigravity 公开模型名 → Cloud Code Assist 运行时模型名。
 *
 * 意图：CCA 的 wire 模型 id 与设置界面展示的公开 id 并不一致，只按原名发送会命中不存在的模型。
 * 不这么做后果：tiered flash 与 thinking 后缀的 Claude 系列会返回 404。
 *
 * 来源：LiZhenNet/dsh-antigravity（MIT）的静态路由表，与上游 PR #1257 同源。
 * 维护：这是静态快照，Google 调整模型后必须人工同步；上游该表自 2026-08-18 起未再更新。
 */
internal object AntigravityModelRouting {
    private val runtimeModelIds = mapOf(
        "gemini-3.7-flash" to "gemini-3.7-flash-tiered",
        "gemini-3.6-flash" to "gemini-3.6-flash",
        "gemini-3.5-flash" to "gemini-3.5-flash",
        "gemini-3.1-pro" to "gemini-3.1-pro",
        "gemini-3.1-flash-image" to "gemini-3.1-flash-image",
        "gemini-3-flash" to "gemini-3-flash",
        "gemini-2.5-pro" to "gemini-2.5-pro",
        "gemini-2.5-flash" to "gemini-2.5-flash",
        "claude-opus-4-6" to "claude-opus-4-6-thinking",
        "claude-sonnet-4-6" to "claude-sonnet-4-6-thinking",
        "gpt-oss-120b" to "gpt-oss-120b",
    )

    fun runtimeModelId(publicModelId: String): String {
        val normalized = publicModelId.trim()
        return runtimeModelIds[normalized] ?: normalized
    }
}
