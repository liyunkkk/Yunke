package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunDeltaFrameWaitTest {
    @Test fun manyDeltasBeforeTheSameFrameProduceOneProjection() = runBlocking {
        val coalescer = AgentRunEventCoalescer()
        var frame: () -> Unit = {}
        var projections = 0
        var output: AgentEvent.AssistantBlockDelta? = null
        val flush = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitRunDeltaFrame { ready -> frame = ready; { } }
            output = coalescer.flush("r")
            projections++
        }
        repeat(16) { coalescer.append("r", delta("$it,")) }
        assertEquals(0, projections)
        frame()
        flush.join()
        frame() // A duplicate/stale frame notification cannot project twice.
        assertEquals(1, projections)
        assertEquals((0 until 16).joinToString("") { "$it," }, output?.delta)
    }

    @Test fun terminalBoundaryCancelsTheFrameAndFlushesTheEarlierOutputExactlyOnce() = runBlocking {
        val coalescer = AgentRunEventCoalescer()
        var frame: () -> Unit = {}
        var removals = 0
        var timerProjections = 0
        val flush = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitRunDeltaFrame { ready -> frame = ready; { removals++ } }
            timerProjections++
        }
        coalescer.append("r", delta("before terminal"))
        flush.cancel()
        val beforeTerminal = coalescer.flush("r")
        // The non-delta/end/error path applies this before applying the terminal event.
        assertEquals("before terminal", beforeTerminal?.delta)
        frame()
        flush.join()
        assertEquals(1, removals)
        assertEquals(0, timerProjections)
        assertNull(coalescer.flush("r"))
    }

    @Test fun absentFrameTimeoutRemovesTheCallbackAndAllowsFallbackProgress() = runBlocking {
        var removals = 0
        val result = withTimeoutOrNull(10L) {
            awaitRunDeltaFrame { { removals++ } }
        }
        assertNull(result)
        assertEquals(1, removals)
    }

    private fun delta(text: String) = AgentEvent.AssistantBlockDelta(
        1, AgentEvent.AssistantBlockKind.TEXT, 0, text.length, text,
    )
}
