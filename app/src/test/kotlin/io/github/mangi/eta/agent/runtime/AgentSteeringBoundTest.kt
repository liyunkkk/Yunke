package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class AgentSteeringBoundTest {
    @Test fun repeatedAndExcessInstructionsAreRejectedWithoutDisplacingAcceptedInstructions() {
        val controller = AgentRunController()
        assertTrue(controller.steer("first"))
        assertFalse(controller.steer(" first "))
        for (i in 2..16) assertTrue(controller.steer("instruction $i"))
        assertFalse(controller.steer("overflow"))
        assertEquals("first", controller.pollSteeringMessage())
        assertTrue(controller.steer("next"))
        assertEquals(16, generateSequence { controller.pollSteeringMessage() }.count())
    }
}
