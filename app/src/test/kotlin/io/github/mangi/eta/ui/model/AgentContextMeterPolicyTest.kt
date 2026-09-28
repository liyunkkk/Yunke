package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentContextMeterPolicyTest {
    @Test fun emptyFirstDraftIncludesSystemAndToolOverhead() {
        val usage = liveContextUsage(emptyList(), "", emptyList(), null, requestOverheadTokens = 12000)
        assertEquals(12000, usage.contextTokens)
        assertTrue(usage.estimated)
        assertTrue(formatContextUsage(usage).startsWith("≈12K"))
    }

    @Test fun cloudRingStaysFixedWhileSilentBudgetAddsHistoryAndToolDeltas() {
        val ring = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1200, billedContextTokens = 10000, requestOverheadTokens = 700)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1200, billedContextTokens = 10000, requestOverheadTokens = 700,
            billedHistoryTokens = 1000, billedOverheadTokens = 500)
        assertEquals(10000, ring.contextTokens)
        assertFalse(ring.estimated)
        assertEquals(10400, budget.contextTokens)
        assertTrue(budget.estimated)
    }

    @Test fun legacyCloudReceiptDoesNotDisableSilentLocalPressure() {
        val ring = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 20000, billedContextTokens = 10000, requestOverheadTokens = 3000)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 20000, localHistoryTokenCount = 20000,
            billedContextTokens = 10000, requestOverheadTokens = 3000)
        assertEquals(10000, ring.contextTokens)
        assertEquals(23000, budget.contextTokens)
        val conservative = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1000, billedContextTokens = 10000, requestOverheadTokens = 500)
        assertEquals(10000, conservative.contextTokens)
    }

    @Test fun summaryWithoutNewCloudUsageUsesNewHistoryNotOldBill() {
        val history = listOf(AgentModelClient.ConversationMessage("system", "short summary"))
        val usage = liveContextUsage(history, "", emptyList(), null, requestOverheadTokens = 500)
        assertEquals(500 + AgentContextBudget.countMessage(history.single()), usage.contextTokens)
        assertTrue(usage.estimated)
    }

    @Test fun validReceiptPersistsCalibrationAndInvalidHistoryCannotRestoreIt() {
        val raw = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 12345, 6000, 700)
        val receipt = requireNotNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "history"))
        assertEquals(12345, receipt.inputTokens)
        assertEquals(6000, receipt.historyTokens)
        assertEquals(700, receipt.overheadTokens)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "other", "history"))
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "edited"))
        assertEquals("", CloudUsageReceiptCodec.encode("c", "p", "m", "history", null, 6000, 700))
    }

    @Test fun unsentHistoryMediaDoesNotDriftRawCloudCalibration() {
        val part = org.json.JSONObject().put("type", "video_url")
            .put("video_url", org.json.JSONObject().put("url", "data:video/mp4;base64,AAAA"))
        val history = listOf(AgentModelClient.ConversationMessage("user", "", contentJson = org.json.JSONArray().put(part).toString()))
        val raw = history.sumOf { AgentContextBudget.countMessage(it) }
        val preview = liveContextUsage(history, "", emptyList(), null, requestOverheadTokens = 100)
        assertTrue(requireNotNull(preview.contextTokens) < raw + 100)
        val budget = compressionContextUsage(history, "", emptyList(), null,
            billedContextTokens = 9000, billedHistoryTokens = raw,
            billedOverheadTokens = 100, requestOverheadTokens = 100)
        assertEquals(9000, budget.contextTokens)
        assertEquals(9000, liveContextUsage(history, "", emptyList(), null, billedContextTokens = 9000).contextTokens)
    }

    @Test fun videoOnlyModelStillCountsAcceptedDraftVideo() {
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 100000,
            supportsVision = false, supportsVideo = true)
        val attachment = PendingImageUi("v", "content://video", "data:image/jpeg;base64,AAAA", "video/mp4",
            isVideo = true, byteSize = 2 * 1024 * 1024)
        val usage = liveContextUsage(emptyList(), "watch", listOf(attachment), model)
        assertEquals(AgentContextBudget.countCurrentTurn("watch", listOf(attachment.toOutboundModelImage(true))), usage.contextTokens)
    }

    @Test fun anInFlightRunKeepsTheWindowItWasLaunchedWith() {
        // The picker already reports the new 500k limit, but the running request was
        // built against 200k: the percentage must stay on the window it really uses.
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 500_000)
        val ring = liveContextUsage(emptyList(), "", emptyList(), model,
            billedContextTokens = 100_000, activeRunContextWindow = 200_000)
        assertEquals(200_000, ring.contextWindow)
        assertEquals(100_000, ring.contextTokens)

        val budget = compressionContextUsage(emptyList(), "", emptyList(), model,
            historyTokenCount = 1_200, billedContextTokens = 100_000, requestOverheadTokens = 700,
            billedHistoryTokens = 1_000, billedOverheadTokens = 500,
            activeRunContextWindow = 200_000)
        assertEquals(200_000, budget.contextWindow)
    }

    @Test fun withoutAnInFlightRunThePickerWindowIsUsed() {
        val model = AgentModelOptionUi("m", "p", "P", "custom", "m", "M", 500_000)
        assertEquals(500_000,
            liveContextUsage(emptyList(), "", emptyList(), model, billedContextTokens = 100_000).contextWindow)
        // A non-positive override is ignored rather than hiding the real window.
        assertEquals(500_000,
            liveContextUsage(emptyList(), "", emptyList(), model,
                billedContextTokens = 100_000, activeRunContextWindow = 0).contextWindow)
    }

}
