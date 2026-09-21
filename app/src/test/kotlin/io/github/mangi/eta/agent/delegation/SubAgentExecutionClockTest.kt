package io.github.mangi.eta.agent.delegation

import org.junit.Assert.*
import org.junit.Test

class SubAgentExecutionClockTest {
    @Test fun compressionPausesExecutionWithoutRenewingEitherBudget() {
        var now = 0L
        val clock = SubAgentExecutionClock(100, 200) { now }
        now = 80
        clock.setCompacting(true)
        now = 230
        assertNull(clock.expired())
        clock.setCompacting(false)
        now = 249
        assertNull(clock.expired())
        now = 250
        assertEquals("SUB_AGENT_TIMEOUT", clock.expired())
    }
    @Test fun repeatedCompressionSharesBoundedBudget() {
        var now = 0L
        val clock = SubAgentExecutionClock(100, 200) { now }
        clock.setCompacting(true)
        now = 150
        clock.setCompacting(false)
        now = 160
        clock.setCompacting(true)
        now = 210
        assertEquals("SUB_AGENT_COMPACTION_TIMEOUT", clock.expired())
    }
}
