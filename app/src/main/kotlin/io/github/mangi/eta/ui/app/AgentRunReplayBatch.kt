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
 * Adjacent deltas for the same block are coalesced just as on the live event path;
 * every other event is a hard boundary. No events or message payloads are retained
 * after the call, and a failed replay cannot leave live updates in deferred mode.
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
            val coalescer = AgentRunEventCoalescer()
            events.forEach { event ->
                if (event is AgentEvent.AssistantBlockDelta) {
                    coalescer.append(runId, event)?.let(apply)
                } else {
                    coalescer.flush(runId)?.let(apply)
                    apply(event)
                }
            }
            coalescer.flush(runId)?.let(apply)
        } finally {
            activeRunId = null
        }
        // Normalization is enabled again. The caller publishes the ordered state
        // and refreshes conversation summaries once, in the same Compose snapshot.
        finish()
    }
}
