package io.github.mangi.eta.agent.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentBudgetTest {
    @Test fun planUsesP75WithHeadroomWhenSamplesAreEnough() {
        val samples = listOf(
            SubAgentSample(tokens = 8_000, rounds = 3, ok = true),
            SubAgentSample(tokens = 12_000, rounds = 3, ok = true),
            SubAgentSample(tokens = 16_000, rounds = 3, ok = true),
            SubAgentSample(tokens = 20_000, rounds = 3, ok = true),
        )
        val plan = AgentSubAgentBudget.plan(SubAgentScope.QUICK, samples)
        // P75 = 20000，留 25% 余量 = 25000，落在 QUICK 区间内。
        assertEquals(25_000, plan.tokenBudget)
        assertEquals(3, plan.maxRounds)
        assertEquals(4, plan.sampleCount)
        assertTrue(plan.fromHistory)
        assertEquals(SubAgentScope.QUICK, plan.scope)
    }

    @Test fun planFallsBackToTierDefaultsWhenSamplesAreInsufficient() {
        val samples = listOf(
            SubAgentSample(tokens = 50_000, rounds = 30, ok = true),
            SubAgentSample(tokens = 60_000, rounds = 30, ok = true),
            SubAgentSample(tokens = 70_000, rounds = 30, ok = true),
        )
        val plan = AgentSubAgentBudget.plan(SubAgentScope.QUICK, samples)
        assertEquals(SubAgentScope.QUICK.defaultRounds, plan.maxRounds)
        assertEquals(SubAgentScope.QUICK.defaultTokens, plan.tokenBudget)
        assertEquals(3, plan.sampleCount)
        assertFalse(plan.fromHistory)
    }

    @Test fun zeroTokenSamplesDoNotCountTowardHistory() {
        val samples = listOf(
            SubAgentSample(tokens = 0, rounds = 0, ok = false),
            SubAgentSample(tokens = 0, rounds = 0, ok = false),
            SubAgentSample(tokens = 0, rounds = 0, ok = false),
            SubAgentSample(tokens = 0, rounds = 0, ok = false),
        )
        val plan = AgentSubAgentBudget.plan(SubAgentScope.DEEP, samples)
        assertEquals(SubAgentScope.DEEP.defaultTokens, plan.tokenBudget)
        assertEquals(0, plan.sampleCount)
        assertFalse(plan.fromHistory)
    }

    @Test fun failedSamplesAreDeprioritizedForFitting() {
        val samples = listOf(
            SubAgentSample(tokens = 10_000, rounds = 2, ok = true),
            SubAgentSample(tokens = 10_000, rounds = 2, ok = true),
            SubAgentSample(tokens = 10_000, rounds = 2, ok = true),
            SubAgentSample(tokens = 10_000, rounds = 2, ok = true),
            // 失败样本多半是中途断掉的，不能按它估算预算。
            SubAgentSample(tokens = 999_999, rounds = 99, ok = false),
        )
        val plan = AgentSubAgentBudget.plan(SubAgentScope.QUICK, samples)
        assertEquals(12_500, plan.tokenBudget)
        assertEquals(2, plan.maxRounds)
        assertTrue(plan.fromHistory)
    }

    @Test fun tokenBudgetIsClampedToScopeBounds() {
        val samples = listOf(
            SubAgentSample(tokens = 500_000, rounds = 40, ok = true),
            SubAgentSample(tokens = 500_000, rounds = 40, ok = true),
            SubAgentSample(tokens = 500_000, rounds = 40, ok = true),
            SubAgentSample(tokens = 500_000, rounds = 40, ok = true),
        )
        val plan = AgentSubAgentBudget.plan(SubAgentScope.COMPARE, samples)
        assertEquals(SubAgentScope.COMPARE.maxTokens, plan.tokenBudget)
        assertEquals(SubAgentScope.COMPARE.defaultRounds * 2, plan.maxRounds)
    }

    @Test fun tierMapsToBudgetScope() {
        assertEquals(SubAgentScope.QUICK, SubAgentTaskTier.SIMPLE.scope)
        assertEquals(SubAgentScope.COMPARE, SubAgentTaskTier.REGULAR.scope)
        assertEquals(SubAgentScope.DEEP, SubAgentTaskTier.COMPLEX.scope)
        assertEquals(SubAgentScope.QUICK, SubAgentTaskTier.scopeOf(SubAgentTaskTier.SIMPLE))
        // 未设置分工（研究/审查代理）按中间档处理。
        assertEquals(SubAgentScope.COMPARE, SubAgentTaskTier.scopeOf(null))
        assertEquals(SubAgentScope.COMPARE, SubAgentScope.fromWire("unknown"))
    }

    @Test fun percentileHandlesEmptyAndSingleValues() {
        assertEquals(0, AgentSubAgentBudget.percentile(emptyList(), 75))
        assertEquals(10, AgentSubAgentBudget.percentile(listOf(10), 75))
        assertEquals(30, AgentSubAgentBudget.percentile(listOf(30, 10, 20), 75))
    }
}
