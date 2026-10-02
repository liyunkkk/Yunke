package io.github.mangi.eta.agent.vivo

/** Test-only isolation; production never exposes a way to refill its log allowance. */
internal class VivoDiagnosticTestScope {
    private val budget = VivoBridgeDiagnostics::class.java.getDeclaredField("budget").apply {
        isAccessible = true
    }.get(VivoBridgeDiagnostics) as VivoDiagnosticBudget
    private val counts = VivoDiagnosticBudget::class.java.getDeclaredField("counts").apply {
        isAccessible = true
    }.get(budget) as IntArray
    private val totalField = VivoDiagnosticBudget::class.java.getDeclaredField("total").apply {
        isAccessible = true
    }
    private val previous = synchronized(budget) {
        val saved = counts.copyOf() to totalField.getInt(budget)
        counts.fill(0)
        totalField.setInt(budget, 0)
        saved
    }

    fun reset() = synchronized(budget) {
        counts.fill(0)
        totalField.setInt(budget, 0)
    }

    fun restore() = synchronized(budget) {
        previous.first.copyInto(counts)
        totalField.setInt(budget, previous.second)
    }
}
