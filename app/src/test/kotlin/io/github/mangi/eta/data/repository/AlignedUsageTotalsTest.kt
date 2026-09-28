package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignedUsageTotalsTest {
    @Test
    fun conversationTokensUseTheModelLedgerNotASecondSum() {
        val live = ModelUsageEvent(
            atMillis = 1,
            inputTokens = 100,
            outputTokens = 10,
            cachedTokens = 40,
            conversationId = "live",
            requestId = "a",
        )
        val deleted = ModelUsageEvent(
            atMillis = 2,
            inputTokens = 50,
            outputTokens = 5,
            cachedTokens = 20,
            conversationId = "gone",
            requestId = "b",
        )
        val snapshot = ModelUsageSnapshot(
            providers = listOf(
                ModelUsageProviderUi(
                    id = "p",
                    name = "p",
                    models = listOf(
                        ModelUsageModelUi(
                            id = "m",
                            displayName = "m",
                            inputTokens = 150,
                            outputTokens = 15,
                            conversationCount = 2,
                            activeDays = 1,
                            cachedTokens = 60,
                            events = listOf(live, deleted),
                        ),
                    ),
                ),
            ),
        )
        val (current, lifetime) = alignedUsageTotals(snapshot, setOf("live"))
        assertEquals(TokenTotals(100, 10, 40), current)
        assertEquals(TokenTotals(150, 15, 60), lifetime)
        assertEquals(60L, lifetime.input - lifetime.cached)
        assertEquals(60L, current.input - current.cached)
    }
}
