package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi

/** Timestamp only the final visible reply. Replays retain the recorded completion instant. */
internal fun stampCompletedReply(
    messages: List<AgentChatMessageUi>,
    runId: String,
    generatedAtMillis: Long?,
): List<AgentChatMessageUi> {
    if (generatedAtMillis == null || generatedAtMillis <= 0L) return messages
    val index = AgentRunMessageProjector.resultTargetIndex(runId, messages)
    return messages.mapIndexed { i, message ->
        if (i == index && message is AgentMessageUi && message.content.isNotBlank()) {
            message.copy(generatedAtMillis = message.generatedAtMillis ?: generatedAtMillis)
        } else message
    }
}
