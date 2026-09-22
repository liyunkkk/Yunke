package io.github.mangi.eta.agent.delegation

/** Execution and compression have separate cumulative budgets, using a monotonic clock. */
internal class SubAgentExecutionClock(
    private val executionMs: Long,
    private val compressionMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last = now()
    private var execution = 0L
    private var compression = 0L
    private var compacting = false
    private var executionPaused = false
    @Synchronized fun pauseExecution() { tick(); executionPaused = true }
    @Synchronized fun setCompacting(value: Boolean) { tick(); compacting = value }
    @Synchronized fun expired(): String? {
        tick()
        return when {
            compression >= compressionMs -> "SUB_AGENT_COMPACTION_TIMEOUT"
            !executionPaused && execution >= executionMs -> "SUB_AGENT_TIMEOUT"
            else -> null
        }
    }
    /** Renew only execution time. Waiting is not charged; cumulative compression budget survives. */
    @Synchronized fun renewExecution() { tick(); execution = 0; executionPaused = false }
    @Synchronized fun diagnostics(): Map<String, Number> { tick(); return mapOf("execution_ms" to execution, "compression_ms" to compression) }
    private fun tick() {
        val current = now()
        val elapsed = (current - last).coerceAtLeast(0)
        if (compacting) compression += elapsed else if (!executionPaused) execution += elapsed
        last = current
    }
}
