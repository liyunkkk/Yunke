package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单轮耗时聚合：llmMs 只吃模型请求，工具等待单独一路，避免 TPS/TTFT 被工具时间稀释。
 */
class AgentTurnMetricsTest {
    @Test fun modelTimeExcludesToolWaiting() {
        val metrics = AgentTurnMetrics()
        metrics.recordStep(
            stepLlmMs = 1_000L,
            stepTtftMs = 300L,
            stepDecodeMs = 700L,
            stepUsage = AgentTokenUsage(inputTokens = 100, outputTokens = 35),
        )
        // 工具跑了 30 秒：只进 toolMs，绝不能进 llmMs。
        metrics.recordToolMs(30_000L)
        metrics.recordStep(
            stepLlmMs = 800L,
            stepTtftMs = null,
            stepDecodeMs = null,
            stepUsage = AgentTokenUsage(inputTokens = 140, outputTokens = 12),
        )

        val snapshot = metrics.snapshot()
        assertTrue(snapshot.stepCount == 2)
        assertEquals(1_800L, snapshot.llmMs)
        assertEquals(30_000L, snapshot.toolMs)
        assertEquals(300L, snapshot.ttftMs)
        assertTrue(snapshot.ttftSteps == 1)
        // 第一步有输出 → 进解码统计；第二步没有首 token → 不进。
        assertEquals(700L, snapshot.decodeMs)
        assertTrue(snapshot.decodeTokens == 35)
        assertTrue(snapshot.inputTokens == 240)
        assertTrue(snapshot.outputTokens == 47)
    }

    @Test fun stepsWithoutATokenContributeNeitherTtftNorDecode() {
        val metrics = AgentTurnMetrics()
        metrics.recordStep(
            stepLlmMs = 500L,
            stepTtftMs = null,
            stepDecodeMs = null,
            stepUsage = AgentTokenUsage(inputTokens = 10),
        )
        val snapshot = metrics.snapshot()
        assertTrue(snapshot.stepCount == 1)
        assertEquals(500L, snapshot.llmMs)
        assertNull(snapshot.ttftMs)
        assertNull(snapshot.ttftSteps)
        assertNull(snapshot.decodeMs)
        assertNull(snapshot.decodeTokens)
        assertNull(snapshot.toolMs)
    }

    @Test fun outputWithoutDecodeWindowDoesNotInventASpeed() {
        val metrics = AgentTurnMetrics()
        // provider 只回 usage、没有首 token 时间：不能凭空造 decodeMs。
        metrics.recordStep(
            stepLlmMs = 2_000L,
            stepTtftMs = null,
            stepDecodeMs = null,
            stepUsage = AgentTokenUsage(outputTokens = 60),
        )
        val snapshot = metrics.snapshot()
        assertNull(snapshot.decodeMs)
        assertNull(snapshot.decodeTokens)
        assertTrue(snapshot.outputTokens == 60)
    }

    @Test fun zeroOutputStepKeepsDecodeWindowOutOfTheAverage() {
        val metrics = AgentTurnMetrics()
        metrics.recordStep(1_000L, 200L, 800L, AgentTokenUsage(outputTokens = 0))
        metrics.recordStep(1_000L, 200L, 800L, AgentTokenUsage(outputTokens = 50))
        val snapshot = metrics.snapshot()
        assertTrue(snapshot.ttftSteps == 2)
        assertEquals(400L, snapshot.ttftMs)
        // 只有第二步入解码：800ms / 50 tok = 62.5 tok/s。
        assertEquals(800L, snapshot.decodeMs)
        assertTrue(snapshot.decodeTokens == 50)
        val tps = snapshot.decodeTokens!!.toDouble() / (snapshot.decodeMs!!.toDouble() / 1_000.0)
        assertEquals(62.5, tps, 0.0001)
    }

    @Test fun providerWithoutUsageStillReportsModelTime() {
        val metrics = AgentTurnMetrics()
        val usage = metrics.recordStep(
            stepLlmMs = 1_500L,
            stepTtftMs = 400L,
            stepDecodeMs = 1_100L,
            stepUsage = null,
        )
        assertTrue(usage.hasTimingOnly())
        assertEquals(1_500L, usage.llmMs)
        assertNull(usage.outputTokens)
        assertNull(usage.decodeMs)
        assertFalse(usage.isEmpty)
    }

    @Test fun toolTimeIsIndependentOfStepsAndAccumulates() {
        val metrics = AgentTurnMetrics()
        metrics.recordToolMs(120L)
        metrics.recordToolMs(-30L)
        metrics.recordToolMs(380L)
        val snapshot = metrics.snapshot()
        assertEquals(500L, snapshot.toolMs)
        assertNull(snapshot.llmMs)
        assertNull(snapshot.stepCount)
    }

    @Test fun resetClearsEveryAccumulator() {
        val metrics = AgentTurnMetrics()
        metrics.recordStep(1_000L, 300L, 700L, AgentTokenUsage(inputTokens = 10, outputTokens = 5))
        metrics.recordToolMs(400L)
        metrics.reset()
        val snapshot = metrics.snapshot()
        assertTrue(metrics.stepCount == 0)
        assertNull(snapshot.llmMs)
        assertNull(snapshot.toolMs)
        assertNull(snapshot.inputTokens)
        assertNull(snapshot.outputTokens)
    }

    @Test fun timingsCanBeOverlaidOnAnotherReceipt() {
        val stepReceipt = AgentTokenUsage(inputTokens = 1_000, outputTokens = 40, cachedTokens = 800)
        val timings = AgentTurnMetrics().apply {
            recordStep(2_000L, 250L, 1_750L, AgentTokenUsage(outputTokens = 40))
            recordToolMs(900L)
        }.snapshot()
        val display = stepReceipt.withTimingsFrom(timings)
        // token 保持在步回执上，耗时来自整轮累计。
        assertTrue(display.inputTokens == 1_000)
        assertTrue(display.cachedTokens == 800)
        assertEquals(2_000L, display.llmMs)
        assertEquals(900L, display.toolMs)
        assertEquals(250L, display.ttftMs)
    }

    private fun AgentTokenUsage.hasTimingOnly(): Boolean =
        llmMs != null && inputTokens == null && outputTokens == null
}
