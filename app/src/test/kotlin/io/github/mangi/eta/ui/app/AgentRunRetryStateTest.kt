package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class AgentRunRetryStateTest {
    @Test fun waitIsScopedToRunAndEndsWhenNextRequestStarts() {
        val state = AgentRunRetryState()
        state.accept("old", AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"))
        assertTrue(state.isWaiting("old"))
        assertFalse(state.isWaiting("new"))
        state.accept("old", AgentEvent.ProviderRequestStarted(2))
        assertFalse(state.isWaiting("old"))
        state.accept("new", AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"))
        state.clear("new")
        assertFalse(state.isWaiting("new"))
    }
    @Test fun stoppedReconnectClearsRetryWaitAndNewSegmentResumesOnlyItsRun() {
        val state = AgentRunRetryState()
        state.accept("run", AgentEvent.ErrorReconnectChanged(1, "old", "running", 0))
        state.accept("run", AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"))
        state.accept("other", AgentEvent.ErrorReconnectChanged(1, "other", "running", 0))
        state.accept("run", AgentEvent.ErrorReconnectChanged(1, "old", "stopped", 1000))
        assertFalse(state.isWaiting("run"))
        assertFalse(state.isReconnecting("run"))
        assertTrue(state.isReconnecting("other"))
        state.accept("run", AgentEvent.ErrorReconnectChanged(1, "new", "running", 1000))
        assertTrue(state.isReconnecting("run"))
    }

    @Test fun lateRunningCannotReviveATerminatedReconnectSegment() {
        for (terminal in listOf("stopped", "succeeded", "failed")) {
            val state = AgentRunRetryState()
            state.accept("run", AgentEvent.ErrorReconnectChanged(1, "old", "running", 0))
            state.accept("run", AgentEvent.ErrorReconnectChanged(1, "old", terminal, 1000))
            state.accept("run", AgentEvent.ErrorReconnectChanged(1, "old", "running", 500))
            assertFalse(state.isReconnecting("run"))
            state.accept("run", AgentEvent.ErrorReconnectChanged(1, "new", "running", 1000))
            assertTrue(state.isReconnecting("run"))
            state.clear("run")
            assertFalse(state.isReconnecting("run"))
        }
    }

}
