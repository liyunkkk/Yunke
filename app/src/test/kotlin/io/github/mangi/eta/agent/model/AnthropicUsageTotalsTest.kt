package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicUsageTotalsTest {

    @Test
    fun promptTokensAddsBothCacheSubsetsToFreshInput() {
        assertEquals(
            190_000,
            AnthropicUsageTotals.promptTokens(
                inputTokens = 4_000,
                cacheReadTokens = 180_000,
                cacheCreationTokens = 6_000,
            ),
        )
    }

    @Test
    fun promptTokensKeepsPlainInputWhenNoCacheIsReported() {
        assertEquals(
            4_000,
            AnthropicUsageTotals.promptTokens(4_000, cacheReadTokens = null, cacheCreationTokens = null),
        )
    }

    @Test
    fun promptTokensStaysNullWhenNothingIsReported() {
        assertNull(AnthropicUsageTotals.promptTokens(null, null, null))
    }

    @Test
    fun promptTokensIgnoresNegativeFields() {
        assertEquals(
            5_000,
            AnthropicUsageTotals.promptTokens(-1, cacheReadTokens = 5_000, cacheCreationTokens = -2),
        )
    }

    @Test
    fun cachedTokensIncludesCreationAndReads() {
        assertEquals(186_000, AnthropicUsageTotals.cachedTokens(180_000, 6_000))
        assertEquals(180_000, AnthropicUsageTotals.cachedTokens(180_000))
        assertNull(AnthropicUsageTotals.cachedTokens(null))
    }

    @Test
    fun parseNormalisesCacheHitUsageToTotalPrompt() {
        val raw = mapOf(
            "input_tokens" to 4_000,
            "cache_read_input_tokens" to 180_000,
            "cache_creation_input_tokens" to 6_000,
            "output_tokens" to 512,
        )
        val usage = requireNotNull(AnthropicUsageTotals.parse { raw[it] })
        assertEquals(190_000, usage.inputTokens)
        assertEquals(186_000, usage.cachedTokens)
        // Only the uncached prefix remains outside the cache bucket.
        assertEquals(4_000, usage.inputTokens!! - usage.cachedTokens!!)
        assertEquals(512, usage.outputTokens)
        assertNull(usage.contextTokens)
        // Occupancy must reflect the whole prompt, not just the uncached prefix.
        assertEquals(190_000, usage.occupancyTokens())
    }

    @Test
    fun parseKeepsCachedTokensAsSubsetOfInputTokens() {
        val raw = mapOf("input_tokens" to 1_200, "cache_read_input_tokens" to 96_000)
        val usage = requireNotNull(AnthropicUsageTotals.parse { raw[it] })
        assertEquals(97_200, usage.inputTokens)
        assertEquals(96_000, usage.cachedTokens)
        assertEquals(1_200, usage.inputTokens!! - usage.cachedTokens!!)
    }

    @Test
    fun parseReadsThinkingOutputTokens() {
        val raw = mapOf("input_tokens" to 10, "output_tokens" to 20, "thinking_output_tokens" to 7)
        val usage = requireNotNull(AnthropicUsageTotals.parse { raw[it] })
        assertEquals(7, usage.reasoningTokens)
    }

    @Test
    fun parseReturnsNullForAnEmptyUsageObject() {
        assertNull(AnthropicUsageTotals.parse { null })
    }
}
