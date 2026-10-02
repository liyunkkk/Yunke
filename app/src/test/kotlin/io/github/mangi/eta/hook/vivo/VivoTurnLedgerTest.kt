package io.github.mangi.eta.hook.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoTurnLedgerTest {
    @Test fun stopBeforeMapperPreventsModelSubmission() {
        val ledger = VivoTurnLedger()
        val turn = ledger.begin("link", "dialog")
        ledger.cancel("link", "dialog", true)
        assertSame(turn, ledger.find("dialog"))
        assertEquals(VivoTurnLedger.Claim.CANCELLED, ledger.claim(turn))
        assertTrue(ledger.consumeCancelRender(turn))
        assertFalse(ledger.consumeCancelRender(turn))
        assertFalse(ledger.finish(turn))
    }
    @Test fun closeBeforeDispatchDoesNotWriteIntoAClosedWindow() {
        val ledger = VivoTurnLedger()
        val turn = ledger.begin("link", "dialog")
        ledger.cancel("link", null, false)
        assertEquals(VivoTurnLedger.Claim.CANCELLED, ledger.claim(turn))
        assertFalse(ledger.consumeCancelRender(turn))
    }
    @Test fun closeThenDisableNeverRestoresPermissionToRender() {
        val ledger = VivoTurnLedger()
        val turn = ledger.begin("link", "dialog")
        ledger.cancel("link", null, false)
        ledger.cancelAll()
        assertEquals(VivoTurnLedger.Claim.CANCELLED, ledger.claim(turn))
        assertFalse(ledger.consumeCancelRender(turn))
    }
    @Test fun repeatedNativeDispatchNeverStartsAnotherModelRequest() {
        val ledger = VivoTurnLedger()
        val turn = ledger.begin("link", "dialog")
        assertEquals(VivoTurnLedger.Claim.START, ledger.claim(turn))
        assertSame(turn, ledger.begin("link", "dialog"))
        assertEquals(VivoTurnLedger.Claim.ACTIVE_DUPLICATE, ledger.claim(turn))
        assertTrue(ledger.finish(turn))
        assertFalse(ledger.finish(turn))
        assertEquals(VivoTurnLedger.Claim.FINISHED_DUPLICATE, ledger.claim(turn))
    }
    @Test fun stopAfterReservationInvalidatesLateCompletion() {
        val ledger = VivoTurnLedger()
        val first = ledger.begin("link", "first")
        assertEquals(VivoTurnLedger.Claim.START, ledger.claim(first))
        ledger.cancel("link", "first", true)
        val next = ledger.begin("link", "next")
        assertFalse(ledger.active(first))
        assertFalse(ledger.finish(first))
        assertEquals(VivoTurnLedger.Claim.START, ledger.claim(next))
        assertTrue(ledger.active(next))
    }
    @Test fun quotaDoesNotEvictReplayProtection() {
        val ledger = VivoTurnLedger(1)
        val first = ledger.begin("link", "first")
        ledger.claim(first); ledger.finish(first)
        val overflow = ledger.begin("link", "second")
        assertSame(overflow, ledger.find("second"))
        assertEquals(VivoTurnLedger.Claim.FULL, ledger.claim(overflow))
        assertEquals(VivoTurnLedger.Claim.FINISHED_DUPLICATE, ledger.claim(first))
    }
    @Test fun ambiguousDialogNeverSelectsAnotherLinksTurn() {
        val ledger = VivoTurnLedger()
        ledger.begin("one", "dialog"); ledger.begin("two", "dialog")
        assertNull(ledger.find("dialog"))
    }
    @Test fun disablingAlsoCancelsMappedButUnsubmittedTurns() {
        val ledger = VivoTurnLedger()
        val a = ledger.begin("a", "a"); val b = ledger.begin("b", "b")
        ledger.claim(a); ledger.cancelAll()
        assertFalse(ledger.finish(a))
        assertEquals(VivoTurnLedger.Claim.CANCELLED, ledger.claim(b))
    }
}
