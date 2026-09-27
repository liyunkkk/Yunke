package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages

/**
 * Synchronous UI-dispatcher replay boundary. A Compose mutable snapshot batches
 * publication, not computation: sorting a whole conversation for every restored
 * event still blocks input and allocates temporary histories. Defer that derived
 * work until finish, while applying metadata/usage/compaction events in order.
 *
 * Keep every recorded event unchanged: thinking deduplication inspects each
 * incoming delta, so concatenating even adjacent deltas can change its meaning.
 * No events or payloads are retained, and failure cannot leave deferral active.
 */
internal class AgentRunReplayBatch(
    private val order: (String, List<AgentChatMessageUi>) -> List<AgentChatMessageUi> =
        ::normalizeTerminalRunMessages,
) {
    private var activeRunId: String? = null
    val isActive: Boolean get() = activeRunId != null

    fun normalize(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        if (activeRunId == runId) messages else order(runId, messages)

    fun replay(
        runId: String,
        events: List<AgentEvent>,
        reset: () -> Unit,
        apply: (AgentEvent) -> Unit,
        finish: () -> Unit,
    ) {
        check(!isActive) { "Run replay must not be nested" }
        activeRunId = runId
        try {
            reset()
            events.forEach(apply)
        } finally {
            activeRunId = null
        }
        // Normalization is enabled again. The caller publishes the ordered state
        // and refreshes conversation summaries once, in the same Compose snapshot.
        finish()
    }
}
