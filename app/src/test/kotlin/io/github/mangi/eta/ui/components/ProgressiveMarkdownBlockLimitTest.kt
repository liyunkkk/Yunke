package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressiveMarkdownBlockLimitTest {
    @Test fun firstStepTakesBlocksWithinBudget() {
        assertEquals(3, nextProgressiveBlockLimit(listOf(300, 300, 300, 300), 0, 1_000))
    }

    @Test fun oversizedBlockStillAdvancesByOne() {
        assertEquals(1, nextProgressiveBlockLimit(listOf(5_000, 10), 0, 1_000))
        assertEquals(2, nextProgressiveBlockLimit(listOf(5_000, 10), 1, 1_000))
    }

    @Test fun continuesFromCurrentAndNeverExceedsSize() {
        val lengths = listOf(400, 400, 400, 400, 400)
        assertEquals(4, nextProgressiveBlockLimit(lengths, 2, 800))
        assertEquals(5, nextProgressiveBlockLimit(lengths, 4, 800))
        assertEquals(5, nextProgressiveBlockLimit(lengths, 5, 800))
        assertEquals(0, nextProgressiveBlockLimit(emptyList(), 0, 800))
    }

    @Test fun everyDocumentFinishesInBoundedSteps() {
        val lengths = List(200) { if (it % 7 == 0) 3_000 else 120 }
        var limit = 0
        var steps = 0
        while (limit < lengths.size) {
            val next = nextProgressiveBlockLimit(lengths, limit, 800)
            check(next > limit)
            limit = next
            steps++
        }
        assertEquals(lengths.size, limit)
        check(steps <= lengths.size)
    }
}
