package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow

/** Keep the existing send policy, but do not compose unused local budget calculations. */
@Composable
internal fun contextSendBlocked(
    measuredContextTokens: Int?,
    autoCompressEnabled: Boolean,
    budget: @Composable () -> AgentContextUsageUi,
): Boolean = if (measuredContextTokens != null && !autoCompressEnabled) {
    shouldBlockSendForContextWindow(autoCompressEnabled, budget())
} else {
    false
}
