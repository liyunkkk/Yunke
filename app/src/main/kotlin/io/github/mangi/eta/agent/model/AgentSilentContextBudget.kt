package io.github.mangi.eta.agent.model

/** Decision-only calibration. Never emitted as a cloud bill or ordinary ring usage. */
internal class AgentSilentContextBudget {
    private var requestLocal: Int = 0
    private var measuredInput: Int? = null
    private var measuredLocal: Int = 0

    fun requestStarted(localTokens: Int) { requestLocal = localTokens.coerceAtLeast(0) }

    fun measured(inputTokens: Int?) {
        if (inputTokens == null || inputTokens <= 0) return
        measuredInput = inputTokens
        measuredLocal = requestLocal
    }

    fun tokens(currentLocal: Int): Int {
        val input = measuredInput ?: return currentLocal.coerceAtLeast(0)
        return (input.toLong() + currentLocal - measuredLocal)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun contextReplaced() {
        measuredInput = null
        measuredLocal = 0
        requestLocal = 0
    }
}
