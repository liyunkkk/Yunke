package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** Cache writes. Not a hit; already included in [inputTokens]. */
    val cacheCreationTokens: Int? = null,
    /* ---- 展示用耗时统计（只用于 UI，不参与计费与压缩判定） ----
     * 全部可空：旧运行记录、旧 IPC 帧、只回 token 的 provider 都不会带这些字段，
     * 反序列化后保持 null，UI 侧据此决定是否渲染统计。 */
    /** 本回合已完成的模型步数（每完成一次模型请求 +1）。 */
    val stepCount: Int? = null,
    /** 模型请求累计耗时，不含工具执行与等待用户的时间。 */
    val llmMs: Long? = null,
    /** 工具批次累计耗时。 */
    val toolMs: Long? = null,
    /** 首 token 延迟累计（只累加有首 token 的步）。 */
    val ttftMs: Long? = null,
    /** 参与 [ttftMs] 平均的步数。 */
    val ttftSteps: Int? = null,
    /** 解码阶段累计耗时（首 token 之后到响应结束）。 */
    val decodeMs: Long? = null,
    /** 参与 [decodeMs] 的累计输出 token，用于算 tok/s。 */
    val decodeTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            cacheCreationTokens == null &&
            stepCount == null &&
            llmMs == null &&
            toolMs == null &&
            ttftMs == null &&
            ttftSteps == null &&
            decodeMs == null &&
            decodeTokens == null

    /** 只带 token 用量、不带耗时：用于把耗时快照盖到已有的 token 回执上。 */
    fun withTimingsFrom(other: AgentTokenUsage): AgentTokenUsage = copy(
        stepCount = other.stepCount,
        llmMs = other.llmMs,
        toolMs = other.toolMs,
        ttftMs = other.ttftMs,
        ttftSteps = other.ttftSteps,
        decodeMs = other.decodeMs,
        decodeTokens = other.decodeTokens,
    )

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
