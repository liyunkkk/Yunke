package io.github.mangi.eta.ui.model

/**
 * A cloud prompt receipt is not bounded by the local tokenizer's growth estimate.
 * Images, tool payloads and provider-specific serialization can change the two counts
 * differently. Rejecting a measured increase on that basis pins the ring to an old bill
 * and keeps comparing every later receipt against the same stale calibration.
 *
 * Keep only the existing shared absolute-window policy here. Run ownership and
 * context-replacement validity are checked by the caller; local calibration belongs
 * to the decision budget, never to accepting a new measured prompt. The one exception,
 * a cache_read larger than the whole request, is
 * [io.github.mangi.eta.agent.model.AgentBilledPromptPlausibility.isInflatedCacheRead].
 */
internal object CloudReceiptPlausibility {

    fun fitsWindow(tokens: Int, contextWindow: Int?): Boolean =
        io.github.mangi.eta.agent.model.AgentBilledPromptPlausibility.fitsWindow(tokens, contextWindow)

    fun isOccupancy(tokens: Int?, contextWindow: Int?): Boolean {
        val value = tokens?.takeIf { it > 0 } ?: return false
        return fitsWindow(value, contextWindow)
    }
}
