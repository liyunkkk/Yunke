package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/**
 * A stream's opening usage (`message_start`) is not a measurement of the request yet.
 *
 * Both gateway shapes observed on this device send it with `output_tokens = 1` and no cache
 * subset. One reports only the uncached remainder before the cache is known: a request that
 * opened with `input 28225, cache 0` was billed `input 240479, cache 206810` at its end, and
 * three consecutive turns of one conversation opened at 27346/28119/28225 while ending at
 * 231794/228299/240479. Feeding the opening value to the ring made the displayed occupancy
 * drop from ~200k to 28k and jump back to 240k inside a single turn.
 *
 * The value is deferred rather than discarded: it is still real money, and a stream that
 * aborted before its final receipt has nothing else to report. It is emitted unchanged as
 * soon as the stream ends without a later receipt in the same request.
 *
 * This is not a plausibility check. A relay that bills a genuine but oversized prompt keeps
 * working exactly as before ([AgentBilledPromptPlausibility]); the final receipt of this very
 * conversation is 240479 on a 500000 window and stays accepted.
 */
internal object AgentInitialUsageReceipt {

    /**
     * True for the pre-measurement receipt of a stream: one output token, no cache read and
     * no cache write. Either cache field being present already makes it a measurement, and a
     * receipt without an input total carries nothing worth deferring.
     */
    fun isOpening(usage: AgentTokenUsage): Boolean =
        usage.outputTokens == 1 &&
            (usage.cachedTokens ?: 0) <= 0 &&
            (usage.cacheCreationTokens ?: 0) <= 0 &&
            (usage.inputTokens ?: 0) > 0

    /**
     * Keeps the newest non-null field of each name, so a final receipt that omits a field the
     * opening one carried still merges into one complete bill instead of dropping it.
     */
    fun merge(latest: AgentTokenUsage, incoming: AgentTokenUsage): AgentTokenUsage = AgentTokenUsage(
        contextTokens = incoming.contextTokens ?: latest.contextTokens,
        inputTokens = incoming.inputTokens ?: latest.inputTokens,
        outputTokens = incoming.outputTokens ?: latest.outputTokens,
        reasoningTokens = incoming.reasoningTokens ?: latest.reasoningTokens,
        cachedTokens = incoming.cachedTokens ?: latest.cachedTokens,
        cacheCreationTokens = incoming.cacheCreationTokens ?: latest.cacheCreationTokens,
    )
}
