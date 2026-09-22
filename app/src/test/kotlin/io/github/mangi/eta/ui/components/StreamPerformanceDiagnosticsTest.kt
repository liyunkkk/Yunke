package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPerformanceDiagnosticsTest {
    @Test fun unaccountedIsTotalMinusNonOverlappingParts() {
        assertEquals(10L, frameUnaccountedNs(
            total = 100, unknown = 40, input = 5, animation = 5,
            layout = 10, draw = 10, sync = 10, command = 5, swap = 5,
        ))
    }

    @Test fun gpuIsNotSubtractedFromTheTotal() {
        assertEquals(0L, frameUnaccountedNs(
            total = 80, unknown = 80, input = 0, animation = 0,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }

    @Test fun overlapIsNegativeWhenPartsExceedTotal() {
        assertEquals(-3L, frameUnaccountedNs(
            total = 7, unknown = 4, input = 3, animation = 3,
            layout = 0, draw = 0, sync = 0, command = 0, swap = 0,
        ))
    }
}
