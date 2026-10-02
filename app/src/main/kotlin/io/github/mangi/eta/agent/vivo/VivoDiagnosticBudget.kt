package io.github.mangi.eta.agent.vivo

/** Bounded counters: saturated stages cannot consume another stage's allowance. */
internal class VivoDiagnosticBudget(
    stageCount: Int,
    private val totalLimit: Int,
    private val perStageLimit: Int,
) {
    init { require(stageCount > 0 && totalLimit > 0 && perStageLimit > 0) }
    private val counts = IntArray(stageCount)
    private var total = 0

    @Synchronized fun claim(stage: Int): Int? {
        if (stage !in counts.indices || total >= totalLimit || counts[stage] >= perStageLimit) return null
        counts[stage]++
        total++
        return total
    }
}
