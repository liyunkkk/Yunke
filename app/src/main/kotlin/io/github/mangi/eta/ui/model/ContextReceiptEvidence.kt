package io.github.mangi.eta.ui.model

/** Never combine a new request's input with another request's local baseline. */
internal data class ContextReceiptEvidence(
    val requestId: String,
    val input: Int,
    val history: Int?,
    val overhead: Int?,
) {
    companion object {
        fun merge(previous: ContextReceiptEvidence?, requestId: String, input: Int,
            history: Int?, overhead: Int?): ContextReceiptEvidence {
            // Same round alone cannot prove a retried network attempt. A changed input is also
            // rejected for merging; complete fresh baselines always replace the previous pair.
            val same = previous?.takeIf { it.requestId == requestId && it.input == input }
            return ContextReceiptEvidence(requestId, input,
                history?.takeIf { it >= 0 } ?: same?.history,
                overhead?.takeIf { it >= 0 } ?: same?.overhead)
        }
    }
}
