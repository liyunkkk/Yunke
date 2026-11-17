package io.github.mangi.eta.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 统计胶囊的纯格式化逻辑（移植自 Eta，只保留 Miuix 路径后的同一套口径）。 */
class ChatStatsFormatTest {
    @Test fun durationKeepsOneDecimalBelowAMinute() {
        assertEquals("0秒", formatStatDuration(0L))
        assertEquals("0.4秒", formatStatDuration(400L))
        assertEquals("1秒", formatStatDuration(1_000L))
        assertEquals("1.5秒", formatStatDuration(1_500L))
        assertEquals("59.9秒", formatStatDuration(59_900L))
    }

    @Test fun durationSwitchesToMinutesAndSeconds() {
        assertEquals("1分0秒", formatStatDuration(60_000L))
        assertEquals("1分30秒", formatStatDuration(90_000L))
        assertEquals("2分5秒", formatStatDuration(125_000L))
    }

    @Test fun negativeDurationClampsToZero() {
        assertEquals("0秒", formatStatDuration(-5_000L))
    }

    @Test fun tokensPerSecondRoundsCoarseAboveTen() {
        assertEquals("0", formatTokensPerSecond(0.0))
        assertEquals("7", formatTokensPerSecond(7.0))
        assertEquals("7.3", formatTokensPerSecond(7.34))
        assertEquals("9.9", formatTokensPerSecond(9.94))
        assertEquals("10", formatTokensPerSecond(10.0))
        assertEquals("42", formatTokensPerSecond(41.6))
        assertEquals("0", formatTokensPerSecond(-3.0))
    }

    @Test fun cacheHitPercentIsNullWithoutAPromptOrReads() {
        assertNull(formatCacheHitPercent(0, 0))
        assertNull(formatCacheHitPercent(10, 0))
        assertNull(formatCacheHitPercent(-1, 100))
    }

    @Test fun cacheHitPercentReportsWholeAndFractionalHits() {
        assertEquals("100", formatCacheHitPercent(1_000, 1_000))
        assertEquals("100", formatCacheHitPercent(1_200, 1_000))
        assertEquals("50", formatCacheHitPercent(500, 1_000))
        assertEquals("67", formatCacheHitPercent(666, 1_000))
        // 四舍五入会到 100 但实际不足：退成一位小数，最多报 99.9。
        assertEquals("99.9", formatCacheHitPercent(999, 1_000))
        assertEquals("99.9", formatCacheHitPercent(99_999, 100_000))
    }

    @Test fun exactTokenCountUsesGrouping() {
        assertEquals("1,234", formatExactTokenCount(1_234, Locale.US))
        assertEquals("0", formatExactTokenCount(-1, Locale.US))
    }
}
