package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class AgentSnapshotTransformTest {
    private data class Payload(val id: Int, val content: String)
    @Test fun zeroSingleAndMultipleChangesKeepExactValuesAndHonestCertificates() {
        for (size in listOf(0, 1, 31, 32, 33, 64, 65, 4096)) {
            val source = List(size) { Payload(it, "old-$it") }.incrementalSnapshot()
            val original = source.toList()
            val hash = source.hashCode()
            var reads = 0
            assertSame(source, source.mapPreservingSnapshot { reads++; it.copy() })
            assertEquals(size, reads)
            if (size > 0) {
                val index = size / 2
                val changed = source.mapPreservingSnapshot { if (it.id == index) it.copy(content = "new") else it }
                assertEquals(source.map { if (it.id == index) it.copy(content = "new") else it }, changed)
                assertNull((changed as AgentIncrementalList<*>).singleReplacementFrom(source))
                assertEquals(original, source)
                assertEquals(hash, source.hashCode())
            }
            if (size > 1) {
                val changed = source.mapPreservingSnapshot { if (it.id == 0 || it.id == size - 1) it.copy(content = "new") else it }
                assertEquals(source.map { if (it.id == 0 || it.id == size - 1) it.copy(content = "new") else it }, changed)
                assertNull((changed as AgentIncrementalList<*>).singleReplacementFrom(source))
            }
        }
    }

    @Test fun droppingTerminalHintDoesNotMutateTheCertifiedStreamingSnapshot() {
        val before = listOf("old", "untouched").incrementalSnapshot()
        val delta = before.replacing(0, "new")
        val terminal = delta.withoutReplacementHint()
        assertEquals(delta, terminal)
        assertEquals(0, delta.singleReplacementFrom(before))
        assertNull(terminal.singleReplacementFrom(before))
        assertSame(terminal, terminal.withoutReplacementHint())
        assertEquals(listOf("old", "untouched"), before)
    }

    @Test fun nullablePayloadAndMutableInputFreezeBeforeCertifiedPublication() {
        val input = mutableListOf<String?>("one", null, "three")
        val snapshot = input.mapPreservingSnapshot { if (it == "one") null else it }
        assertEquals(listOf(null, null, "three"), snapshot)
        input[2] = "edited"
        assertEquals(listOf(null, null, "three"), snapshot)
        assertNull((snapshot as AgentIncrementalList<*>).singleReplacementFrom(input))
    }
}
