package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.junit.Assert.*
import org.junit.Test

class SubAgentContextStatsTest {
    private fun tracker() = SubAgentContextTracker(SubAgentContextStats("task", 1, "research", "model", "Model", "Provider", 10000))
    @Test fun billedInputWinsAndProjectionsDoNotIncreaseBilling() {
        val t = tracker()
        t.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = 2000, inputTokens = 1000, outputTokens = 100)))
        t.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 1200), projected = true))
        assertEquals(1200, t.value.contextTokens)
        assertEquals(1000L, t.value.inputTokens)
        assertEquals(100L, t.value.outputTokens)
        t.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 1000, outputTokens = 110)))
        assertEquals(1000L, t.value.inputTokens)
        assertEquals(110L, t.value.outputTokens)
        t.accept(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 1200, outputTokens = 20)))
        assertEquals(2200L, t.value.inputTokens)
        assertEquals(130L, t.value.outputTokens)
    }
    @Test fun compactionClearsOldUsageAndCompletionIgnoresLateEvents() {
        val t = tracker()
        t.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 9000)))
        t.accept(AgentEvent.ContextCompactionStarted(2))
        assertTrue(t.value.isCompacting)
        assertEquals(9000, t.value.beforeCompactionTokens)
        t.accept(AgentEvent.ContextCompacted(2, true, 20, 5))
        assertFalse(t.value.isCompacting)
        assertNull(t.value.contextTokens)
        t.accept(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 2000), true))
        assertEquals(2000, t.value.afterCompactionTokens)
        assertEquals(1, t.value.compactionCount)
        t.accept(AgentEvent.ContextCompactionStarted(3))
        val finished = t.finish("cancelled")
        assertFalse(finished.isCompacting)
        assertNull(t.accept(AgentEvent.ContextCompactionStarted(4)))
        assertEquals(finished, t.value)
        assertEquals(finished, SubAgentContextStats.fromJson(finished.toJson()))
    }
    @Test fun unknownWindowAndUsageStayUnknownInJson() {
        val stats = tracker().value.copy(contextWindow = null)
        assertTrue(stats.toJson().isNull("context_percent"))
        assertTrue(stats.toJson().isNull("context_tokens"))
        assertEquals(stats, SubAgentContextStats.fromJson(stats.toJson()))
    }
    @Test fun finishingDuringManualCompressionClearsSpinnerAndRejectsLateCompletion() {
        val tracker = tracker()
        tracker.manualRequest("pending")
        tracker.accept(AgentEvent.ContextCompactionStarted(2))
        assertEquals("compressing", tracker.value.manualCompactionState)
        assertTrue(tracker.value.isCompacting)
        val terminal = tracker.finish("completed")
        assertEquals("ended", terminal.manualCompactionState)
        assertFalse(terminal.isCompacting)
        assertNull(tracker.accept(AgentEvent.ContextCompacted(2, true, 20, 5)))
        assertEquals(terminal, tracker.value)
        assertEquals(terminal, SubAgentContextStats.fromJson(terminal.toJson()))
    }

}
