package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow

/** The exact existing policy, shared by budget evaluation and its input boundary. */
internal fun contextSendBudgetRequired(measuredContextTokens: Int?, autoCompressEnabled: Boolean): Boolean =
    if (measuredContextTokens != null && !autoCompressEnabled) true else false

/** History is used ONLY by the manual send budget; never retain a stale blocking snapshot. */
internal fun historyForContextSendBudget(
    history: List<AgentModelClient.ConversationMessage>,
    measuredContextTokens: Int?,
    autoCompressEnabled: Boolean,
): List<AgentModelClient.ConversationMessage> =
    if (contextSendBudgetRequired(measuredContextTokens, autoCompressEnabled)) history else emptyList()

/** Keep the existing send policy, but do not compose unused local budget calculations. */
@Composable
internal fun contextSendBlocked(
    measuredContextTokens: Int?,
    autoCompressEnabled: Boolean,
    budget: @Composable () -> AgentContextUsageUi,
): Boolean = if (contextSendBudgetRequired(measuredContextTokens, autoCompressEnabled)) {
    shouldBlockSendForContextWindow(autoCompressEnabled, budget())
} else {
    false
}
