package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class AgentIncrementalListTest {
    @Test fun snapshotsAreIsolatedFromMutableInputsAtEveryChunkBoundary() {
        for (size in listOf(0, 1, 31, 32, 33, 64, 65, 4096)) {
            val input = MutableList(size) { "value-$it" }
            val expected = input.toList()
            val snapshot = input.incrementalSnapshot()
            input.clear()
            assertEquals(expected, snapshot)
            assertEquals(expected.hashCode(), snapshot.hashCode())
            assertEquals(expected, snapshot.toList())
            assertEquals(expected, snapshot.listIterator().asSequence().toList())
            assertEquals(expected.take(4), snapshot.subList(0, minOf(4, size)))
        }
    }

    @Test fun repeatedReplacementSharesUnchangedChunksWithoutRetainingStrongPredecessors() {
        val original = List(4097) { "value-$it" }.incrementalSnapshot()
        val oldHash = original.hashCode()
        val next = original.replacing(original.lastIndex, "new")
        assertEquals(original.lastIndex, next.singleReplacementFrom(original))
        assertNull(next.singleReplacementFrom(original.toList()))
        assertEquals("value-4096", original.last())
        assertEquals("new", next.last())
        assertEquals(oldHash, original.hashCode())
        val chunksField = AgentIncrementalList::class.java.getDeclaredField("chunks").apply { isAccessible = true }
        val before = chunksField.get(original) as List<*>
        val after = chunksField.get(next) as List<*>
        assertEquals(before.size, after.size)
        for (index in 0 until before.lastIndex) assertSame(before[index], after[index])
        assertNotSame(before.last(), after.last())
        assertSame(next, next.incrementalSnapshot())
        var current = next
        repeat(1000) { current = current.replacing(current.lastIndex, "tail-$it") }
        assertEquals("new", next.last())
        assertEquals("tail-999", current.last())
        assertNull(current.singleReplacementFrom(original))
        assertTrue(AgentIncrementalList::class.java.declaredFields.none { it.type == AgentIncrementalList::class.java })
    }

    @Test fun structuralEqualityAndHashRemainSymmetricWithOrdinaryLists() {
        val ordinary = listOf("a", "b", "c")
        val initial = ordinary.incrementalSnapshot()
        val updated = initial.replacing(1, "B")
        assertEquals(ordinary, initial)
        assertEquals(initial, ordinary)
        assertNotEquals(initial, updated)
        assertEquals(listOf("a", "B", "c"), updated)
        assertEquals(listOf("a", "B", "c").hashCode(), updated.hashCode())
    }
}
