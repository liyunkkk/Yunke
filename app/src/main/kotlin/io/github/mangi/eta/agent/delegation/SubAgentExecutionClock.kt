package io.github.mangi.eta.agent.delegation

/** Queue time is never charged. Text execution time is advisory; compaction remains terminal. */
internal class SubAgentExecutionClock(
    private val executionMs: Long,
    private val compressionMs: Long,
    private val softExecution: Boolean = false,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last = now()
    private var execution = 0L
    private var compression = 0L
    private var compacting = false
    private var executionPaused = false
    private var warned = false
    @Synchronized fun pauseExecution() { tick(); executionPaused = true }
    @Synchronized fun setCompacting(value: Boolean) { tick(); compacting = value }
    @Synchronized fun softWarningDue(): Boolean {
        tick()
        if (!softExecution || warned || execution < executionMs) return false
        warned = true
        return true
    }
    @Synchronized fun expired(): String? {
        tick()
        return when {
            compression >= compressionMs -> "SUB_AGENT_COMPACTION_TIMEOUT"
            !softExecution && !executionPaused && execution >= executionMs -> "SUB_AGENT_TIMEOUT"
            else -> null
        }
    }
    @Synchronized fun renewExecution() { tick(); execution = 0; warned = false; executionPaused = false }
    @Synchronized fun diagnostics(): Map<String, Number> { tick(); return mapOf("execution_ms" to execution, "compression_ms" to compression) }
    private fun tick() {
        val current = now()
        val elapsed = (current - last).coerceAtLeast(0)
        if (compacting) compression += elapsed else if (!executionPaused) execution += elapsed
        last = current
    }
}
