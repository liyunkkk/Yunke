package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStopEventGateTest {
    @Test fun terminalEventsCanUnlockAStoppingRun() {
        assertTrue(RunStopEventGate.isRunTerminal(AgentEvent.RunFinished(round = 3, contentChars = 10)))
        assertTrue(RunStopEventGate.isRunTerminal(AgentEvent.RunFailed(reason = "已停止")))
    }

    @Test fun nonTerminalEventsStillCannotUnlock() {
        val nonTerminal = listOf(
            AgentEvent.RoundStarted(round = 1, messageCount = 2),
            AgentEvent.ProviderRequestStarted(round = 1),
            AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"),
            AgentEvent.ToolStarted(round = 1, toolCallId = "t1", name = "shell", argsPreview = "{}"),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "t1",
                name = "shell",
                resultSummary = "ok",
                imageCount = 0,
                imageBytes = 0,
            ),
            AgentEvent.UsageReceived(round = 1, usage = AgentTokenUsage(inputTokens = 1)),
            AgentEvent.ContextCompacted(round = 1, applied = true, originalCount = 5, compactedCount = 2),
        )
        for (event in nonTerminal) {
            assertFalse(
                "${event::class.simpleName} must not unlock a stopping run",
                RunStopEventGate.isRunTerminal(event),
            )
        }
    }

    @Test fun streamingIncrementsStayBlockedWhileStopping() {
        val streaming = listOf(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                deltaChars = 2,
                delta = "继续",
            ),
            AgentEvent.AssistantBlockStart(round = 1, kind = AgentEvent.AssistantBlockKind.TEXT, index = 0),
            AgentEvent.AssistantBlockEnd(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                contentChars = 2,
            ),
            AgentEvent.AssistantReceived(round = 1, contentChars = 2, reasoningContent = "", toolNames = emptyList()),
            AgentEvent.ProviderResponseStarted(round = 1, httpCode = 200),
        )
        for (event in streaming) {
            assertTrue(
                "${event::class.simpleName} must stay blocked while stopping",
                RunStopEventGate.isStreamingIncrement(event),
            )
        }
    }

    @Test fun terminalAndControlEventsAreNotStreamingIncrements() {
        assertFalse(RunStopEventGate.isStreamingIncrement(AgentEvent.RunFinished(round = 1, contentChars = 0)))
        assertFalse(RunStopEventGate.isStreamingIncrement(AgentEvent.RunFailed(reason = "boom")))
        assertFalse(
            RunStopEventGate.isStreamingIncrement(
                AgentEvent.ContextCompacted(round = 1, applied = true, originalCount = 3, compactedCount = 1),
            ),
        )
    }
}
