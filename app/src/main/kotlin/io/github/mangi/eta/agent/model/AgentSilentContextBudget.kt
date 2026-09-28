package io.github.mangi.eta.agent.model

/** Decision-only calibration. Never emitted as a cloud bill or ordinary ring usage. */
internal class AgentSilentContextBudget {
    private var requestLocal: Int = 0
    private var measuredInput: Int? = null
    private var measuredLocal: Int = 0

    /**
     * Ratio between what the provider billed and what the local heuristic counted for
     * the same request, learned from accepted receipts and deliberately kept across
     * [contextReplaced].
     *
     * The local count is a character heuristic (CJK 1.5/char, latin 0.25/char). On this
     * device's own traffic it measured billed/local ≈ 0.83..0.87 for ordinary rounds, so
     * it usually *over*-counts; but the ratio is content-dependent and punctuation-dense
     * code tokenizes far worse than 4 chars/token. When the ratio shows the heuristic
     * under-counting, an uncalibrated request configured for 200k can really leave at
     * ~220k, because the send limit compares against that under-count.
     *
     * Only values above 1 are retained, and only for the send limit: correcting a
     * known under-count is safe, while trusting an over-count would shrink the window
     * for no reason.
     */
    private var underCountScale: Double = 1.0

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
        learnScale(inputTokens)
    }

    private fun learnScale(inputTokens: Int) {
        if (requestLocal < MIN_SCALE_BASIS) return
        val observed = inputTokens.toDouble() / requestLocal
        if (observed <= 1.0) return
        underCountScale = maxOf(underCountScale, observed.coerceAtMost(MAX_SCALE))
    }

    private fun isPlausible(inputTokens: Int, contextWindow: Int?): Boolean {
        val window = contextWindow?.takeIf { it > 0 }
        if (!AgentBilledPromptPlausibility.fitsWindow(inputTokens, window)) return false
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
     * Same value as [tokens], but with a known under-count corrected.
     *
     * Used only by the hard send limit. While a cloud anchor exists the anchor already
     * carries the provider's own number, so this returns [tokens] unchanged; the
     * correction matters exactly in the uncalibrated window right after a context
     * replacement, which is where an under-counted prompt used to slip out above the
     * configured limit. With no receipt ever observed the scale stays 1.0 and the
     * behaviour is bit-for-bit the previous one.
     */
    fun sendLimitTokens(currentLocal: Int): Int {
        val base = tokens(currentLocal)
        if (measuredInput != null || underCountScale <= 1.0) return base
        return (base * underCountScale).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
    }

    /**
     * False while the only basis is the local character heuristic. Callers that must
     * not act on a purely local estimate check this first.
     */
    fun isCalibrated(): Boolean = measuredInput != null

    fun contextReplaced() {
        measuredInput = null
        measuredLocal = 0
        requestLocal = 0
        // underCountScale is a property of the model's tokenizer, not of this context.
    }

    private companion object {
        /** Absolute slack for cache accounting and per-round request scaffolding. */
        const val GROWTH_SLACK_TOKENS = 8_192

        /** Extra slack proportional to the window, for large-context models. */
        const val GROWTH_SLACK_WINDOW_PERCENT = 5

        /** Below this a ratio is dominated by fixed per-request overhead. */
        const val MIN_SCALE_BASIS = 2_000

        /** Never let one odd receipt inflate the correction without bound. */
        const val MAX_SCALE = 2.0
    }
}
