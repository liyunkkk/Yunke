package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBilledPromptPlausibilityTest {

    @Test fun relayCacheReadAboveTheWindowIsInflated() {
        // ST API `claude-超高缓` on a 500k window; the same request billed 129987 uncached.
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(546_739, 520_658, 500_000))
    }

    @Test fun aCacheReadAboveItsOwnTotalIsInflated() {
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(10_000, 12_000, 500_000))
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(10_000, 12_000, null))
    }

    @Test fun realCacheHitsAreKeptHoweverFarTheyExceedTheLocalEstimate() {
        // GPT pro 272k window: cache grows with the content.
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(258_397, 257_024, 272_000))
        // Screenshots under-count locally; the bill alone decides, not the local estimate.
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(208_000, 207_900, 260_000))
    }

    @Test fun billsWithoutACacheReadAreNeverDropped() {
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(199_206, 0, 500_000))
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(199_206, null, 500_000))
    }

    @Test fun exactlyTheWindowIsStillAPossiblePrefix() {
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(600_000, 500_000, 500_000))
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(600_000, 500_001, 500_000))
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(600_000, 500_001, null))
    }
}
