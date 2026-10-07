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
}
