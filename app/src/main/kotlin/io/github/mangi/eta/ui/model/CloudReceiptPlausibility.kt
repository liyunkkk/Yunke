package io.github.mangi.eta.ui.model

/**
 * A provider bill and the window occupancy are two different quantities, and only
 * the second one may drive the ring or the compaction decision.
 *
 * Observed on the wire (conv-a20645fb, run 3de233e2):
 *  - round 1 reported `input_tokens = 784267` against a 500000 window while the
 *    request itself succeeded, so that number cannot be what occupied the window;
 *  - round 14 -> 15 jumped 38880 -> 267917 while the locally counted transcript
 *    moved 21079 -> 22194, i.e. +229037 billed against +1115 of real growth.
 *
 * Both shapes come from gateways that aggregate a retried or multi-leg request into
 * one usage object. Accepting them made occupancy "suddenly max out" even though the
 * cloud context never held that much, and it also tripped auto-compaction early:
 * the trigger reads this value, so a bogus 267917 on a 200000 window crosses the 80%
 * line while the ring still shows a much smaller, plausible number.
 *
 * Deliberately *not* tested here: the absolute ratio between a bill and the local
 * estimate. A first bill legitimately runs several times the local count (relays that
 * bill inline images as base64 text are the documented case), so an absolute multiple
 * would reject correct receipts. What a single round cannot do is grow far beyond the
 * growth of the transcript since the previous accepted receipt.
 *
 * Every check only ever refuses a value; it never invents one and never lowers a
 * plausible one. A receipt that merely exceeds the window is still accepted, because
 * a genuine overflow must stay visible.
 */
internal object CloudReceiptPlausibility {

    /** Absolute slack for cache accounting and per-round request scaffolding. */
    private const val GROWTH_SLACK_TOKENS = 8_192

    /** Extra slack proportional to the window, for large-context models. */
    private const val GROWTH_SLACK_WINDOW_PERCENT = 5

    /**
     * True when [tokens] can describe the prompt that occupied [contextWindow]. Shares
     * one definition with accounting, so the ring and the statistics page cannot
     * disagree about whether the very same receipt was possible.
     */
    fun fitsWindow(tokens: Int, contextWindow: Int?): Boolean =
        io.github.mangi.eta.agent.model.AgentBilledPromptPlausibility.fitsWindow(tokens, contextWindow)

    /**
     * True when the step from the previously accepted receipt is consistent with how
     * much the locally counted transcript actually grew.
     *
     * All four references must belong to the same context; callers drop them whenever
     * compaction, pruning or a model change invalidates the baseline.
     */
    fun fitsGrowth(
        tokens: Int,
        previousTokens: Int?,
        localTokens: Int?,
        previousLocalTokens: Int?,
        contextWindow: Int?,
    ): Boolean {
        if (tokens <= 0) return false
        val previous = previousTokens?.takeIf { it > 0 } ?: return true
        val local = localTokens ?: return true
        val previousLocal = previousLocalTokens ?: return true
        val billedGrowth = tokens.toLong() - previous
        if (billedGrowth <= 0) return true
        val localGrowth = (local.toLong() - previousLocal).coerceAtLeast(0L)
        val slack = GROWTH_SLACK_TOKENS.toLong() +
            (contextWindow?.takeIf { it > 0 }?.toLong() ?: 0L) * GROWTH_SLACK_WINDOW_PERCENT / 100
        return billedGrowth <= localGrowth + slack
    }

    /** Accepts a receipt only when both independent sanity checks hold. */
    fun isOccupancy(
        tokens: Int?,
        contextWindow: Int?,
        previousTokens: Int? = null,
        localTokens: Int? = null,
        previousLocalTokens: Int? = null,
    ): Boolean {
        val value = tokens?.takeIf { it > 0 } ?: return false
        return fitsWindow(value, contextWindow) &&
            fitsGrowth(value, previousTokens, localTokens, previousLocalTokens, contextWindow)
    }
}
