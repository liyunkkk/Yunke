package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

private fun finishAccounting(block: suspend () -> Unit) {
    // Finish received accounting even if the network/agent thread was interrupted.
    val interrupted = Thread.interrupted()
    try { runBlocking(Dispatchers.IO) { block() } }
    finally { if (interrupted) Thread.currentThread().interrupt() }
}

private val DEFAULT_USAGE_RECORD: (ModelUsageDelta) -> Unit = { delta ->
    finishAccounting { UsageStatsRepository.recordModelUsage(delta) }
}

/** Accounting belongs to a provider request, not to a UI/display round or the selected model. */
internal class UsageRecordingProvider(
    private val delegate: AgentProviderClient,
    private val finish: (() -> Unit)? = null,
    private val record: (ModelUsageDelta) -> Unit = DEFAULT_USAGE_RECORD,
) : AgentProviderClient by delegate {
    override fun complete(request: ProviderRequest, runController: AgentRunController,
                          onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        var latest: AgentTokenUsage? = null
        var saved: AgentTokenUsage? = null
        fun persist() {
            val usage = latest ?: return
            if (usage == saved || (usage.inputTokens == null && usage.outputTokens == null && usage.cachedTokens == null && usage.cacheCreationTokens == null)) return
            val config = request.config
            // An implausible prompt total would be summed into lifetime statistics forever,
            // and the stats page would then contradict the ring for the same traffic.
            // Observed: 784267 billed against a 500000 window on a request that succeeded.
            // Output and cache are still recorded; only the impossible prompt is dropped.
            val billedInput = usage.inputTokens?.takeIf { input ->
                AgentBilledPromptPlausibility.fitsWindow(input, config.contextWindow)
            }
            // Statistics failure must not suppress a completed model response or its error.
            runCatching {
                record(ModelUsageDelta(
                    providerId = config.providerId, providerName = config.providerName,
                    modelId = config.model, modelDisplayName = config.modelDisplayName.ifBlank { config.model },
                    inputTokens = (billedInput ?: 0).toLong(),
                    outputTokens = (usage.outputTokens ?: 0).toLong(),
                    cachedTokens = (if (billedInput == null) 0 else usage.cachedTokens ?: 0).toLong(),
                    cacheCreationTokens = (if (billedInput == null) 0 else usage.cacheCreationTokens ?: 0).toLong(),
                    conversationId = request.usageConversationId, requestId = requestId, atMillis = startedAt,
                ))
            }.onSuccess { saved = usage }
        }
        try {
            return delegate.complete(request, runController) { event ->
                if (event is ProviderEvent.Usage) {
                    request.toolDiagnosticAttempt?.usage(event.usage)
                    val previous = latest
                    latest = event.usage.copy(
                        inputTokens = event.usage.inputTokens ?: previous?.inputTokens,
                        outputTokens = event.usage.outputTokens ?: previous?.outputTokens,
                        cachedTokens = event.usage.cachedTokens ?: previous?.cachedTokens,
                        cacheCreationTokens = event.usage.cacheCreationTokens ?: previous?.cacheCreationTokens,
                    )
                    try { onEvent(event) } finally { persist() }
                } else {
                    onEvent(event)
                }
            }
        } finally {
            persist()
            // Timed/count-bounded commits cover a long-running request; completion (including
            // cancellation/error/consumer failure) closes its final dirty interval synchronously.
            // As before, statistics failure must not replace the model response/error.
            runCatching {
                if (finish != null) finish.invoke()
                else if (record === DEFAULT_USAGE_RECORD) finishAccounting { UsageStatsRepository.flushModelUsage() }
            }
        }
    }
}
