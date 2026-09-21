package io.github.mangi.eta.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationUsageLedgerTest {
    private fun delta(id: String, conversation: String = "a", input: Long = 100, model: String = "main") = ModelUsageDelta(
        providerId = "p", providerName = "p", modelId = model, modelDisplayName = model,
        inputTokens = input, outputTokens = 10, cachedTokens = 70, requestId = id,
        conversationId = conversation, atMillis = 1000,
    )

    @Test fun allModelsAccumulateForOwnerWithoutOtherConversation() {
        var raw = ""
        for (model in listOf("main", "child", "summary", "vision", "title")) {
            raw = applyModelUsageDelta(raw, delta(model, model = model))
        }
        raw = applyModelUsageDelta(raw, delta("other", "b", 900))
        assertEquals(ConversationUsageTotals(500, 50, 350), conversationUsageTotals(raw, "a"))
        assertEquals(900L, conversationUsageTotals(raw, "b")!!.input)
        assertNull(conversationUsageTotals(raw, "unknown"))
    }

    @Test fun updateSameRequestOnlyAddsDifference() {
        var raw = applyModelUsageDelta(null, delta("a"))
        raw = applyModelUsageDelta(raw, delta("a", input = 120))
        raw = applyModelUsageDelta(raw, delta("b", input = 300))
        raw = applyModelUsageDelta(raw, delta("a", input = 120))
        assertEquals(ConversationUsageTotals(420, 20, 140), conversationUsageTotals(raw, "a"))
    }

    @Test fun legacyMigrationKeepsBaselineDoesNotAddOverlappingMessagesAndEvents() {
        var raw = applyModelUsageDelta(null, delta("old"))
        val root = JSONObject(raw).also { it.remove("conversationTotalsV1"); it.remove("conversationTotalsInitialized") }
        raw = seedConversationUsage(root.toString(), mapOf("a" to ConversationUsageTotals(1000, 100, 800)))
        assertEquals(ConversationUsageTotals(1000, 100, 800), conversationUsageTotals(raw, "a"))
        raw = applyModelUsageDelta(raw, delta("new", input = 200))
        // Compression/deletion changes message metadata, never the stored baseline.
        raw = seedConversationUsage(raw, mapOf("a" to ConversationUsageTotals(5000, 500, 4000)))
        raw = seedConversationUsage(raw, emptyMap())
        assertEquals(ConversationUsageTotals(1200, 110, 870), conversationUsageTotals(raw, "a"))
    }

    @Test fun detailPruningAndRestartCannotReduceLifetimeConversationUsage() {
        var raw = seedConversationUsage(null, mapOf("a" to ConversationUsageTotals(10000, 1000, 8000)))
        raw = applyModelUsageDelta(raw, delta("new"))
        val root = JSONObject(raw)
        root.getJSONObject("providers").getJSONObject("p").getJSONObject("models").getJSONObject("main")
            .put("events", JSONArray())
        raw = seedConversationUsage(root.toString(), emptyMap())
        assertEquals(ConversationUsageTotals(10100, 1010, 8070), conversationUsageTotals(raw, "a"))
    }

    @Test fun migratingAfterEarlierRequestLedgerNeverDoubleCounts() {
        var raw = applyModelUsageDelta(null, delta("already-recorded"))
        val root = JSONObject(raw).also { it.remove("conversationTotalsV1"); it.remove("conversationTotalsInitialized") }
        raw = seedConversationUsage(root.toString(), mapOf("a" to ConversationUsageTotals(100, 10, 70)))
        raw = applyModelUsageDelta(raw, delta("already-recorded", input = 150))
        assertEquals(ConversationUsageTotals(150, 10, 70), conversationUsageTotals(raw, "a"))
    }
}
