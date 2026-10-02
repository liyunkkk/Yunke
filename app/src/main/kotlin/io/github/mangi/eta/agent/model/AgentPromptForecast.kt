package io.github.mangi.eta.agent.model

/** Next-request prediction, NOT a receipt or a send-limit policy.
 *
 * A cloud input includes cache reads. Calibrate the offset in one local-count basis:
 * I + (L - La), never input minus cache, never output plus input. An anchor is only
 * meaningful for the same session, model and history generation. Callers invalidate
 * it when replacing history; append-only growth can reuse it, including across runs.
 */
internal class AgentPromptForecast(private var binding: Binding) {
    data class Binding(val sessionId: String, val modelKey: String, val generation: Long = 0)
    data class Anchor(val binding: Binding, val inputTokens: Int, val localTokens: Int)

    private var anchor: Anchor? = null
    private var requestLocal: Int = 0

    fun inherit(value: Anchor?) {
        anchor = value?.takeIf { it.binding == binding && it.inputTokens > 0 && it.localTokens >= 0 }
    }

    /** UI has already validated session/model/history and folded the raw DTO delta.
     * Rebase that inherited prediction onto this run's request-local counting basis.
     * It remains a prediction: this class has no API for automatic compression.
     */
    fun seed(localTokens: Int, calibratedInputTokens: Int?, contextWindow: Int?) {
        inherit(calibratedInputTokens?.takeIf {
            it > 0 && AgentBilledPromptPlausibility.fitsWindow(it, contextWindow)
        }?.let { Anchor(binding, it, localTokens.coerceAtLeast(0)) })
    }

    fun requestStarted(localTokens: Int) { requestLocal = localTokens.coerceAtLeast(0) }

    fun measured(inputTokens: Int?, cachedTokens: Int?, contextWindow: Int?) {
        if (inputTokens == null || inputTokens <= 0 ||
            !AgentBilledPromptPlausibility.fitsWindow(inputTokens, contextWindow) ||
            AgentBilledPromptPlausibility.isInflatedCacheRead(inputTokens, cachedTokens, contextWindow)) return
        anchor = Anchor(binding, inputTokens, requestLocal)
    }

    fun tokens(localTokens: Int, uncalibratedTokens: Int = localTokens): Int =
        anchor?.let { project(it.inputTokens, localTokens.toLong(), it.localTokens.toLong()) }
            ?: uncalibratedTokens.coerceAtLeast(0)

    fun snapshot(): Anchor? = anchor

    fun contextReplaced() {
        binding = binding.copy(generation = binding.generation + 1)
        anchor = null
        requestLocal = 0
    }

    companion object {
        fun project(inputTokens: Int, localTokens: Long, anchorLocalTokens: Long): Int =
            (inputTokens.toLong() + localTokens - anchorLocalTokens)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
