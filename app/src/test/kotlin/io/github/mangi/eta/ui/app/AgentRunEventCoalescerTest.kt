package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunEventCoalescerTest {
    @Test
    fun coalescesAdjacentDeltasFromTheSameBlock() {
        val coalescer = AgentRunEventCoalescer()

        assertNull(coalescer.append("run-1", delta(index = 0, text = "你")))
        assertNull(coalescer.append("run-1", delta(index = 0, text = "好")))

        assertEquals(
            delta(index = 0, text = "你好", chars = 2),
            coalescer.flush("run-1"),
        )
    }

    @Test
    fun flushesThePreviousBlockBeforeBufferingANewOne() {
        val coalescer = AgentRunEventCoalescer()
        val text = delta(index = 0, text = "回答")
        val thinking = delta(
            index = 1,
            text = "分析",
            kind = AgentEvent.AssistantBlockKind.THINKING,
        )

        assertNull(coalescer.append("run-1", text))
        assertEquals(text, coalescer.append("run-1", thinking))
        assertEquals(thinking, coalescer.flush("run-1"))
    }

    @Test
    fun keepsRunsIndependent() {
        val coalescer = AgentRunEventCoalescer()
        val first = delta(index = 0, text = "A")
        val second = delta(index = 0, text = "B")

        coalescer.append("run-1", first)
        coalescer.append("run-2", second)

        assertEquals(first, coalescer.flush("run-1"))
        assertEquals(second, coalescer.flush("run-2"))
    }

    @Test
    fun frameWindowCoalescingMatchesSequentialMessageProjectionAcrossBlockAndRoundBoundaries() {
        val events = listOf(
            delta(0, "想", kind = AgentEvent.AssistantBlockKind.THINKING),
            delta(0, "一想", kind = AgentEvent.AssistantBlockKind.THINKING),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.THINKING, 0, contentChars = 3),
            delta(1, "hello "), delta(1, "world"),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TEXT, 1, contentChars = 11, replacementContent = "hello world!"),
            delta(0, "下一轮").copy(round = 2), delta(0, "答案").copy(round = 2),
        )
        val coalescer = AgentRunEventCoalescer()
        val merged = mutableListOf<AgentEvent>()
        events.forEach { event ->
            if (event is AgentEvent.AssistantBlockDelta) {
                coalescer.append("r", event)?.let(merged::add)
            } else {
                coalescer.flush("r")?.let(merged::add)
                merged += event
            }
        }
        coalescer.flush("r")?.let(merged::add)
        assertEquals(project(events), project(merged))
        assertEquals(3, merged.filterIsInstance<AgentEvent.AssistantBlockDelta>().size)
        assertEquals(events.filterIsInstance<AgentEvent.AssistantBlockDelta>().sumOf { it.deltaChars },
            merged.filterIsInstance<AgentEvent.AssistantBlockDelta>().sumOf { it.deltaChars })
    }

    @Test
    fun errorAndEndBoundariesFlushAllEarlierDeltasWithoutWaitingForTheFrame() {
        val coalescer = AgentRunEventCoalescer()
        val delivered = mutableListOf<AgentEvent>()
        val first = delta(0, "part ")
        val second = delta(0, "two")
        coalescer.append("r", first)
        coalescer.append("r", second)
        val error = AgentEvent.RunFailed("failure")
        coalescer.flush("r")?.let(delivered::add)
        delivered += error
        assertEquals(listOf(delta(0, "part two"), error), delivered)
        assertNull(coalescer.flush("r")) // A cancelled/stale frame cannot duplicate output.
        val sequential = project(listOf(first, second))
        assertEquals(sequential, project(delivered.dropLast(1)))
        val sequentialProjector = AgentRunMessageProjector { 1_000L }
        val mergedProjector = AgentRunMessageProjector { 1_000L }
        assertEquals(sequentialProjector.terminalFailure("r", "failure", sequential),
            mergedProjector.terminalFailure("r", "failure", project(delivered.dropLast(1))))
    }

    private fun project(events: List<AgentEvent>): List<io.github.mangi.eta.ui.model.AgentChatMessageUi> {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = emptyList<io.github.mangi.eta.ui.model.AgentChatMessageUi>()
        events.forEach { event ->
            messages = when (event) {
                is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT -> projector.appendTextDelta("r", event.round, event.index, event.delta, messages)
                    AgentEvent.AssistantBlockKind.THINKING -> projector.appendReasoningDelta("r", event.round, event.index, event.delta, messages)
                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
                is AgentEvent.AssistantBlockEnd -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT -> projector.finalizeTextBlock("r", event.round, event.index, event.replacementContent, messages)
                    AgentEvent.AssistantBlockKind.THINKING -> projector.finalizeThinkingBlock("r", event.round, event.index, event.replacementContent, messages)
                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
                else -> messages
            }
        }
        return messages
    }

    private fun delta(
        index: Int,
        text: String,
        chars: Int = text.length,
        kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT,
    ) = AgentEvent.AssistantBlockDelta(
        round = 1,
        kind = kind,
        index = index,
        deltaChars = chars,
        delta = text,
    )
}
