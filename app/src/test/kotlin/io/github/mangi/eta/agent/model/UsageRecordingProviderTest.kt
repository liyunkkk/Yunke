package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageRecordingProviderTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "unused", model = "actual-model",
        systemPrompt = "", providerId = "actual-provider", providerName = "Actual", modelDisplayName = "Actual model")
    private val request get() = ProviderRequest(config, JSONArray(), JSONArray(), "conversation")
    private fun provider(block: ((ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fake"
        override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, true, true, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(onEvent)
    }
    private fun answer() = ProviderResponse(JSONObject().put("content", "done").put("finish_reason", "stop"))

    @Test fun identicalUsageEventsAreIdempotentAndNewInvocationsAccumulate() {
        var raw = ""
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            repeat(2) { emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, outputTokens = 20, cachedTokens = 80))) }
            answer()
        }) { records += it; raw = applyModelUsageDelta(raw, it) }
        repeat(2) { decorated.complete(request, AgentRunController()) {} }
        assertEquals(2, records.size)
        assertNotEquals(records[0].requestId, records[1].requestId)
        assertEquals("actual-model", records[0].modelId)
        assertEquals("actual-provider", records[0].providerId)
        assertEquals(200L, decodeModelUsageSnapshot(raw).totalInputTokens)
    }

    @Test fun reportedUsageSurvivesFailureAndPartialSnapshotsKeepInput() {
        var raw = ""
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, cachedTokens = 80)))
            emit(ProviderEvent.Usage(AgentTokenUsage(outputTokens = 30)))
            throw IllegalStateException("stream interrupted")
        }) { raw = applyModelUsageDelta(raw, it) }
        assertThrows(IllegalStateException::class.java) { decorated.complete(request, AgentRunController()) {} }
        val result = decodeModelUsageSnapshot(raw)
        assertEquals(100L, result.totalInputTokens)
        assertEquals(30L, result.totalOutputTokens)
        assertEquals(80L, result.totalCachedTokens)
        assertEquals(1, result.providers.single().models.single().events.size)
    }

    @Test fun missingUsageIsNotEstimatedAndStorageFailureDoesNotBreakResponse() {
        val unreported = UsageRecordingProvider(provider { answer() }) { fail("No usage was returned") }
        assertEquals("done", unreported.complete(request, AgentRunController()) {}.assistantMessage.getString("content"))
        val failingStore = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10))); answer()
        }) { error("storage failure") }
        assertEquals("done", failingStore.complete(request, AgentRunController()) {}.assistantMessage.getString("content"))
    }

    @Test fun failingUiConsumerCannotDropReceivedUsage() {
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100))); answer()
        }) { records += it }
        assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) { error("UI disconnected") }
        }
        assertEquals(1, records.size)
    }
    @Test fun usageIsDeliveredBeforeAccountingAndCallbackFailureIsPreserved() {
        val order = mutableListOf<String>()
        val original = IllegalStateException("consumer stopped")
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100)))
            answer()
        }) { order += "record" }
        val thrown = assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) {
                order += "event"
                assertTrue(order == listOf("event"))
                throw original
            }
        }
        assertSame(original, thrown)
        assertEquals(listOf("event", "record"), order)
    }

    @Test fun anImpossiblePromptTotalIsNotSummedIntoLifetimeStatistics() {
        // Observed on the wire: 784267 billed for a request that succeeded on a 500000
        // window. Recording it would corrupt the totals permanently and make the stats
        // page disagree with the ring about the same traffic.
        val records = mutableListOf<ModelUsageDelta>()
        val windowed = config.copy(contextWindow = 500_000)
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 784_267, outputTokens = 1805, cachedTokens = 270_969)))
            answer()
        }) { records += it }
        decorated.complete(ProviderRequest(windowed, JSONArray(), JSONArray(), "conversation"), AgentRunController()) {}
        val delta = records.single()
        assertEquals(0L, delta.inputTokens)
        // The cache subset belongs to the rejected prompt, so it cannot be kept either.
        assertEquals(0L, delta.cachedTokens)
        // Output was genuinely produced and is still billed.
        assertEquals(1805L, delta.outputTokens)
    }

    @Test fun aRealOverflowSlightlyAboveTheWindowIsStillRecorded() {
        val records = mutableListOf<ModelUsageDelta>()
        val windowed = config.copy(contextWindow = 200_000)
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 220_000, outputTokens = 100, cachedTokens = 50_000)))
            answer()
        }) { records += it }
        decorated.complete(ProviderRequest(windowed, JSONArray(), JSONArray(), "conversation"), AgentRunController()) {}
        assertEquals(220_000L, records.single().inputTokens)
        assertEquals(50_000L, records.single().cachedTokens)
    }

    @Test fun withoutAConfiguredWindowEveryReportedPromptIsStillRecorded() {
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 784_267))); answer()
        }) { records += it }
        decorated.complete(request, AgentRunController()) {}
        assertEquals(784_267L, records.single().inputTokens)
    }

    @Test fun requestCompletionFlushesAfterAllPartialsEvenWhenProviderFails() {
        val order = mutableListOf<String>()
        val original = IllegalStateException("stream ended")
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100)))
            emit(ProviderEvent.Usage(AgentTokenUsage(outputTokens = 7)))
            throw original
        }, finish = { order += "flush" }) { delta -> order += "record:${delta.outputTokens}" }
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) { }
        })
        assertEquals(listOf("record:0", "record:7", "flush"), order)
    }

    @Test fun flushFailureCannotReplaceSuccessfulResponseOrConsumerError() {
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100)))
            answer()
        }, finish = { error("flush failed") }) { }
        assertEquals("done", decorated.complete(request, AgentRunController()) { }.assistantMessage.getString("content"))
        val consumerError = IllegalStateException("consumer failed")
        assertSame(consumerError, assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) { throw consumerError }
        })
    }

    @Test fun accountingUsesConversationOwnerNotNetworkSession() {
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100))); answer()
        }) { records += it }
        decorated.complete(request.copy(sessionId = "isolated-title-session", usageConversationId = "owner"), AgentRunController()) {}
        assertEquals("owner", records.single().conversationId)
    }
}
