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
}
