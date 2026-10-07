package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot

/** Timestamp only the final visible reply. Replays retain the recorded completion instant. */
internal fun stampCompletedReply(
    messages: List<AgentChatMessageUi>,
    runId: String,
    generatedAtMillis: Long?,
): List<AgentChatMessageUi> {
    if (generatedAtMillis == null || generatedAtMillis <= 0L) return messages
    val index = AgentRunMessageProjector.resultTargetIndex(runId, messages)
    val target = messages.getOrNull(index) as? AgentMessageUi ?: return messages
    if (target.content.isBlank() || target.generatedAtMillis != null) return messages
    return messages.incrementalSnapshot().replacing(
        index, target.copy(generatedAtMillis = generatedAtMillis),
    ).withoutReplacementHint()
}
