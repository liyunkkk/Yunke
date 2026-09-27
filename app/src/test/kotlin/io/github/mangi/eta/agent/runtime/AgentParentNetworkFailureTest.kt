package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParentNetworkFailureTest {
    @Test fun exhaustedRetryWrapperIsFinalNetworkFailure() {
        val connection = AgentModelFailure("MODEL_CONNECTION_FAILED", true, "connection")
        val exhausted = AgentModelFailure(connection.code, false, "exhausted", connection)
        assertTrue(AgentParentNetworkFailure.isFinal(exhausted, cancelled = false))
    }

    @Test fun cancellationNeverPromptsEvenIfLastAttemptWasNetworkFailure() {
        val timeout = AgentModelFailure("MODEL_TIMEOUT", true, "timeout")
        assertFalse(AgentParentNetworkFailure.isFinal(timeout, cancelled = true))
        assertFalse(AgentParentNetworkFailure.isFinal(InterruptedException(), cancelled = false))
    }

    @Test fun finalPartialStreamFailureDoesNotRequireReplayingRequest() {
        assertTrue(AgentParentNetworkFailure.isFinal(
            AgentModelFailure("STREAM_INCOMPLETE", true, "incomplete"), cancelled = false,
        ))
    }

    @Test fun permanentProviderAndToolFailuresAreNotNetworkFailures() {
        for (code in listOf("HTTP_401", "HTTP_400", "HTTP_429", "CONTEXT_WINDOW_EXCEEDED", "TOOL_ENVELOPE_REJECTED")) {
            assertFalse(code, AgentParentNetworkFailure.isFinal(
                AgentModelFailure(code, false, "permanent"), cancelled = false,
            ))
        }
        assertFalse(AgentParentNetworkFailure.isFinal(IllegalStateException("tool failed"), cancelled = false))
    }
}
