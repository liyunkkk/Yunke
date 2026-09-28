package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/**
 * Anthropic reports its cache subsets alongside `input_tokens` rather than inside it:
 * `input_tokens` counts only the uncached prefix, while `cache_read_input_tokens` and
 * `cache_creation_input_tokens` are separate. Every consumer here assumes the OpenAI
 * semantics, where `prompt_tokens` already contains `cached_tokens`
 * (see OpenAiChatCompletionsProvider.parseUsage). Normalising at the provider boundary
 * keeps one meaning for AgentTokenUsage.inputTokens: the whole prompt that occupied
 * the window, with cachedTokens as a subset of it.
 */
internal object AnthropicUsageTotals {

    /** The prompt actually sent: fresh input plus both cache subsets. */
    fun promptTokens(
        inputTokens: Int?,
        cacheReadTokens: Int?,
        cacheCreationTokens: Int?,
    ): Int? {
        val parts = listOfNotNull(inputTokens, cacheReadTokens, cacheCreationTokens)
        if (parts.isEmpty()) return null
        return parts.sumOf { it.coerceAtLeast(0) }
    }

    /**
     * Both cache subsets count as cache, matching the upstream cache bucket
     * (creation plus read). `inputTokens` is still the whole prompt, so the
     * remainder `inputTokens - cachedTokens` is only the uncached prefix.
     * Stats and the conversation usage popup both read that split.
     */
    fun cachedTokens(cacheReadTokens: Int?, cacheCreationTokens: Int? = null): Int? {
        val parts = listOfNotNull(cacheReadTokens, cacheCreationTokens)
        if (parts.isEmpty()) return null
        return parts.sumOf { it.coerceAtLeast(0) }
    }

    /** Builds window-consistent totals from a raw Anthropic `usage` object. */
    fun parse(intOf: (String) -> Int?): AgentTokenUsage? {
        val cacheRead = intOf("cache_read_input_tokens")
        val cacheCreation = intOf("cache_creation_input_tokens")
        return AgentTokenUsage(
            contextTokens = null,
            inputTokens = promptTokens(intOf("input_tokens"), cacheRead, cacheCreation),
            outputTokens = intOf("output_tokens"),
            reasoningTokens = intOf("thinking_output_tokens"),
            cachedTokens = cachedTokens(cacheRead, cacheCreation),
        ).takeUnless { it.isEmpty }
    }
}
