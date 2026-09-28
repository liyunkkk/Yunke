package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentSilentContextBudgetTest {
    @Test fun firstBoundaryUsesFullLocalRequest() {
        val budget = AgentSilentContextBudget()
        assertEquals(45000, budget.tokens(45000))
    }

    @Test fun cloudBaseAddsOnlyLocalDeltaIncludingToolsAndSchemaChanges() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(50000)
        budget.measured(70000)
        assertEquals(70000, budget.tokens(50000))
        assertEquals(81000, budget.tokens(61000))
        // The silent budget may cross 80% without changing any displayed bill.
        assertEquals(79000, budget.tokens(59000))
    }

    @Test fun usageLessNextRequestKeepsCalibrationButCannotFabricateANewBill() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(10000)
        budget.measured(20000)
        budget.requestStarted(14000)
        budget.measured(null)
        assertEquals(27000, budget.tokens(17000))
        budget.measured(22000)
        assertEquals(25000, budget.tokens(17000))
    }

    @Test fun sameRequestCorrectionReplacesTheCloudAnchor() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(10000)
        budget.measured(20000)
        budget.measured(18000)
        assertEquals(19000, budget.tokens(11000))
    }

    @Test fun committedPruningOrSummaryDropsOldCalibration() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(100000)
        budget.measured(150000)
        budget.contextReplaced()
        assertEquals(12000, budget.tokens(12000))
        budget.requestStarted(12000)
        budget.measured(15000)
        assertEquals(18000, budget.tokens(15000))
    }

    @Test fun negativeDeltasAndIntegerOverflowAreBounded() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(10000)
        budget.measured(1000)
        assertEquals(0, budget.tokens(1000))
        budget.requestStarted(0)
        budget.measured(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, budget.tokens(Int.MAX_VALUE))
    }

    @Test fun aggregatedGatewayBillAboveTheWindowCannotBecomeTheAnchor() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(34185)
        // Observed on the wire: one usage object summing a retried multi-leg request.
        budget.measured(784267, 500000)
        assertFalse(budget.isCalibrated())
        assertEquals(34185, budget.tokens(34185))
    }

    @Test fun aStepFarBeyondTheLocalGrowthIsRejected() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(21079)
        budget.measured(38880, 200000)
        // round 14 -> 15: +229037 billed while the local request grew by ~1100.
        budget.requestStarted(22194)
        budget.measured(267917, 200000)
        // The previous valid anchor survives, so the decision stays realistic.
        assertEquals(39995, budget.tokens(22194))
    }

    @Test fun aFirstBillManyTimesTheLocalEstimateIsStillAccepted() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(9000)
        // Relays that bill inline images as base64 legitimately report far above local.
        budget.measured(131470, 200000)
        assertTrue(budget.isCalibrated())
        assertEquals(131470, budget.tokens(9000))
    }

    @Test fun genuineOverflowSlightlyAboveTheWindowStaysVisible() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(196000)
        budget.measured(220000, 200000)
        assertTrue(budget.isCalibrated())
        assertEquals(220000, budget.tokens(196000))
    }

    @Test fun aDecreasingBillIsAlwaysAccepted() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(9714)
        budget.measured(784267)
        budget.requestStarted(7927)
        budget.measured(26424, 500000)
        assertEquals(26424, budget.tokens(7927))
    }
}
