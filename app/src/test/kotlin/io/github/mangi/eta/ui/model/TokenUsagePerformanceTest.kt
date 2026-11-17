package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单轮性能字段的展示派生值：只有真实耗时才算有数据，零值不算。 */
class TokenUsagePerformanceTest {
    @Test fun tokenOnlyUsageHasNoPerformanceStats() {
        val usage = TokenUsageUi(inputTokens = 1_200, outputTokens = 80, cachedTokens = 900)
        assertFalse(usage.hasPerformanceStats)
        assertNull(usage.averageTtftMs)
        assertNull(usage.tokensPerSecond)
        assertFalse(usage.isEmpty)
    }

    @Test fun zeroDurationsAreNotPerformanceStats() {
        assertFalse(TokenUsageUi(llmMs = 0L, toolMs = 0L, ttftMs = 0L, decodeMs = 0L).hasPerformanceStats)
        assertFalse(TokenUsageUi(decodeMs = 500L, decodeTokens = 0).hasPerformanceStats)
    }

    @Test fun anyRealDurationQualifies() {
        assertTrue(TokenUsageUi(llmMs = 1L).hasPerformanceStats)
        assertTrue(TokenUsageUi(toolMs = 900L).hasPerformanceStats)
        assertTrue(TokenUsageUi(ttftMs = 320L, ttftSteps = 1).hasPerformanceStats)
        assertTrue(TokenUsageUi(decodeMs = 1_000L, decodeTokens = 40).hasPerformanceStats)
    }

    @Test fun ttftIsAveragedOverTheStepsThatHadOne() {
        assertEquals(300L, TokenUsageUi(ttftMs = 900L, ttftSteps = 3).averageTtftMs)
        // 没有步数字段时按 1 步处理，保持老数据可读。
        assertEquals(420L, TokenUsageUi(ttftMs = 420L).averageTtftMs)
        assertNull(TokenUsageUi(ttftMs = 0L, ttftSteps = 1).averageTtftMs)
        assertNull(TokenUsageUi(ttftSteps = 2).averageTtftMs)
    }

    @Test fun tpsUsesDecodePhaseOnlyAndFallsBackToOutputTokens() {
        assertEquals(40.0, TokenUsageUi(decodeMs = 1_000L, decodeTokens = 40).tokensPerSecond!!, 0.0001)
        assertEquals(20.0, TokenUsageUi(decodeMs = 2_000L, outputTokens = 40).tokensPerSecond!!, 0.0001)
        assertNull(TokenUsageUi(decodeMs = 0L, decodeTokens = 40).tokensPerSecond)
        assertNull(TokenUsageUi(decodeMs = 1_000L, decodeTokens = 0, outputTokens = 0).tokensPerSecond)
        assertNull(TokenUsageUi(outputTokens = 40).tokensPerSecond)
    }

    @Test fun performanceOnlyUsageIsNotEmpty() {
        val usage = TokenUsageUi(stepCount = 2, llmMs = 3_000L, toolMs = 800L,
            ttftMs = 600L, ttftSteps = 2, decodeMs = 2_400L, decodeTokens = 120)
        assertFalse(usage.isEmpty)
        assertTrue(usage.hasPerformanceStats)
        assertEquals(300L, usage.averageTtftMs)
        assertEquals(50.0, usage.tokensPerSecond!!, 0.0001)
    }
}
