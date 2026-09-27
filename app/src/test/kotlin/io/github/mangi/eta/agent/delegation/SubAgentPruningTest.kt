package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.junit.Assert.*
import org.junit.Test

class SubAgentPruningTest {
    @Test fun pruningPreservesChildBillsAndManualPendingWhileSummaryInvalidatesUsage() {
        val t = SubAgentContextTracker(SubAgentContextStats("child", 1, "implementation", "m", "M", "P", 256000))
        t.accept(AgentEvent.ProviderRequestStarted(147))
        t.accept(AgentEvent.UsageReceived(147, AgentTokenUsage(inputTokens = 209563, outputTokens = 100)))
        t.manualRequest("pending")
        val before = t.value
        assertNull(t.accept(AgentEvent.ContextCompacted(148, true, 190, 190,
            compressorLabel = "工具输出预算修剪（原文可回读）")))
        assertEquals(before, t.value)
        t.accept(AgentEvent.UsageReceived(148, AgentTokenUsage(inputTokens = 162108), projected = true))
        assertEquals(before, t.value)
        t.accept(AgentEvent.ProviderRequestStarted(148))
        t.accept(AgentEvent.UsageReceived(148, AgentTokenUsage(inputTokens = 212441, outputTokens = 50)))
        assertEquals(212441, t.value.contextTokens)
        assertEquals(422004L, t.value.inputTokens)
        assertEquals(150L, t.value.outputTokens)
        assertEquals("pending", t.value.manualCompactionState)
        assertEquals(0, t.value.compactionCount)

        t.accept(AgentEvent.ContextCompactionStarted(149))
        assertEquals("compressing", t.value.manualCompactionState)
        t.accept(AgentEvent.ContextCompacted(149, true, 194, 69, compressorLabel = "摘要压缩"))
        assertNull(t.value.contextTokens)
        assertEquals("completed", t.value.manualCompactionState)
        assertEquals(1, t.value.compactionCount)
        t.accept(AgentEvent.UsageReceived(149, AgentTokenUsage(inputTokens = 80750), projected = true))
        assertNull(t.value.afterCompactionTokens)
        t.accept(AgentEvent.ProviderRequestStarted(149))
        t.accept(AgentEvent.UsageReceived(149, AgentTokenUsage(inputTokens = 95095)))
        assertEquals(95095, t.value.contextTokens)
        assertEquals(95095, t.value.afterCompactionTokens)
        assertEquals(517099L, t.value.inputTokens)
    }

    @Test fun pruningDoesNotFinishAlreadyStartedManualCompression() {
        val t = SubAgentContextTracker(SubAgentContextStats("child", 1, "implementation", "m", "M", "P", 256000))
        t.manualRequest("pending")
        t.accept(AgentEvent.ContextCompactionStarted(148))
        val before = t.value
        assertNull(t.accept(AgentEvent.ContextCompacted(148, true, 190, 190, pruningOnly = true)))
        assertEquals(before, t.value)
        assertTrue(t.value.isCompacting)
        assertEquals("compressing", t.value.manualCompactionState)
    }
}
