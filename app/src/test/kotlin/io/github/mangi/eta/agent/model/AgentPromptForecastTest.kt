package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentPromptForecastTest {
    private val owner = AgentPromptForecast.Binding("session-a", "provider/model-a")
    private fun forecast() = AgentPromptForecast(owner)

    @Test fun noReceiptUsesThePreparedRequestNotAnInventedAnchor() {
        val p = forecast()
        p.requestStarted(36000)
        assertEquals(36590, p.tokens(36000, 36590))
        assertNull(p.snapshot())
    }

    @Test fun observedCloudInputReplacesFullRoughEstimateForFollowingPrediction() {
        val p = forecast()
        p.requestStarted(36590)
        p.measured(29405, 2797, 272000)
        assertEquals(29405, p.tokens(36590))
        assertEquals(29497, p.tokens(36682))
        p.requestStarted(36682)
        p.measured(29478, 28910, 272000)
        assertEquals(29478, p.tokens(36682))
        assertEquals(29596, p.tokens(36800))
    }

    @Test fun cacheReadNeverGetsSubtractedFromCompleteInput() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, 18000, 200000)
        assertEquals(20700, p.tokens(10700))
    }

    @Test fun outputOnlyUsageDoesNotCreateAReceipt() {
        val p = forecast()
        p.requestStarted(12000)
        p.measured(null, null, 200000)
        assertNull(p.snapshot())
        assertEquals(14000, p.tokens(14000))
    }

    @Test fun usageLessRequestRetainsPreviousCalibrationWithoutRebasingIt() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, null, 200000)
        p.requestStarted(14000)
        p.measured(null, null, 200000)
        assertEquals(27000, p.tokens(17000))
    }

    @Test fun correctedUsageUsesTheSameRequestSnapshot() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, 1000, 200000)
        p.measured(18000, 1000, 200000)
        assertEquals(19000, p.tokens(11000))
    }

    @Test fun inheritedPredictionIsNotAnAutomaticCompressionReceipt() {
        val p = forecast()
        val budget = AgentSilentContextBudget()
        p.seed(50000, 190000, 200000)
        budget.seed(50000, 190000, 200000)
        assertEquals(191000, p.tokens(51000))
        assertNull(budget.cloudTokens())
        assertEquals(191000, budget.tokens(51000))
    }

    @Test fun compressionInvalidatesAnchorAndItsGeneration() {
        val p = forecast()
        p.requestStarted(100000)
        p.measured(150000, 120000, 200000)
        val old = p.snapshot()
        p.contextReplaced()
        assertNull(p.snapshot())
        assertEquals(12000, p.tokens(12000))
        p.inherit(old)
        assertNull(p.snapshot())
        p.requestStarted(12000)
        p.measured(15000, null, 200000)
        assertEquals(18000, p.tokens(15000))
    }

    @Test fun aDifferentConversationOrModelCannotInheritTheAnchor() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, null, 200000)
        val old = p.snapshot()
        for (binding in listOf(owner.copy(sessionId = "session-b"),
            owner.copy(modelKey = "provider/model-b"), owner.copy(generation = 1))) {
            val other = AgentPromptForecast(binding)
            other.inherit(old)
            assertNull(other.snapshot())
            assertEquals(10000, other.tokens(10000))
        }
    }

    @Test fun sameOwnerMayReuseAValidatedAnchor() {
        val p = forecast()
        p.inherit(AgentPromptForecast.Anchor(owner, 20000, 10000))
        assertEquals(21500, p.tokens(11500))
    }

    @Test fun implausibleReceiptDoesNotDestroyTheLastValidAnchor() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, 10000, 200000)
        p.requestStarted(11000)
        p.measured(900000, null, 200000)
        p.measured(21000, 22000, 200000)
        assertEquals(21000, p.tokens(11000))
    }

    @Test fun aLargeCloudToLocalRatioIsNotRejected() {
        val p = forecast()
        p.requestStarted(9000)
        p.measured(131470, 100000, 200000)
        assertEquals(132470, p.tokens(10000))
    }

    @Test fun toolAndSchemaGrowthUseTheSameLocalCountingBasis() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(17000, null, 200000)
        assertEquals(21500, p.tokens(14500))
        assertEquals(16000, p.tokens(9000))
    }

    @Test fun forecastTrustsAValidCompleteReceiptRatherThanVetoingItByLocalGrowth() {
        val p = forecast()
        p.requestStarted(10000)
        p.measured(20000, 18000, 200000)
        p.requestStarted(11000)
        p.measured(100000, 90000, 200000)
        assertEquals(100500, p.tokens(11500))
    }

    @Test fun zeroAndOverflowPredictionsAreBounded() {
        assertEquals(0, AgentPromptForecast.project(1000, 1000L, 10000L))
        assertEquals(Int.MAX_VALUE, AgentPromptForecast.project(Int.MAX_VALUE,
            Int.MAX_VALUE.toLong(), 0))
        val p = forecast()
        p.seed(10000, -1, 200000)
        assertNull(p.snapshot())
        assertEquals(0, p.tokens(-1))
    }
}
