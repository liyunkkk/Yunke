package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * 停止窗口内事件分流的纯判定。
 * 终态事件必须放行，否则 Runtime 停止后 UI 只剩看门狗一条解锁路径。
 */
internal object RunStopEventGate {
    /** True only for events that mean the run itself is over and no further output will arrive. */
    fun isRunTerminal(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.RunFinished, is AgentEvent.RunFailed -> true
        else -> false
    }

    /**
     * Streaming text must stay blocked while stopping: a sealed run that resumes appending deltas
     * would resurrect the transcript the user already asked to end.
     */
    fun isStreamingIncrement(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockDelta,
        is AgentEvent.AssistantBlockStart,
        is AgentEvent.AssistantBlockEnd,
        is AgentEvent.AssistantReceived,
        is AgentEvent.RoundStarted,
        is AgentEvent.ProviderRequestStarted,
        is AgentEvent.ProviderResponseStarted,
        -> true

        else -> false
    }
}
