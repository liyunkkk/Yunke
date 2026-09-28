package io.github.mangi.eta.agent.model

/** Decision-only calibration. Never emitted as a cloud bill or ordinary ring usage. */
internal class AgentSilentContextBudget {
    private var requestLocal: Int = 0
    private var measuredInput: Int? = null
    private var measuredLocal: Int = 0

    fun requestStarted(localTokens: Int) { requestLocal = localTokens.coerceAtLeast(0) }

    /**
     * Anchors on a cloud measurement, but only when it can actually describe the
     * prompt that was just sent.
     *
     * Some gateways sum a retried or multi-leg request into one usage object: on the
     * wire we saw `input_tokens = 784267` for a request whose local boundary was
     * ~34000 on a 500000 window, and a later round billing 267917 while the local
     * transcript had grown by ~1100. Anchoring on such a number makes every
     * subsequent decision believe the context is nearly full, which is how
     * auto-compaction fired far below its threshold and then compacted again
     * immediately without occupancy ever really being that high.
     *
     * Rejecting the outlier keeps the previous anchor (or the local boundary). That is
     * conservative in the safe direction: a genuine overflow still surfaces as a
     * provider CONTEXT_WINDOW_EXCEEDED failure rather than as a silent wrong anchor.
     */
    fun measured(inputTokens: Int?, contextWindow: Int? = null) {
        if (inputTokens == null || inputTokens <= 0) return
        if (!isPlausible(inputTokens, contextWindow)) return
        measuredInput = inputTokens
        measuredLocal = requestLocal
    }

    private fun isPlausible(inputTokens: Int, contextWindow: Int?): Boolean {
        val window = contextWindow?.takeIf { it > 0 }
        if (window != null && inputTokens.toLong() * 100 > window.toLong() * MAX_WINDOW_PERCENT) return false
        val local = requestLocal.takeIf { it >= MIN_LOCAL_BASIS } ?: return true
        return inputTokens.toLong() <= local.toLong() * MAX_LOCAL_GROWTH_MULTIPLE
    }

    fun tokens(currentLocal: Int): Int {
        val input = measuredInput ?: return currentLocal.coerceAtLeast(0)
        return (input.toLong() + currentLocal - measuredLocal)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * False while the only basis is the local character heuristic. Send limits must
     * widen their reserve in that case: the heuristic under-counts dense code and
     * mixed CJK, which is how a run configured for 200k can still leave with ~220k.
     */
    fun isCalibrated(): Boolean = measuredInput != null

    fun contextReplaced() {
        measuredInput = null
        measuredLocal = 0
        requestLocal = 0
    }

    private companion object {
        /** A prompt may exceed its window, but not by an unbounded factor. */
        const val MAX_WINDOW_PERCENT = 130

        /** One round cannot bill this multiple of what was locally counted for it. */
        const val MAX_LOCAL_GROWTH_MULTIPLE = 8

        /** Below this the ratio test is noise, so only the window test applies. */
        const val MIN_LOCAL_BASIS = 2_000
    }
}
