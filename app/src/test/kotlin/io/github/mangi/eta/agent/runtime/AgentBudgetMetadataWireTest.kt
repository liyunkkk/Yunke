package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentBudgetMetadataWireTest {
    @Test fun cloudReceiptCalibrationRoundTripsWithoutChangingBillingFields() {
        val event = AgentEvent.UsageReceived(3, AgentTokenUsage(inputTokens = 20000, outputTokens = 200),
            requestHistoryTokens = 15000, requestOverheadTokens = 3000)
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
    }

    @Test fun forecastOnlyRoundTripsWithoutInventingBilling() {
        val event = AgentEvent.UsageReceived(2, AgentTokenUsage(), projected = true,
            forecastPromptTokens = 29497)
        val restored = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)) as AgentEvent.UsageReceived
        assertEquals(event, restored)
        assertTrue(restored.usage.isEmpty)
        assertNull(restored.usage.inputTokens)
    }

    @Test fun measuredUsageAndIndependentForecastRoundTripSeparately() {
        val event = AgentEvent.UsageReceived(3, AgentTokenUsage(inputTokens = 29405, cachedTokens = 2797),
            forecastPromptTokens = 29497)
        val restored = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)) as AgentEvent.UsageReceived
        assertEquals(event, restored)
        assertFalse(restored.projected)
        assertEquals(29405, restored.usage.inputTokens)
        assertEquals(29497, restored.forecastPromptTokens)
    }

    @Test fun legacyUsageLeavesCalibrationUnknownRatherThanZero() {
        val event = AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20000))
        val restored = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)) as AgentEvent.UsageReceived
        assertNull(restored.requestHistoryTokens)
        assertNull(restored.requestOverheadTokens)
        assertNull(restored.forecastPromptTokens)
        assertFalse(restored.projected)
    }
}
