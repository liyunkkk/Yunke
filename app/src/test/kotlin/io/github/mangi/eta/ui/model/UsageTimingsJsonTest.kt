package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 耗时列的紧凑 JSON：往返、空值、损坏、大毫秒值。 */
class UsageTimingsJsonTest {
    private val full = TokenUsageUi(
        contextTokens = 32_000,
        inputTokens = 1_500,
        outputTokens = 120,
        reasoningTokens = 40,
        cachedTokens = 1_000,
        stepCount = 3,
        llmMs = 8_400L,
        toolMs = 2_100L,
        ttftMs = 1_200L,
        ttftSteps = 3,
        decodeMs = 6_000L,
        decodeTokens = 120,
    )

    @Test fun roundTripKeepsEveryTimingFieldAndDropsTokens() {
        val encoded = UsageTimingsJson.encode(full)
        assertTrue(encoded.startsWith("{"))
        // token 不重复存：列里只有耗时键。
        assertFalse(encoded.contains("input"))
        val decoded = requireNotNull(UsageTimingsJson.decode(encoded))
        assertTrue(decoded.stepCount == 3)
        assertEquals(8_400L, decoded.llmMs)
        assertEquals(2_100L, decoded.toolMs)
        assertEquals(1_200L, decoded.ttftMs)
        assertTrue(decoded.ttftSteps == 3)
        assertEquals(6_000L, decoded.decodeMs)
        assertTrue(decoded.decodeTokens == 120)
        // 从耗时列读回时不带 token：token 由既有列提供。
        assertNull(decoded.inputTokens)
        assertNull(decoded.outputTokens)
        assertNull(decoded.contextTokens)
        assertTrue(decoded.hasPerformanceStats)
    }

    @Test fun tokenOnlyUsageEncodesToEmptyString() {
        val tokenOnly = TokenUsageUi(contextTokens = 1_000, inputTokens = 900, outputTokens = 12)
        assertEquals("", UsageTimingsJson.encode(tokenOnly))
        assertEquals("", UsageTimingsJson.encode(null))
        assertEquals("", UsageTimingsJson.encode(TokenUsageUi()))
    }

    @Test fun blankAndCorruptPayloadsDecodeToNull() {
        listOf(null, "", "   ", "{}", "null", "not-json", "[1,2,3]", "{\"s\":", "{\"zz\":1}")
            .forEach { raw -> assertNull("raw=$raw", UsageTimingsJson.decode(raw)) }
    }

    @Test fun partialTimingsRoundTrip() {
        val partial = TokenUsageUi(llmMs = 1_234L)
        val decoded = requireNotNull(UsageTimingsJson.decode(UsageTimingsJson.encode(partial)))
        assertEquals(1_234L, decoded.llmMs)
        assertNull(decoded.toolMs)
        assertNull(decoded.decodeMs)
        assertNull(decoded.stepCount)
    }

    @Test fun largeMillisecondValuesKeepPrecision() {
        val huge = TokenUsageUi(llmMs = 5_000_000_000L, toolMs = 4_294_967_296L, decodeMs = 9_007_199_254_740_991L)
        val decoded = requireNotNull(UsageTimingsJson.decode(UsageTimingsJson.encode(huge)))
        assertEquals(5_000_000_000L, decoded.llmMs)
        assertEquals(4_294_967_296L, decoded.toolMs)
        assertEquals(9_007_199_254_740_991L, decoded.decodeMs)
    }

    @Test fun stringAndDoubleNumbersAreTolerated() {
        // 手工改过的行或跨版本格式：数字写成了字符串/浮点也不要炸。
        val decoded = requireNotNull(UsageTimingsJson.decode("""{"s":"3","l":"8400","d":6000.0}"""))
        assertTrue(decoded.stepCount == 3)
        assertEquals(8_400L, decoded.llmMs)
        assertEquals(6_000L, decoded.decodeMs)
    }

    @Test fun tokenFieldsOnlyAreNotTimingFields() {
        assertFalse(TokenUsageUi(inputTokens = 10, outputTokens = 2).hasTimingFields())
        assertTrue(TokenUsageUi(stepCount = 1).hasTimingFields())
        assertTrue(TokenUsageUi(decodeMs = 1L).hasTimingFields())
    }
}
