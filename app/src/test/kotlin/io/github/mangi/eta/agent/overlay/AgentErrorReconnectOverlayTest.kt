package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class AgentErrorReconnectOverlayTest {
    @Test fun reconnectEventsHideDiagnosticsAndDoNotClaimAnAnswer() {
        val running = AgentOverlayState.Initial.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "running", 1000, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.RUNNING, running.phase)
        assertEquals(AgentOverlayStatus.RequestingModel, running.status)
        assertEquals("", running.detailText)
        val stopped = running.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "stopped", 1500, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.FINISHED, stopped.phase)
        assertEquals(AgentOverlayStatus.Stopped, stopped.status)
        assertEquals("", stopped.detailText)
        val failed = running.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "failed", 30000, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.FAILED, failed.phase)
        assertEquals("", failed.detailText)
    }
    @Test fun pausedControlSurvivesQueuedReconnectEventsAndResumeRemainsAvailable() {
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        for (status in listOf("running", "stopped", "succeeded", "failed")) {
            val event = AgentEvent.ErrorReconnectChanged(2, "disconnect", status, 1000)
            val state = paused.applyControlledEvent(event, isPaused = true)
            assertEquals(AgentOverlayPhase.PAUSED, state.phase)
            assertEquals(AgentOverlayStatus.Paused, state.status)
        }
        val retry = paused.applyControlledEvent(AgentEvent.ModelRetryScheduled(2, 1, 3, 2000, "network"), true)
        assertEquals(AgentOverlayPhase.PAUSED, retry.phase)
        assertEquals(AgentOverlayStatus.Paused, retry.status)
        val resumed = paused.applyControlledEvent(
            AgentEvent.ErrorReconnectChanged(2, "new-segment", "running", 1000), isPaused = false)
        assertEquals(AgentOverlayPhase.RUNNING, resumed.phase)
    }

}
