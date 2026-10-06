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

/**
 * Accounting belongs to a provider request, not a display round or the selected model.
 * Each partial is an atomic durable edit. On failure the provider still returns its original
 * result/throws its original error; accountingFailure + payload-free log expose the failure.
 * On an exceptional result, bounded accounting causes are also attached as suppressed errors.
 * saved advances only after a successful commit; retries retain this request's ID, so even
 * an ambiguous commit outcome is replaced, never charged as a new request.
 */
internal class UsageRecordingProvider(
    private val delegate: AgentProviderClient,
    private val finish: (() -> Unit)? = null,
    private val reportFailure: (Throwable) -> Unit = UsageStatsRepository::reportAccountingFailure,
    private val record: (ModelUsageDelta) -> Unit = DEFAULT_USAGE_RECORD,
) : AgentProviderClient by delegate {
    override fun complete(request: ProviderRequest, runController: AgentRunController,
                          onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        var latest: AgentTokenUsage? = null
        var saved: AgentTokenUsage? = null
        var originalFailure: Throwable? = null
        val accountingFailures = mutableListOf<Throwable>()
        fun accountingFailed(failure: Throwable) {
            if (accountingFailures.size < 8 && accountingFailures.none { it === failure }) accountingFailures += failure
            try { reportFailure(failure) } catch (_: Exception) {
                UsageStatsRepository.reportAccountingFailure(failure)
            }
        }
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
            try {
                record(ModelUsageDelta(
                    providerId = config.providerId, providerName = config.providerName,
                    modelId = config.model, modelDisplayName = config.modelDisplayName.ifBlank { config.model },
                    inputTokens = (billedInput ?: 0).toLong(),
                    outputTokens = (usage.outputTokens ?: 0).toLong(),
                    cachedTokens = (if (billedInput == null) 0 else usage.cachedTokens ?: 0).toLong(),
                    cacheCreationTokens = (if (billedInput == null) 0 else usage.cacheCreationTokens ?: 0).toLong(),
                    conversationId = request.usageConversationId, requestId = requestId, atMillis = startedAt,
                ))
                saved = usage
            } catch (failure: Exception) {
                accountingFailed(failure)
            }
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
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            persist() // Retry only a failed receipt, with the SAME request ID and cumulative values.
            try {
                if (finish != null) finish.invoke()
                else if (record === DEFAULT_USAGE_RECORD) finishAccounting { UsageStatsRepository.flushModelUsage() }
            } catch (failure: Exception) {
                accountingFailed(failure)
            }
            originalFailure?.let { original ->
                accountingFailures.filter { it !== original }.forEach { original.addSuppressed(it) }
            }
        }
    }
}
