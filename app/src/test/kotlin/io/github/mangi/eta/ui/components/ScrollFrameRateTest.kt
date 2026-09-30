package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollFrameRateTest {
    @Test fun picksHighestSupportedRate() {
        assertEquals(120f, pickPeakFrameRate(listOf(60f, 90f, 120.00001f, 72f)), 0.01f)
    }

    @Test fun ignoresInvalidRates() {
        assertEquals(90f, pickPeakFrameRate(listOf(Float.NaN, -1f, 0f, 90f, Float.POSITIVE_INFINITY)), 0.01f)
    }

    @Test fun fallsBackWhenDisplayUnknown() {
        assertEquals(FALLBACK_PEAK_FRAME_RATE, pickPeakFrameRate(emptyList()), 0f)
    }
}
