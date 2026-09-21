package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class StreamDeliveryTimingTest {
    @Test fun oldBundlesAndReplayHaveNoLatencySample() {
        assertNull(StreamDeliveryTiming.delayNs(0, 100, true))
        assertNull(StreamDeliveryTiming.delayNs(10, 100, false))
        assertNull(StreamDeliveryTiming.delayNs(100, 10, true))
    }
    @Test fun liveDeltaUsesMonotonicDelay() {
        assertEquals(90L, StreamDeliveryTiming.delayNs(10, 100, true))
    }
}
