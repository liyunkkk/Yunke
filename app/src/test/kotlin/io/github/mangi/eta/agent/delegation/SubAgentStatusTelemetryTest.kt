package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.junit.Assert.*
import org.junit.Test

class SubAgentStatusTelemetryTest {
    private fun initial() = SubAgentContextStats("status-fixture", 1, "research", "model", "Model", "Provider", 1000,
        status = "queued", statusVersion = 7, statusChangedAtMs = 1L)

    @Test fun transitionVersionChangesWithoutReadersAndOldJsonIsSideEffectFree() {
        val tracker = SubAgentContextTracker(initial())
        val firstPause = tracker.updateStatus("awaiting_decision")
        tracker.updateStatus("running")
        val secondPause = tracker.updateStatus("awaiting_decision")
        assertEquals(firstPause.statusVersion + 2, secondPause.statusVersion)
        assertTrue(secondPause.statusChangedAtMs!! >= firstPause.statusChangedAtMs!!)
        repeat(3) {
            assertEquals(firstPause, SubAgentContextStats.fromJson(firstPause.toJson()))
            firstPause.copy(status = "running")
            assertEquals(secondPause, tracker.value)
            assertEquals(secondPause, tracker.updateStatus("awaiting_decision"))
        }
    }

    @Test fun realUsageAndCompactionDoNotResetStatusToken() {
        val tracker = SubAgentContextTracker(initial())
        val running = tracker.start()
        tracker.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 100, outputTokens = 5)))
        assertEquals(100, tracker.value.contextTokens)
        assertEquals(running.statusVersion, tracker.value.statusVersion)
        assertEquals(running.statusChangedAtMs, tracker.value.statusChangedAtMs)
        tracker.manualRequest("pending")
        tracker.accept(AgentEvent.ContextCompactionStarted(2))
        assertEquals("compressing", tracker.value.manualCompactionState)
        assertEquals(running.statusVersion, tracker.value.statusVersion)
        assertEquals(running.statusChangedAtMs, tracker.value.statusChangedAtMs)
        tracker.accept(AgentEvent.ContextCompacted(2, true, 20, 5))
        assertEquals(running.statusVersion, tracker.value.statusVersion)
        assertEquals(running.statusChangedAtMs, tracker.value.statusChangedAtMs)
        assertEquals(tracker.value, SubAgentContextStats.fromJson(tracker.value.toJson()))
    }

    @Test fun legacyJsonAndNullableTimestampRemainPassive() {
        val old = initial().toJson().apply { remove("status_version"); remove("status_changed_at_ms") }
        val restored = SubAgentContextStats.fromJson(old)
        assertEquals(0L, restored.statusVersion)
        assertNull(restored.statusChangedAtMs)
        val nullable = initial().copy(statusChangedAtMs = null)
        assertEquals(nullable, SubAgentContextStats.fromJson(nullable.toJson()))
        assertNull(nullable.statusChangedAtMs)
    }
}
