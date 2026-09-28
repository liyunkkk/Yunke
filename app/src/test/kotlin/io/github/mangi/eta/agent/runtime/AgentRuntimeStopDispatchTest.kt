package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimeStopDispatchTest {
    @Test fun parentStopDuringDeliveryDoesNotCancelChildren() {
        assertEquals(AgentRuntimeWire.MSG_STOP_MAIN_RUN,
            AgentRuntimeStopDispatch.message(AgentChildControlPolicy.Reason.USER_STOP))
    }
    @Test fun settingsStopDuringInterruptionDoesNotCancelChildren() {
        assertEquals(AgentRuntimeWire.MSG_STOP_MAIN_RUN,
            AgentRuntimeStopDispatch.message(AgentChildControlPolicy.Reason.SETTINGS_CHANGED))
    }
    @Test fun explicitWholeRunCancelRetainsItsScope() {
        assertEquals(AgentRuntimeWire.MSG_CANCEL, AgentRuntimeStopDispatch.message(null))
    }
}
