package io.github.mangi.eta.ui.model

/**
 * A provider bill and the window occupancy are two different quantities, and only
 * the second one may drive the ring or the compaction decision.
 *
 * Observed on the wire (conv-a20645fb, run 3de233e2):
 *  - round 1 reported `input_tokens = 784267` against a 500000 window while the
 *    request itself succeeded, so that number cannot be what occupied the window;
 *  - round 14 -> 15 jumped 38880 -> 267917 while the locally counted transcript
 *    moved 21079 -> 22194, i.e. +229037 billed against +1115 real growth.
 *
 * Both shapes come from gateways that aggregate a retried or multi-leg request into
 * one usage object. Accepting them made occupancy "suddenly max out" even though the
 * cloud context never held that much, and it also tripped auto-compaction early:
 * the trigger reads this value, so a bogus 267917 on a 200000 window crosses the 80%
 * line while the UI ring still shows a much smaller, plausible number.
 *
 * The guard is deliberately one-directional. It never invents a value and never
 * lowers a plausible one; it only refuses a receipt that cannot describe this
 * window, so the caller keeps its previous measurement or falls back to the local
 * estimate. A receipt that merely exceeds the window is still accepted, because a
 * genuine overflow must stay visible.
 */
internal object CloudReceiptPlausibility {

    /**
     * A prompt may legitimately exceed a configured window (the provider decides),
     * but not by an unbounded factor. Past this multiple the number is an aggregate,
     * not an occupancy: a real request that far over the limit is rejected upstream
     * with CONTEXT_WINDOW_EXCEEDED instead of returning a bill.
     */
    private const val MAX_WINDOW_PERCENT = 130

    /** A single round cannot bill this multiple of the locally counted transcript. */
    private const val MAX_LOCAL_GROWTH_MULTIPLE = 8

    /** Below this the ratio test is meaningless, so only the window test applies. */
    private const val MIN_LOCAL_BASIS = 2_000

    /** True when [tokens] can describe the prompt that occupied [contextWindow]. */
    fun fitsWindow(tokens: Int, contextWindow: Int?): Boolean {
        if (tokens <= 0) return false
        val window = contextWindow?.takeIf { it > 0 } ?: return true
        return tokens.toLong() * 100 <= window.toLong() * MAX_WINDOW_PERCENT
    }

    /**
     * True when [tokens] is consistent with the locally counted transcript for the
     * same request. [localTokens] is the app-side count of exactly what was sent, so
     * a bill many times larger than it describes more than this one prompt.
     */
    fun fitsLocalBasis(tokens: Int, localTokens: Int?): Boolean {
        if (tokens <= 0) return false
        val local = localTokens?.takeIf { it >= MIN_LOCAL_BASIS } ?: return true
        return tokens.toLong() <= local.toLong() * MAX_LOCAL_GROWTH_MULTIPLE
    }

    /** Accepts a receipt only when both independent sanity checks hold. */
    fun isOccupancy(tokens: Int?, contextWindow: Int?, localTokens: Int? = null): Boolean {
        val value = tokens?.takeIf { it > 0 } ?: return false
        return fitsWindow(value, contextWindow) && fitsLocalBasis(value, localTokens)
    }
}
