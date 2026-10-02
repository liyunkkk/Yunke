package io.github.mangi.eta.agent.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoClientStateTest {
    @org.junit.Test fun ownershipLostDuringBindCannotCommitSend() {
        val state = VivoClientState()
        val ticket = state.begin("request")!!
        org.junit.Assert.assertFalse(state.markSent(ticket, stillOwner = false))
        org.junit.Assert.assertTrue(state.finish(ticket))
        org.junit.Assert.assertFalse(state.markSent(ticket, stillOwner = true))
        org.junit.Assert.assertNull(state.begin("request"))
    }

    @Test fun bindDeathAndLateResultsCannotCompleteTwice() {
        val state = VivoClientState()
        val first = state.begin("first")!!
        assertNull(state.begin("second"))
        assertTrue(state.markSent(first))
        assertFalse(state.markSent(first))
        assertTrue(state.finish(first))
        assertFalse(state.finish(first))
        val second = state.begin("second")!!
        assertFalse(state.finish(first))
        assertTrue(state.current(second))
    }
    @Test fun cancellationBeforeBindingPreventsSendAndRetry() {
        val state = VivoClientState()
        val ticket = state.begin("r")!!
        assertTrue(state.finish(ticket))
        assertFalse(state.markSent(ticket))
        assertNull(state.begin("r"))
    }
    @Test fun closeRejectsNewWorkAndAllOldCallbacks() {
        val state = VivoClientState()
        val ticket = state.begin("r")!!
        state.close()
        assertFalse(state.markSent(ticket))
        assertFalse(state.finish(ticket))
        assertNull(state.begin("new"))
    }
    @Test fun boundedReplayLedgerNeverEvicts() {
        val state = VivoClientState(1)
        state.finish(state.begin("r")!!)
        assertNull(state.begin("r"))
        assertNull(state.begin("new"))
    }
}
