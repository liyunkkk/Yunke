package io.github.mangi.eta.agent.model

/**
 * Single definition of "this prompt total could really have occupied that window".
 *
 * A provider bill and the window occupancy are different quantities, but an impossible
 * bill is worthless for either purpose. Some gateways aggregate a retried or multi-leg
 * request into one usage object: on this device's own wire log a request that succeeded
 * on a 500000 window reported `input_tokens = 784267`.
 *
 * Accepting that number had two separate consequences. It became the occupancy anchor,
 * so the ring maxed out and auto-compaction fired far below its threshold; and it was
 * summed into lifetime statistics, where it stays forever and makes the statistics page
 * contradict the ring for exactly the same traffic.
 *
 * The check is one-directional. It never invents or lowers a value, it only refuses one
 * that cannot describe this window. A prompt slightly above its window is still accepted,
 * because a genuine overflow must remain visible rather than being silently hidden.
 */
internal object AgentBilledPromptPlausibility {

    /**
     * A prompt may legitimately exceed a configured window, since the provider decides
     * what it accepts, but not by an unbounded factor: a request that far over the limit
     * is rejected upstream with CONTEXT_WINDOW_EXCEEDED instead of returning a bill.
     */
    const val MAX_WINDOW_PERCENT = 130

    /** True when [tokens] can describe a prompt that occupied [contextWindow]. */
    fun fitsWindow(tokens: Int?, contextWindow: Int?): Boolean {
        val value = tokens?.takeIf { it > 0 } ?: return false
        val window = contextWindow?.takeIf { it > 0 } ?: return true
        return value.toLong() * 100 <= window.toLong() * MAX_WINDOW_PERCENT
    }
}
