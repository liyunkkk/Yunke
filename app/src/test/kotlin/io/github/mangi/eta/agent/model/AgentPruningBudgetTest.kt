package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPruningBudgetTest {
    @Test fun toolPruningKeepsCloudPlusLocalDeltaUntilTrueSummary() {
        val budget = AgentSilentContextBudget()
        budget.requestStarted(160000)
        budget.measured(209563) // round 147 cloud receipt
        assertEquals(209563, budget.tokens(160000))
        val prune = AgentEvent.ContextCompacted(148, true, 190, 190,
            compressorLabel = "工具输出预算修剪（原文可回读）")
        if (!prune.pruningOnly) budget.contextReplaced()
        // Smaller tool output is a delta from the cloud-calibrated request, not a new raw 162K anchor.
        assertEquals(211671, budget.tokens(162108))
        budget.requestStarted(162108)
        budget.measured(212441) // round 148 cloud receipt
        assertEquals(212441, budget.tokens(162108))
        val summary = AgentEvent.ContextCompacted(149, true, 194, 69, compressorLabel = "摘要压缩")
        if (!summary.pruningOnly) budget.contextReplaced()
        assertEquals(80750, budget.tokens(80750))
        budget.requestStarted(80750)
        budget.measured(95095)
        assertEquals(95095, budget.tokens(80750))
    }
}
