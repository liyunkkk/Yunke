package io.github.mangi.eta.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelUsageRequestTest {
    private fun delta(id: String, input: Long = 100, at: Long = 1000) = ModelUsageDelta(
        providerId = "provider", providerName = "Provider", modelId = "model", modelDisplayName = "Model",
        inputTokens = input, outputTokens = 10, cachedTokens = 70,
        conversationId = "same-conversation", round = 1, requestId = id, atMillis = at,
    )

    @Test fun repeatedDisplayRoundFromDifferentRequestsMustAccumulate() {
        val raw = applyModelUsageDelta(applyModelUsageDelta(null, delta("request-a")), delta("request-b", 200))
        val snapshot = decodeModelUsageSnapshot(raw)
        assertEquals(300L, snapshot.totalInputTokens)
        assertEquals(20L, snapshot.totalOutputTokens)
        assertEquals(140L, snapshot.totalCachedTokens)
        assertEquals(300L, snapshot.filtered(1, 2000).totalInputTokens)
    }

    @Test fun progressiveSnapshotsReplaceOnlyTheSameRequest() {
        var raw = applyModelUsageDelta(null, delta("a", 100))
        raw = applyModelUsageDelta(raw, delta("b", 300))
        raw = applyModelUsageDelta(raw, delta("a", 200))
        raw = applyModelUsageDelta(raw, delta("a", 200))
        val model = decodeModelUsageSnapshot(raw).providers.single().models.single()
        assertEquals(500L, model.inputTokens)
        assertEquals(2, model.events.size)
        assertEquals(500L, decodeModelUsageSnapshot(raw).filtered(1, 2000).totalInputTokens)
    }

    @Test fun legacyRoundAndNewRequestDoNotOverwriteEachOther() {
        val legacy = delta("unused").copy(requestId = null)
        val raw = applyModelUsageDelta(applyModelUsageDelta(null, legacy), delta("new", 200))
        assertEquals(300L, decodeModelUsageSnapshot(raw).totalInputTokens)
    }

    @Test fun dayFilterDoesNotScaleOrMergeDifferentRequests() {
        val raw = applyModelUsageDelta(applyModelUsageDelta(null, delta("day1", 100, 1000)), delta("day2", 200, 100000))
        assertEquals(100L, decodeModelUsageSnapshot(raw).filtered(1, 2000).totalInputTokens)
        assertEquals(200L, decodeModelUsageSnapshot(raw).filtered(99999, 100001).totalInputTokens)
        assertEquals(300L, decodeModelUsageSnapshot(raw).filtered(null, null).totalInputTokens)
    }

    @Test fun trimmingDetailDoesNotLowerLifetimeTotals() {
        val root = JSONObject(applyModelUsageDelta(null, delta("first", 100)))
        val model = root.getJSONObject("providers").getJSONObject("provider").getJSONObject("models").getJSONObject("model")
        val events = JSONArray()
        repeat(4000) { i -> events.put(JSONObject().put("t", i + 1).put("in", 1).put("out", 0).put("c", "same").put("q", "request-$i")) }
        model.put("events", events).put("inputTokens", 5000L)
        val raw = applyModelUsageDelta(root.toString(), delta("last", 200, 99999))
        val result = decodeModelUsageSnapshot(raw).providers.single().models.single()
        assertEquals(5200L, result.inputTokens)
        assertEquals(4000, result.events.size)
        assertEquals(200L, result.filtered(99999, 99999)!!.inputTokens)
    }
}
