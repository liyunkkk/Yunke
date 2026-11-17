package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/**
 * 单轮对话（一次用户请求触发的整条 agent 循环）的耗时累加器。
 *
 * 语义边界（和 Eta 参考实现一致）：
 * - [recordStep] 只吃「模型请求」这一段：从请求发出（或最后一次重试发出）到响应结束。
 *   工具执行、压缩请求、等用户确认都不进 llmMs，否则 TPS 会被工具时间稀释。
 * - [recordToolMs] 单独累计工具批次时间，含被拒绝/校验失败的调用不计（调用点只在真正执行处）。
 * - 单步没有首 token（空响应/取消）时不进 TTFT 分母，避免平均值被拉低。
 * - 单步输出 token 为 0 时不进解码统计，避免 tok/s 的分母出现无输出的耗时。
 *
 * 纯 Kotlin、无 Android 依赖，可直接单元测试。
 */
internal class AgentTurnMetrics {
    var stepCount: Int = 0
        private set

    private var llmMs: Long = 0L
    private var toolMs: Long = 0L
    private var ttftMs: Long = 0L
    private var ttftSteps: Int = 0
    private var decodeMs: Long = 0L
    private var decodeTokens: Int = 0

    private var contextTokens: Int? = null
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var reasoningTokens: Int? = null
    private var cachedTokens: Int? = null
    private var cacheCreationTokens: Int? = null

    fun reset() {
        stepCount = 0
        llmMs = 0L
        toolMs = 0L
        ttftMs = 0L
        ttftSteps = 0
        decodeMs = 0L
        decodeTokens = 0
        contextTokens = null
        inputTokens = null
        outputTokens = null
        reasoningTokens = null
        cachedTokens = null
        cacheCreationTokens = null
    }

    /**
     * 记一步模型请求，返回「本回合累计」的用量快照，直接喂给 UI 的 UsageReceived。
     *
     * @param stepLlmMs 本步模型请求耗时（不含工具）。
     * @param stepTtftMs 本步首 token 延迟；没有首 token 传 null。
     * @param stepDecodeMs 本步首 token 到响应结束的耗时；没有首 token 传 null。
     * @param stepUsage 本步 provider 回执（可为空：有些 provider 不回 usage）。
     * @param stepPromptTokens 本步发送侧 prompt token（云回执缺失时用它）。
     */
    fun recordStep(
        stepLlmMs: Long,
        stepTtftMs: Long?,
        stepDecodeMs: Long?,
        stepUsage: AgentTokenUsage?,
        stepPromptTokens: Int? = null,
    ): AgentTokenUsage {
        stepCount += 1
        llmMs += stepLlmMs.coerceAtLeast(0L)
        if (stepTtftMs != null) {
            ttftMs += stepTtftMs.coerceAtLeast(0L)
            ttftSteps += 1
        }
        val stepOut = stepUsage?.outputTokens
        if (stepDecodeMs != null && stepOut != null && stepOut > 0) {
            decodeMs += stepDecodeMs.coerceAtLeast(0L)
            decodeTokens += stepOut
        }
        stepUsage?.contextTokens?.let { contextTokens = it }
        (stepPromptTokens ?: stepUsage?.inputTokens)?.let { inputTokens = (inputTokens ?: 0) + it }
        stepUsage?.outputTokens?.let { outputTokens = (outputTokens ?: 0) + it }
        stepUsage?.reasoningTokens?.let { reasoningTokens = (reasoningTokens ?: 0) + it }
        stepUsage?.cachedTokens?.let { cachedTokens = (cachedTokens ?: 0) + it }
        stepUsage?.cacheCreationTokens?.let { cacheCreationTokens = (cacheCreationTokens ?: 0) + it }
        return snapshot()
    }

    fun recordToolMs(elapsedMs: Long) {
        toolMs += elapsedMs.coerceAtLeast(0L)
    }

    /** 本回合累计用量；未发生的字段保持 null，UI 不会凭空画出 0。 */
    fun snapshot(): AgentTokenUsage = AgentTokenUsage(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
        cacheCreationTokens = cacheCreationTokens,
        stepCount = stepCount.takeIf { it > 0 },
        llmMs = llmMs.takeIf { stepCount > 0 },
        toolMs = toolMs.takeIf { it > 0L },
        ttftMs = ttftMs.takeIf { ttftSteps > 0 },
        ttftSteps = ttftSteps.takeIf { it > 0 },
        decodeMs = decodeMs.takeIf { decodeTokens > 0 && it > 0L },
        decodeTokens = decodeTokens.takeIf { decodeMs > 0L && it > 0 },
    )
}
