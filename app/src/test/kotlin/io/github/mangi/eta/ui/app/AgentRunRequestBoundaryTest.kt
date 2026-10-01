package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentRunRequestBoundaryTest {
    private val runId = "request-boundary"

    private fun tool(p: AgentRunMessageProjector, messages: List<AgentChatMessageUi>, hosted: Boolean,
                     run: String = runId, round: Int = 1): List<AgentChatMessageUi> {
        val done = p.finalizeTextRound(run, round, p.finalizeThinkingRound(run, round, messages))
        return if (hosted) p.startHostedTool(run, AgentEvent.HostedToolStarted(round, "tool", "search"), done)
        else p.startTool(run, AgentEvent.ToolStarted(round, "tool", "search", "{}"), done)
    }

    @Test fun mappedPauseContinuationRetainsVisibleReasoningAndOrderOnReplay() {
        val p = AgentRunMessageProjector { 1000L }
        fun replay(initial: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
            var m = p.resetForReplay(runId, initial)
            p.beginProviderRequest(runId, 1)
            m = p.appendTextDelta(runId, 1, 0, "part1 ", m)
            p.beginProviderRequest(runId, 1)
            m = p.appendTextDelta(runId, 1, 0, "part2 ", m)
            p.beginProviderRequest(runId, 1)
            m = p.startAssistantBlock(runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 1), m)
            m = p.appendReasoningDelta(runId, 1, 1, "visible-later", m)
            m = p.finalizeThinkingBlock(runId, 1, 1, null, m)
            m = p.appendTextDelta(runId, 1, 2, "part3 ", m)
            m = p.ensureCompletedThinking(runId, 1, "visible-later", m)
            return p.finalizeRun(runId, m)
        }
        val first = replay(emptyList())
        assertEquals(listOf("assistant-$runId-1-0", "$runId-thinking-1-1", "assistant-$runId-1-2"), first.map { it.id })
        assertEquals(listOf("part1 part2", "part3"), first.filterIsInstance<AgentMessageUi>().map { it.content })
        assertEquals(listOf("visible-later"), first.filterIsInstance<ThinkingMessageUi>().map { it.content })
        assertEquals(first, replay(first))
    }

    @Test fun nextRequestsToolCannotReleaseThePreviousRequestsPendingTail() {
        for (hosted in listOf(false, true)) {
            val p = AgentRunMessageProjector { 1000L }
            p.beginProviderRequest(runId, 1)
            var m = p.appendTextDelta(runId, 1, 0, "answer", emptyList())
            m = p.appendReasoningDelta(runId, 1, 1, "old late tail", m)
            p.beginProviderRequest(runId, 1)
            m = tool(p, m, hosted)
            assertTrue(m.none { it is ThinkingMessageUi })
        }
    }

    @Test fun confirmedLocalAndHostedToolReasoningSurvivesTheNextRequest() {
        for (hosted in listOf(false, true)) {
            val p = AgentRunMessageProjector { 1000L }
            p.beginProviderRequest(runId, 1)
            var m = p.appendTextDelta(runId, 1, 0, "commentary", emptyList())
            m = p.appendReasoningDelta(runId, 1, 1, "tool preparation", m)
            m = tool(p, m, hosted)
            assertEquals(listOf("tool preparation"), m.filterIsInstance<ThinkingMessageUi>().map { it.content })
            val before = m
            p.beginProviderRequest(runId, 1)
            m = p.appendReasoningDelta(runId, 1, 2, "new request", m)
            assertEquals(listOf("tool preparation", "new request"), m.filterIsInstance<ThinkingMessageUi>().map { it.content })
            assertEquals(before.map { it.id }, m.dropLast(1).map { it.id })
        }
    }

    @Test fun multipleTextDeltasNeverSubstituteForARequestBoundary() {
        for (index in listOf(9, 10, 11)) {
            val p = AgentRunMessageProjector { 1000L }
            p.beginProviderRequest(runId, 1)
            var m = p.appendTextDelta(runId, 1, 10, "final ", emptyList())
            m = p.appendTextDelta(runId, 1, 10, "answer", m)
            m = p.appendReasoningDelta(runId, 1, index, "late", m)
            assertTrue("index=$index", m.none { it is ThinkingMessageUi })
            assertTrue(p.finalizeRun(runId, m).none { it is ThinkingMessageUi })
        }
    }

    @Test fun aBoundaryCannotUnsealATerminalRun() {
        val p = AgentRunMessageProjector { 1000L }
        val m = p.finalizeRun(runId, p.appendTextDelta(runId, 1, 0, "answer", emptyList()))
        p.beginProviderRequest(runId, 1)
        assertTrue(p.isSealed(runId))
        assertEquals(m, p.appendReasoningDelta(runId, 1, 1, "late", m))
        assertEquals(m, p.appendTextDelta(runId, 1, 0, "late", m))
        assertEquals(m, p.startHostedTool(runId, AgentEvent.HostedToolStarted(1, "late", "search"), m))
    }

    @Test fun resettingOneRequestDoesNotDiscardOtherRunOrRoundCandidates() {
        val p = AgentRunMessageProjector { 1000L }
        var m: List<AgentChatMessageUi> = emptyList()
        for ((run, round) in listOf(runId to 1, runId to 2, "other" to 1)) {
            m = p.appendTextDelta(run, round, 0, "commentary", m)
            m = p.appendReasoningDelta(run, round, 1, "$run:$round", m)
        }
        p.beginProviderRequest(runId, 1)
        m = tool(p, m, true, runId, 1)
        m = tool(p, m, true, runId, 2)
        m = tool(p, m, true, "other", 1)
        assertEquals(listOf("$runId:2", "other:1"), m.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }
}
