package io.github.mangi.eta.agent.overlay

import org.junit.Assert.*
import org.junit.Test

class AgentOrbPulseTest {
    @Test fun breathingHasSameRangeAndRepeats() {
        assertEquals(0.6f, agentOrbPulseAlpha(0), 0.0001f)
        assertEquals(1f, agentOrbPulseAlpha(1400), 0.0001f)
        assertEquals(0.6f, agentOrbPulseAlpha(2800), 0.0001f)
        assertEquals(agentOrbPulseAlpha(600), agentOrbPulseAlpha(3400), 0.0001f)
    }
    @Test fun alphaNeverEscapesDrawingBounds() {
        for (millis in 0L..10000L step 7) assertTrue(agentOrbPulseAlpha(millis) in 0.6f..1f)
        assertEquals(0.6f, agentOrbPulseAlpha(-1), 0.0001f)
        assertTrue(agentOrbPulseAlpha(Long.MAX_VALUE) in 0.6f..1f)
    }
    @Test fun timerIsCappedAtTwentyUpdatesPerSecond() {
        assertEquals(50L, ORB_PULSE_INTERVAL_MS)
        assertEquals(20L, 1000L / ORB_PULSE_INTERVAL_MS)
    }
    @Test fun quietStateRemainsVisibleWithoutAnimation() {
        assertEquals(0.85f, ORB_STATIC_ALPHA, 0.0001f)
    }
}
