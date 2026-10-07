package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunEventBudgetTest {
    @Test
    fun budgetStopsAfterAnEventWithoutDroppingTheRemainingFifo() {
        var now = 0L
        val queue = AgentRunEventBudget<Int>(budgetNs = 5L) { now }
        queue.offer(1)
        queue.offer(2)
        queue.offer(3)
        val first = mutableListOf<Int>()
        queue.drain(force = false) {
            first += it
            now += 5L
        }
        assertEquals(listOf(1), first)
        assertEquals(2, queue.size)
        val second = mutableListOf<Int>()
        queue.drain(force = true, consume = second::add)
        assertEquals(listOf(2, 3), second)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun failedConsumerKeepsTheHeadEventForALaterRetry() {
        val queue = AgentRunEventBudget<Int>(budgetNs = 1L)
        queue.offer(7)
        try {
            queue.drain(force = true) { error("transient") }
        } catch (_: IllegalStateException) {
            // The event must remain queued until the caller can retry it.
        }
        assertFalse(queue.isEmpty)
        val applied = mutableListOf<Int>()
        queue.drain(force = true, consume = applied::add)
        assertEquals(listOf(7), applied)
    }
    @Test fun sameFrameArrivalsAndDifferentRunsShareOneAccumulatedBudget() {
        var now = 0L
        val frame = AgentFrameEventBudget(5L)
        val first = AgentRunEventBudget<Int>(5L) { now }
        val second = AgentRunEventBudget<Int>(5L) { now }
        val applied = mutableListOf<Int>()
        frame.beginFrame(100L)
        first.offer(1)
        first.drain(false, frame) { applied += it; now += 3L }
        first.offer(2)
        first.drain(false, frame) { applied += it; now += 2L }
        second.offer(3)
        second.drain(false, frame) { applied += it }
        assertEquals(listOf(1, 2), applied)
        assertEquals(1, second.size)
        frame.beginFrame(100L)
        assertTrue(frame.exhausted)
        frame.beginFrame(200L)
        second.drain(false, frame) { applied += it }
        assertEquals(listOf(1, 2, 3), applied)
    }

    @Test fun oneSlowEventAndConsumerFailureAreChargedWithoutLosingTheHead() {
        var now = 0L
        val frame = AgentFrameEventBudget(5L)
        val queue = AgentRunEventBudget<Int>(5L) { now }
        queue.offer(1)
        queue.offer(2)
        try {
            queue.drain(false, frame) { now += 9L; error("failed") }
            error("expected failure")
        } catch (_: IllegalStateException) { }
        assertTrue(frame.exhausted)
        assertEquals(2, queue.size)
        val applied = mutableListOf<Int>()
        queue.drain(true, frame, consume = applied::add)
        assertEquals(listOf(1, 2), applied)
        assertTrue(queue.isEmpty)
    }

    @Test fun singleEventTurnsPermitRoundRobinFifoWithoutDroppingBusyRunEvents() {
        var now = 0L
        val frame = AgentFrameEventBudget(100L)
        val first = AgentRunEventBudget<Int>(100L) { now }
        val second = AgentRunEventBudget<Int>(100L) { now }
        first.offer(1); first.offer(3); second.offer(2); second.offer(4)
        val applied = mutableListOf<Int>()
        repeat(2) {
            first.drain(false, frame, maxEvents = 1) { applied += it; now++ }
            second.drain(false, frame, maxEvents = 1) { applied += it; now++ }
        }
        assertEquals(listOf(1, 2, 3, 4), applied)
    }
    @Test fun forcedBoundaryPreservesTextAndUsageFifoWithoutResettingSharedBudget() {
        var now = 0L
        val frame = AgentFrameEventBudget(5L)
        val queue = AgentRunEventBudget<String>(5L) { now }
        val applied = mutableListOf<String>()
        frame.beginFrame(100L)
        listOf("你", "好🙂", "usage", "end").forEach(queue::offer)
        queue.drain(false, frame) { applied += it; now += 5L }
        assertTrue(frame.exhausted)
        queue.drain(true, frame) { applied += it; now++ }
        assertEquals(listOf("你", "好🙂", "usage", "end"), applied)
        assertTrue(queue.isEmpty)
        assertTrue(frame.exhausted)
        queue.offer("next")
        queue.drain(false, frame) { applied += it }
        assertEquals(1, queue.size)
        frame.beginFrame(200L)
        queue.drain(false, frame) { applied += it }
        assertEquals("next", applied.last())
    }
    @Test fun wallTimeScopesIncludeSchedulerOverheadButNotIdleOrNestedDoubleCharging() {
        var now = 0L
        val frame = AgentFrameEventBudget(10L) { now }
        val queue = AgentRunEventBudget<Int>(10L) { now }
        val applied = mutableListOf<Int>()
        frame.beginFrame(100L)
        now += 1_000L // Idle time outside a work scope is not charged.
        assertFalse(frame.exhausted)
        frame.measureWork {
            now += 3L // Scheduling and queue bookkeeping.
            frame.measureWork { queue.offer(1); queue.drain(false, frame) { applied += it; now += 2L } }
        }
        assertFalse(frame.exhausted) // Five, not seven, nanoseconds were charged.
        now += 1_000L
        frame.measureWork {
            now += 5L // Even a stream of zero-cost/discarded events must yield.
            assertTrue(frame.exhausted)
            queue.offer(2)
            queue.drain(false, frame, consume = applied::add)
        }
        assertEquals(listOf(1), applied)
        assertEquals(1, queue.size)
        frame.beginFrame(200L)
        try {
            frame.measureWork { now += 10L; error("failed work") }
        } catch (_: IllegalStateException) { }
        assertTrue(frame.exhausted)
    }

}
