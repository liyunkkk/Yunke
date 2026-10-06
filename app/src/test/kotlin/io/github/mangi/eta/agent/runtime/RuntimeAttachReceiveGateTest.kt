package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAttachReceiveGateTest {
    @Test fun receiveOrderClassifiesReplayBeforeAckAndLiveAfterAckEvenBeforeApply() {
        val gate = RuntimeAttachReceiveGate()
        assertFalse(gate.isLive)
        gate.attachResponse(true)
        assertTrue(gate.isLive)
        gate.attachResponse(false) // Duplicate ACK does not close a live subscription.
        assertTrue(gate.isLive)
        gate.result()
        assertFalse(gate.isLive)
        gate.attachResponse(true)
        assertFalse(gate.isLive)
    }

    @Test fun rejectedAckOrResultBeforeAckCannotReopenDelivery() {
        listOf(true, false).forEach { resultFirst ->
            val gate = RuntimeAttachReceiveGate()
            if (resultFirst) gate.result() else gate.attachResponse(false)
            gate.attachResponse(true)
            assertFalse(gate.isLive)
        }
    }
}
