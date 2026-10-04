package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompletionMarkerTest {
    @Test fun successfulBackgroundOutputIsMarked() {
        val result = AgentRuntimeWire.RunResult("run", true, "answer")
        assertTrue(ConversationCompletionMarker.shouldMark(result, isSelected = false))
        assertFalse(ConversationCompletionMarker.shouldMark(result, isSelected = true))
        assertFalse(ConversationCompletionMarker.shouldMark(result, isSelected = false, alreadyApplied = true))
        assertFalse(ConversationCompletionMarker.shouldMark(result, isSelected = false, wasStopped = true))
    }

    @Test fun failureAndEmptyResultsAreNotMarked() {
        assertFalse(ConversationCompletionMarker.shouldMark(
            AgentRuntimeWire.RunResult("run", true, ""), isSelected = false,
        ))
        assertFalse(ConversationCompletionMarker.shouldMark(
            AgentRuntimeWire.RunResult("run", false, "partial", "failed"), isSelected = false,
        ))
        assertFalse(ConversationCompletionMarker.shouldMark(
            AgentRuntimeWire.RunResult("run", true, "answer", "error"), isSelected = false,
        ))
        assertTrue(ConversationCompletionMarker.shouldMark(
            AgentRuntimeWire.RunResult("run", true, "", virtualDeliveryCompleted = true), isSelected = false,
        ))
    }
}
