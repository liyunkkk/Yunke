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

    @Test fun tapFrameLeavesAnOversizedFirstBlockForTheNextFrame() {
        // 点击那一帧：首块超预算时不纳入，交给下一帧。
        assertEquals(0, nextProgressiveBlockLimit(listOf(5_000, 10), 0, 240, mustAdvance = false))
        // 首块在预算内照常纳入，后续超预算的块停下。
        assertEquals(2, nextProgressiveBlockLimit(listOf(100, 120, 900), 0, 240, mustAdvance = false))
        // 下一帧恢复“至少前进一块”，超长块也能完成。
        assertEquals(1, nextProgressiveBlockLimit(listOf(5_000, 10), 0, 400))
    }

    @Test fun everyDocumentFinishesInBoundedSteps() {
        val lengths = List(200) { if (it % 7 == 0) 3_000 else 120 }
        var limit = nextProgressiveBlockLimit(lengths, 0, 240, mustAdvance = false)
        var steps = 0
        while (limit < lengths.size) {
            val next = nextProgressiveBlockLimit(lengths, limit, 400)
            check(next > limit)
            limit = next
            steps++
        }
        assertEquals(lengths.size, limit)
        check(steps <= lengths.size)
    }
}
