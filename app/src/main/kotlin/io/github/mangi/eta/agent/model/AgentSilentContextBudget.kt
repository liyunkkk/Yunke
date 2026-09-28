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
     * wire we saw `input_tokens = 784267` for a request that succeeded on a 500000
     * window, and a later round billing 267917 right after 38880 while the local
     * transcript had grown by ~1100. Anchoring on such a number makes every later
     * decision believe the context is nearly full, which is how auto-compaction fired
     * far below its threshold and then immediately compacted a second time.
     *
     * Only two things are checked, both one-directional refusals:
     *  - the value cannot exceed the window by an unbounded factor;
     *  - its step above the previous anchor cannot far exceed the local growth since
     *    that anchor.
     * The absolute ratio between a bill and the local estimate is deliberately *not*
     * checked: a first bill can legitimately be several times the local count, so that
     * test would reject correct receipts.
     *
     * Rejecting an outlier keeps the previous anchor (or the local boundary), which is
     * conservative in the safe direction: a genuine overflow still surfaces as a
     * provider CONTEXT_WINDOW_EXCEEDED failure rather than as a silently wrong anchor.
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
        val previous = measuredInput?.takeIf { it > 0 } ?: return true
        val billedGrowth = inputTokens.toLong() - previous
        if (billedGrowth <= 0) return true
        val localGrowth = (requestLocal.toLong() - measuredLocal).coerceAtLeast(0L)
        val slack = GROWTH_SLACK_TOKENS.toLong() +
            (window?.toLong() ?: 0L) * GROWTH_SLACK_WINDOW_PERCENT / 100
        return billedGrowth <= localGrowth + slack
    }

    fun tokens(currentLocal: Int): Int {
        val input = measuredInput ?: return currentLocal.coerceAtLeast(0)
        return (input.toLong() + currentLocal - measuredLocal)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * False while the only basis is the local character heuristic, and send limits must
     * widen their reserve in that case: the heuristic under-counts dense code and mixed
     * CJK, which is how a run configured for 200k can still leave with ~220k.
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

        /** Absolute slack for cache accounting and per-round request scaffolding. */
        const val GROWTH_SLACK_TOKENS = 8_192

        /** Extra slack proportional to the window, for large-context models. */
        const val GROWTH_SLACK_WINDOW_PERCENT = 5
    }
}
