package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null

    /**
     * 当前请求占用的窗口：对齐 ST「输入」。
     * inputTokens 是含缓存的总 prompt，cachedTokens 是它的子集；
     * 各 provider 在自己的边界完成归一（见 AnthropicUsageTotals）。
     * completion 是本轮输出，下一轮才会进 prompt。
     */
    fun occupancyTokens(): Int? {
        inputTokens?.takeIf { it > 0 }?.let { return it }
        return contextTokens?.takeIf { it > 0 }
    }
}
