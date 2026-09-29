package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.GoogleAntigravityOAuth
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.OpenAiEndpointMode

/** Compression-only Chat Completions / Responses override. Independent of the session provider. */
internal object AgentCompressionEndpoint {
    fun parse(value: String?): String =
        if (value == OpenAiEndpointMode.RESPONSES) OpenAiEndpointMode.RESPONSES
        else OpenAiEndpointMode.CHAT_COMPLETIONS

    fun canOverride(config: AgentModelClient.ModelConfig): Boolean {
        if (config.providerType != io.github.mangi.eta.data.model.ProviderTypes.OPENAI_COMPATIBLE) return false
        if (OpenAiCodexOAuth.isCodexEndpoint(config.baseUrl)) return false
        if (GoogleAntigravityOAuth.isAntigravityEndpoint(config.baseUrl)) return false
        return config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS ||
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES
    }

    fun apply(config: AgentModelClient.ModelConfig, endpointMode: String?): AgentModelClient.ModelConfig {
        if (!canOverride(config)) return config
        val resolved = parse(endpointMode)
        return if (config.openAiEndpointMode == resolved) config
        else config.copy(openAiEndpointMode = resolved)
    }
}

internal object AgentCompressionBoundary {
    /** Every cut is between complete tool batches; malformed/orphaned results are not compactable. */
    fun balancedCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> {
        val cuts = collectCuts(history, strict = true)
        require(cuts.lastOrNull() == history.size) { "工具批次尚未完成" }
        return cuts
    }

    /** Complete-batch cut points even if the newest tool batch is still running. */
    fun availableCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> =
        collectCuts(history, strict = false)

    private fun collectCuts(
        history: List<AgentModelClient.ConversationMessage>,
        strict: Boolean,
    ): List<Int> {
        val pending = mutableSetOf<String>()
        val cuts = mutableListOf(0)
        history.forEachIndexed { index, message ->
            if (message.toolCallsJson.isNotBlank()) {
                val calls = runCatching { org.json.JSONArray(message.toolCallsJson) }.getOrNull()
                if (calls == null) {
                    if (strict) require(false) { "工具调用 ID 缺失或重复" }
                } else {
                    for (i in 0 until calls.length()) {
                        val id = calls.optJSONObject(i)?.optString("id").orEmpty()
                        if (strict) {
                            require(id.isNotBlank() && pending.add(id)) { "工具调用 ID 缺失或重复" }
                        } else if (id.isNotBlank()) {
                            pending.add(id)
                        }
                    }
                }
            }
            if (message.role == "tool") {
                val id = message.toolCallId
                if (strict) {
                    require(id.isNotBlank() && pending.remove(id)) { "工具结果缺少对应调用" }
                } else if (id.isNotBlank()) {
                    pending.remove(id)
                } else {
                    pending.lastOrNull()?.let(pending::remove)
                }
            }
            if (pending.isEmpty()) cuts += index + 1
        }
        return cuts
    }

    /**
     * Shared token-tail selection. Every cut remains at a complete tool-batch boundary.
     *
     * [billedTokens]/[localTokens] describe the same request in provider-billed and
     * local-estimate units. Relays that bill inline images as base64 text can make the
     * bill 5-10x the local estimate; the 16% budget is then converted to local units so
     * the retained tail targets 16% of the *billed* window instead of swallowing it all.
     */
    fun selectStart(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int,
        overflow: Boolean = false,
        billedTokens: Int? = null,
        localTokens: Int? = null,
    ): Int {
        if (contextWindow <= 0) return 0
        val budget = localRetentionBudget(continuationRetentionBudget(contextWindow, overflow), billedTokens, localTokens)
        val cut = continuationStart(history, budget)
        if (cut > 0 || overflow) return if (cut > 0) cut else continuationStart(history, 1)
        // The whole local history fits in the verbatim budget, so the pressure comes from
        // request overhead. Collapsing to the newest unit here discarded ~98% of a live
        // run; keep the newer half instead. Confirmed overflow still uses the 1-token path.
        val total = retainedTokens(history, 0)
        val halfCut = continuationStart(history, (total / 2).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
        return if (halfCut > 0) halfCut else continuationStart(history, 1)
    }

    /** Scale a billed-unit budget into local-estimate units; never enlarges it. */
    internal fun localRetentionBudget(budget: Int, billedTokens: Int?, localTokens: Int?): Int {
        val billed = billedTokens?.takeIf { it > 0 } ?: return budget
        val local = localTokens?.takeIf { it > 0 } ?: return budget
        if (billed <= local) return budget
        return (budget.toLong() * local / billed).coerceIn(1L, budget.toLong()).toInt()
    }

    /** Local-estimate tokens of history[start..]; used for diagnostics only. */
    fun retainedTokens(history: List<AgentModelClient.ConversationMessage>, start: Int): Long =
        history.drop(start.coerceIn(0, history.size)).sumOf { AgentContextBudget.countMessage(it).toLong() }

    /**
     * Upper bound of the verbatim recent tail (16% of the window, DeepSeek harness
     * retainRatio=0.16). It is a ceiling, not a guarantee: when the history is shorter
     * than this, selectStart keeps the newer half. Callers with a calibrated bill
     * convert it to local units through [localRetentionBudget]. Overflow recovery may shrink this to a
     * single token so the newest complete tool batch can still be selected.
     */
    internal fun continuationRetentionBudget(contextWindow: Int, overflow: Boolean = false): Int {
        if (overflow) return 1
        if (contextWindow <= 0) return 1
        return maxOf(1, (contextWindow.toLong() * 16 / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Never summarize the newest complete unit; walk backward to the next balanced cut. */
    fun continuationStart(history: List<AgentModelClient.ConversationMessage>, retainTokens: Int): Int {
        if (history.size < 2) return 0
        var tokens = 0L
        var start = history.lastIndex
        for (i in history.indices.reversed()) {
            tokens += AgentContextBudget.countMessage(history[i])
            start = i
            if (tokens >= retainTokens.coerceAtLeast(1)) break
        }
        return availableCuts(history).lastOrNull { it <= start && it < history.size } ?: 0
    }

    fun outputReserve(config: AgentModelClient.ModelConfig): Int {
        val body = org.json.JSONObject(config.extraBodyJson.ifBlank { "{}" })
        RequestBodyMerge.mergeCustomBody(body, config.customBody)
        return listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
            .mapNotNull { key -> body.optLong(key, -1).takeIf { it > 0 } }
            .maxOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 4096
    }

    /**
     * Largest prompt still allowed to leave for [window].
     *
     * [calibrated] states whether the caller's token count came from a cloud receipt.
     * When it did not, the only basis is the local character heuristic, which
     * under-counts dense code and mixed CJK; a run configured for 200k then really
     * sends ~220k. The extra reserve absorbs that error, and disappears as soon as a
     * real receipt calibrates the budget.
     */
    fun inputLimit(window: Int, outputReserve: Int = 4096, calibrated: Boolean = true): Int {
        val safety = maxOf(512, window / 20)
        val heuristic = if (calibrated) 0L else window.toLong() * UNCALIBRATED_MARGIN_PERCENT / 100
        return (window.toLong() - outputReserve - safety - heuristic)
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Headroom for local under-counting while no cloud receipt exists yet. */
    private const val UNCALIBRATED_MARGIN_PERCENT = 12
}
