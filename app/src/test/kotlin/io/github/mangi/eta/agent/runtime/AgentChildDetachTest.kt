package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChildDetachTest {
    @Test fun normalParentSealDoesNotCloseRetainedChildToolDependencies() {
        val session = AgentRuntimeSession("parent")
        var closeCount = 0
        val tools = AgentChildToolOwnership { closeCount++ }
        val parentBinding = session.controller.register { tools.release() }
        assertTrue(tools.retain())
        parentBinding.close() // parent worker unregisters after its run
        tools.release() // relinquish parent ownership once
        assertTrue(session.complete(AgentRuntimeWire.RunResult("parent", true, "done")))
        assertEquals(0, closeCount)
        AgentChildToolOwnership.releaseChild { tools.release() }
        assertEquals(1, closeCount)
    }

    @Test fun stoppedParentKeepsToolsUntilDetachedChildFinishes() {
        val session = AgentRuntimeSession("parent")
        var closeCount = 0
        val tools = AgentChildToolOwnership { closeCount++ }
        session.controller.register { tools.release() }
        assertTrue(tools.retain())
        assertTrue(session.requestStop())
        tools.release() // duplicate release in parent worker finally
        assertEquals(0, closeCount)
        AgentChildToolOwnership.releaseChild { tools.release() }
        assertEquals(1, closeCount)
    }
}
