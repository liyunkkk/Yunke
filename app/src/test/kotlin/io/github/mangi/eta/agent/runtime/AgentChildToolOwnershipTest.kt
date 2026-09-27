package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChildToolOwnershipTest {
    @Test fun parentTerminalAndFinallyCannotCloseAnActiveChildsTools() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        assertTrue(owner.retain())
        owner.release() // parent controller.cancel at seal
        owner.release() // parent finally (idempotent)
        assertEquals(0, closes)
        AgentChildToolOwnership.releaseChild { owner.release() }
        assertEquals(1, closes)
        assertFalse(owner.retain())
    }

    @Test fun childCanFinishBeforeParentAndToolsCloseExactlyOnce() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        assertTrue(owner.retain())
        AgentChildToolOwnership.releaseChild { owner.release() }
        assertEquals(0, closes)
        owner.release() // parent finally
        owner.release() // controller terminal (idempotent)
        assertEquals(1, closes)
    }
}
