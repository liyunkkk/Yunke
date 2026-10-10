package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticOutputQueueTest {
    @Test
    fun boundedQueueKeepsFifoAndReportsOnlyDiagnosticDrops() {
        val queue = BoundedDiagnosticOutputQueue(capacity = 2)
        assertTrue(queue.offer("a"))
        assertTrue(queue.offer("b"))
        assertFalse(queue.offer("c"))
        assertEquals(1, queue.dropped)
        assertEquals(listOf("a"), queue.drain(1))
        assertEquals(listOf("b"), queue.drainAll())
        assertTrue(queue.isEmpty())
    }
    @Test
    fun detailFloodCannotUseEssentialBudgetAndFifoIsRetained() {
        val queue = BoundedDiagnosticOutputQueue(capacity = 1, essentialCapacity = 2)
        assertTrue(queue.offer("detail"))
        assertFalse(queue.offer("dropped-detail"))
        assertTrue(queue.offer("window", essential = true))
        assertTrue(queue.offer("summary", essential = true))
        assertFalse(queue.offer("lost-window", essential = true))
        assertEquals(2L, queue.dropped)
        assertEquals(1L, queue.essentialDropped)
        assertEquals(listOf("detail", "window"), queue.drain(2))
        assertTrue(queue.offer("detail2"))
        assertTrue(queue.offer("window2", essential = true))
        assertEquals(listOf("summary", "detail2", "window2"), queue.drainAll())
        assertTrue(queue.isEmpty())
    }
}
