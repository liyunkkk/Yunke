package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.*
import org.junit.Test

class RequestOverheadCalibrationTest {
    private fun learn(previous: RequestOverheadCalibration.Sample?, id: String, input: Int = 37214,
        history: Int = 481, overhead: Int = 25270, route: String = "p:model:config") =
        requireNotNull(RequestOverheadCalibration.learn(previous, input, history, overhead,
            requestId = id, routeSignature = route))
    private fun stable(input: Int = 37214): RequestOverheadCalibration.Sample {
        var sample: RequestOverheadCalibration.Sample? = null
        repeat(3) { sample = learn(sample, "request-$it", input) }
        return requireNotNull(sample)
    }

    @Test fun threeDistinctRecentRequestsAreRequiredAndDuplicateDoesNotAdvance() {
        val first = learn(null, "one")
        assertNull(first.estimate(481, 25270))
        assertNull(RequestOverheadCalibration.learn(first, 37214, 481, 25270,
            requestId = "one", routeSignature = first.routeSignature))
        val second = learn(first, "two")
        assertNull(second.ratio)
        val third = learn(second, "three")
        assertEquals(3, third.samples)
        assertEquals(37214, third.estimate(481, 25270))
    }

    @Test fun claudeUnderestimateAndDeepSeekOverestimateAreBothCorrected() {
        for (input in listOf(37214, 19000)) {
            val sample = stable(input)
            val ui = liveContextUsage(emptyList(), "", emptyList(), null, historyTokenCount = 481,
                requestOverheadTokens = 25270, overheadCalibrationTokens = sample)
            assertEquals(input, ui.contextTokens)
            assertTrue(ui.estimated)
            assertTrue(formatContextUsage(ui).startsWith("≈"))
            assertEquals(25751, compressionContextUsage(emptyList(), "", emptyList(), null,
                localHistoryTokenCount = 481, requestOverheadTokens = 25270).contextTokens)
        }
    }

    @Test fun runtimeInjectionToleranceStillAllowsRealUiBasisButNotLongSummary() {
        val sample = stable()
        assertNotNull(sample.estimate(6, 25237)) // observed runtime history 481 / overhead 25270
        assertNull(sample.estimate(120000, 25237))
        assertNull(sample.estimate(481, 28000))
        assertNull(sample.estimate(481, 0))
    }

    @Test fun latestOutlierAndConfigurationChangeInvalidateStability() {
        val stable = stable()
        assertNull(learn(stable, "outlier", 26000).ratio)
        val otherRoute = learn(stable, "other-route", route = "p:other:config")
        assertEquals(1, otherRoute.samples)
        assertNull(otherRoute.ratio)
        assertNull(RequestOverheadCalibration.learn(stable, 100000, 481, 25270,
            requestId = "bad", routeSignature = stable.routeSignature))
        assertNull(RequestOverheadCalibration.learn(stable, 37214, 481, 25270, inflatedCache = true,
            requestId = "cache", routeSignature = stable.routeSignature))
    }

    @Test fun routeSignatureIncludesEndpointButNotPasswordOrTimestamps() {
        val model = Model("m", "model", "Model", createdAt = 1)
        val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.org/v1", apiKey = "first", createdAt = 1)
        val signature = RequestOverheadCalibration.routeSignature(provider, model)
        assertTrue(signature.isNotBlank())
        assertEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(apiKey = "second", createdAt = 100), model.copy(createdAt = 500)))
        assertNotEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(baseUrl = "https://other.org/v1"), model))
        assertNotEquals(signature, RequestOverheadCalibration.routeSignature(provider.copy(endpointMode = "responses"), model))
    }

    @Test fun noneAndUnknownIgnoreDraftAndRawProjectionAndActualWinsOverLearning() {
        for (first in listOf(true, false)) {
            val policy = ContextDisplayPolicy(firstTurn = first)
            val idle = liveContextUsage(emptyList(), "", emptyList(), null, requestOverheadTokens = 25270, contextDisplayPolicy = policy)
            val typing = liveContextUsage(emptyList(), "a long draft".repeat(100), emptyList(), null,
                requestOverheadTokens = 25270, projectedContextTokens = 99999, contextDisplayPolicy = policy)
            assertEquals(idle, typing)
            assertNull(idle.contextTokens)
            assertNull(idle.progress)
            assertEquals(if (first) "无" else "未知", formatContextUsage(idle))
        }
        val actual = liveContextUsage(emptyList(), "draft", emptyList(), null, billedContextTokens = 12345,
            overheadCalibrationTokens = stable(), requestOverheadTokens = 25270)
        assertEquals(12345, actual.contextTokens)
        assertFalse(actual.estimated)
    }

    @Test fun compressionForcesUnknownEvenWithStableLearningUntilFreshReceipt() {
        val compacted = liveContextUsage(emptyList(), "draft", emptyList(), null, historyTokenCount = 481,
            requestOverheadTokens = 25270, overheadCalibrationTokens = stable(),
            contextDisplayPolicy = ContextDisplayPolicy(awaitingReceipt = true), receiptEstimateTokens = 37000)
        assertNull(compacted.contextTokens)
        assertEquals("未知", formatContextUsage(compacted))
        val fresh = liveContextUsage(emptyList(), "", emptyList(), null, billedContextTokens = 27723,
            overheadCalibrationTokens = stable(), contextDisplayPolicy = ContextDisplayPolicy())
        assertEquals(27723, fresh.contextTokens)
        assertFalse(fresh.estimated)
    }

    @Test fun sameSessionReceiptDegradesOnlyWithinApplicableComposition() {
        val estimate = RequestOverheadCalibration.receiptEstimate(37214, 481, 25270, 300, 25237)
        assertNotNull(estimate)
        assertTrue(liveContextUsage(emptyList(), "", emptyList(), null, receiptEstimateTokens = estimate).estimated)
        assertNull(RequestOverheadCalibration.receiptEstimate(37214, 481, 25270, 120000, 25237))
    }
}
