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

    @Test fun legacyUsageLeavesCalibrationUnknownRatherThanZero() {
        val event = AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20000))
        val restored = AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)) as AgentEvent.UsageReceived
        assertNull(restored.requestHistoryTokens)
        assertNull(restored.requestOverheadTokens)
        assertFalse(restored.projected)
    }
}
