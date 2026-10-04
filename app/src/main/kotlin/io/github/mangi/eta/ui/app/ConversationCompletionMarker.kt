package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

internal object ConversationCompletionMarker {
    fun shouldMark(
        result: AgentRuntimeWire.RunResult,
        wasStopped: Boolean = false,
        isSelected: Boolean,
        alreadyApplied: Boolean = false,
    ): Boolean = !wasStopped && !isSelected && !alreadyApplied &&
        result.ok && result.error.isNullOrBlank() &&
        (result.content.isNotBlank() || result.virtualDeliveryCompleted ||
            result.transcript.any { it.role == "assistant" && it.content.isNotBlank() })
}

