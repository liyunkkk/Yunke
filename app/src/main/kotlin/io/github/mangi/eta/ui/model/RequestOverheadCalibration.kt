package io.github.mangi.eta.ui.model

import kotlinx.serialization.Serializable

/** Additive correction in legacy request-overhead units, independent of conversation receipts. */
internal object RequestOverheadCalibration {
    // Long histories contain tokenizer error as well as fixed overhead error; never learn from them.
    const val MAX_HISTORY_TO_OVERHEAD_RATIO = 0.25
    private const val MAX_OFFSET_TO_OVERHEAD_RATIO = 2L

    @Serializable
    data class Sample(
        val offsetTokens: Int,
        val samples: Int,
        val measuredOverheadTokens: Int,
    ) {
        fun apply(currentOverheadTokens: Int): Int = applyOffset(currentOverheadTokens, offsetTokens)
    }

    fun learn(
        previous: Sample?,
        cloudInput: Int,
        requestHistoryTokens: Int,
        requestOverheadTokens: Int,
        inflatedCache: Boolean = false,
    ): Sample? {
        if (inflatedCache || cloudInput <= 0 || requestHistoryTokens < 0 || requestOverheadTokens < 0) return null
        if (requestHistoryTokens > requestOverheadTokens.toDouble() * MAX_HISTORY_TO_OVERHEAD_RATIO) return null
        val offset = cloudInput.toLong() - requestHistoryTokens - requestOverheadTokens
        // Only correct underestimation. Negative residuals can come from provider normalization;
        // do not let those shrink an already conservative local estimate.
        if (offset < 0L || offset > maxOf(requestOverheadTokens, 1).toLong() * MAX_OFFSET_TO_OVERHEAD_RATIO) return null
        // Equal-weight smoothing limits transient provider accounting noise without tying the
        // correction to history revision or tool count. The local overhead still follows tool changes.
        val smoothed = previous?.let { (it.offsetTokens.toLong() + offset) / 2L } ?: offset
        return Sample(smoothed.toInt(), ((previous?.samples ?: 0).toLong() + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), requestOverheadTokens)
    }

    fun applyOffset(currentOverheadTokens: Int, offsetTokens: Int): Int =
        (currentOverheadTokens.coerceAtLeast(0).toLong() + offsetTokens)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
