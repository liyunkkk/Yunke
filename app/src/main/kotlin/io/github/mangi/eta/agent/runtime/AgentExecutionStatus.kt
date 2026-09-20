package io.github.mangi.eta.agent.runtime

internal enum class AgentExecutionPhase {
    IDLE,
    PREPARING,
    THINKING,
    TOOL_EXECUTING,
    GENERATING,
    TRANSLATING,
    GENERIC_RUNNING,
}

internal data class AgentExecutionState(
    val phase: AgentExecutionPhase = AgentExecutionPhase.IDLE,
    val title: String? = null,
    val subtitle: String? = null,
    val detail: String? = null,
    val startedAtElapsedRealtime: Long = 0L,
    val showChronometer: Boolean = false,
    val expandedSnippet: String? = null,
)
