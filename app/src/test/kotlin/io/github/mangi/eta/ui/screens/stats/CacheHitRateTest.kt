package io.github.mangi.eta.ui.screens.stats

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheHitRateTest {
    @Test fun usesInputTokensAsDenominator() {
        assertEquals("84.4%", formatCacheHitRate(5_360_000, 6_350_000, Locale.US))
        assertEquals("83.7%", formatCacheHitRate(5_600_000, 6_690_000, Locale.US))
    }
    @Test fun handlesEmptyZeroAndLargeCounts() {
        assertEquals("—", formatCacheHitRate(0, 0, Locale.US))
        assertEquals("0.0%", formatCacheHitRate(0, 100, Locale.US))
        assertEquals("100.0%", formatCacheHitRate(Long.MAX_VALUE, Long.MAX_VALUE, Locale.US))
    }
    @Test fun inconsistentDataIsClampedInsteadOfShowingImpossibleRates() {
        // cachedTokens is always a subset of inputTokens once usage is normalized.
        assertEquals("100.0%", formatCacheHitRate(500, 100, Locale.US))
        assertEquals("0.0%", formatCacheHitRate(-50, 100, Locale.US))
    }
    @Test fun aggregateIsWeightedByTokenCounts() {
        assertEquals("10.0%", formatCacheHitRate(100 + 0, 100 + 900, Locale.US))
    }
}
