package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The policy treats registry control tokens as opaque: it must never rebuild them from IDs. */
class AgentChildControlTokenPolicyTest {
    private class Token(val generation: String, val epoch: Long)

    @Test fun resumeReturnsTheOriginalEpochTokenNotANewCapture() {
        val policy = AgentChildControlPolicy<Token> { "event" }
        val parent = Any()
        val captured = Token("shared-generation", 1)
        policy.begin(parent, "old-parent")
        policy.pause(parent, listOf(captured))
        val adoptedByNewParent = Token("shared-generation", 2)
        val resumed = policy.resume(parent).single()
        assertSame(captured, resumed)
        assertTrue(resumed.epoch != adoptedByNewParent.epoch)
    }

    @Test fun terminalSelectionRetainsEpochAfterParentRemovalAndNewTurn() {
        val policy = AgentChildControlPolicy<Token> { "event" }
        val oldParent = Any()
        val newParent = Any()
        val oldToken = Token("shared-generation", 7)
        policy.begin(oldParent, "old-parent")
        val choice = policy.terminate(oldParent, AgentChildControlPolicy.Reason.USER_STOP, listOf(oldToken), true)!!
        policy.finish(oldParent)
        policy.begin(newParent, "new-parent")
        policy.pause(newParent, listOf(Token("shared-generation", 8)))
        val confirmation = policy.resolve(choice.eventId)!!
        assertEquals("old-parent", confirmation.runId)
        assertSame(oldToken, confirmation.targets.single())
        // Registry (module A), not this policy, rejects this old epoch when applying stop/pause.
        assertEquals(7L, confirmation.targets.single().epoch)
        assertEquals(8L, policy.resume(newParent).single().epoch)
    }
}
