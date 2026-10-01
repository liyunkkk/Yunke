package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBilledPromptPlausibilityTest {

    @Test fun relayCacheReadsFarAboveTheLocalRequestAreInflated() {
        // ST API `claude-超高缓`, local request ~156k; uncached the same request billed 129987.
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(546_739, 520_658, 156_434))
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(469_990, 469_662, 157_579))
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(361_153, 333_918, 157_835))
    }

    @Test fun realCacheHitsAndUncachedBillsAreKept() {
        // GPT pro 272k window: cache grows with the content.
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(258_397, 257_024, 200_000))
        // First Claude round: cache write only, no read.
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(199_206, 0, 155_636))
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(199_206, null, 155_636))
        // Relays that bill inline images as text: total far above local, no cache read.
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(131_470, null, 9_000))
    }

    @Test fun exactlyOneAndAHalfTimesTheLocalRequestIsStillAccepted() {
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(150_000, 150_000, 100_000))
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(150_001, 150_001, 100_000))
    }

    @Test fun aCacheReadAboveItsOwnTotalIsInflated() {
        assertTrue(AgentBilledPromptPlausibility.isInflatedCacheRead(10_000, 12_000, 20_000))
    }

    @Test fun tinyOrMissingLocalEstimatesNeverDropABill() {
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(9_000, 8_000, 300))
        assertFalse(AgentBilledPromptPlausibility.isInflatedCacheRead(9_000, 8_000, null))
    }
}
