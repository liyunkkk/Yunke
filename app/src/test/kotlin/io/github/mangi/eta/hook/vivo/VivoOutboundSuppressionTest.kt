package io.github.mangi.eta.hook.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoOutboundSuppressionTest {
    @Test fun unownedDispatchProceedsExactlyOnceDespiteDiagnosticFailure() {
        var proceeds = 0
        val result = Any()
        assertSame(result, suppressOwnedVivoOutbound(false,
            onOwned = { fail("Unowned dispatch must not be claimed") },
            onFailure = { fail("Original call must not enter owned error handling") },
            onDiagnostic = { throw IllegalStateException("diagnostic") },
            proceed = { proceeds++; result }))
        assertEquals(1, proceeds)
    }

    @Test fun originalExceptionIsPreservedWithoutRetry() {
        val expected = IllegalStateException("original")
        var proceeds = 0
        try {
            suppressOwnedVivoOutbound(false, {}, { fail("must not recover original") }, {},
                { proceeds++; throw expected })
            fail("Expected original exception")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
        assertEquals(1, proceeds)
    }

    @Test fun ownedDispatchNeverProceedsWhenActionAndRecoveryAndDiagnosticThrow() {
        var proceeds = 0
        var claims = 0
        var recoveries = 0
        var recovered: Throwable? = null
        val expected = AssertionError("owned")
        val result = suppressOwnedVivoOutbound(true,
            onOwned = { claims++; throw expected },
            onFailure = { error ->
                recoveries++
                recovered = error
                throw IllegalStateException("recovery")
            },
            onDiagnostic = { throw IllegalStateException("diagnostic") },
            proceed = { proceeds++; Any() })
        assertNull(result)
        assertEquals(1, claims)
        assertEquals(1, recoveries)
        assertSame(expected, recovered)
        assertEquals(0, proceeds)
    }

    private class Harness {
        val turns = VivoTurnLedger()
        val receipts = WeakIdentityReceipts<VivoTurnLedger.Turn>()
        var proceeds = 0
        var modelCalls = 0
        fun map(payload: Any, link: String, dialog: String): VivoTurnLedger.Turn =
            turns.begin(link, dialog).also { receipts.put(payload, it) }
        fun send(payload: Any, link: String) {
            val turn = receipts.get(payload)
            suppressOwnedVivoOutbound(turn != null && turn.link == link,
                onOwned = { if (turns.claim(turn!!) == VivoTurnLedger.Claim.START) modelCalls++ },
                onFailure = { throw AssertionError(it) }, onDiagnostic = {},
                proceed = { proceeds++; null })
        }
    }

    @Test fun liveMapperReceiptSurvivesDuplicateCompletionAndCancellation() {
        val h = Harness()
        val payload = Any()
        val turn = h.map(payload, "link", "dialog")
        h.send(payload, "link")
        h.send(payload, "link")
        assertTrue(h.turns.finish(turn))
        h.send(payload, "link")
        h.turns.cancel("link", "dialog", true)
        h.send(payload, "link")
        assertEquals(1, h.modelCalls)
        assertEquals(0, h.proceeds)
        assertSame(turn, h.receipts.get(payload))
    }

    @Test fun cancellationBeforeMapperDispatchDoesNotEraseReceipt() {
        val h = Harness()
        val payload = Any()
        h.turns.begin("link", "dialog")
        h.turns.cancel("link", "dialog", true)
        h.map(payload, "link", "dialog")
        repeat(3) { h.send(payload, "link") }
        assertEquals(0, h.modelCalls)
        assertEquals(0, h.proceeds)
    }

    @Test fun cancelAllAndInterleavedDialogsDoNotDeleteAnotherReceipt() {
        val h = Harness()
        val a = Any(); val b = Any()
        val old = h.map(a, "link", "old")
        val next = h.map(b, "link", "next")
        h.turns.cancel("link", "old", true)
        h.send(a, "link")
        h.send(b, "link")
        h.turns.cancelAll()
        h.send(b, "link")
        assertSame(old, h.receipts.get(a))
        assertSame(next, h.receipts.get(b))
        assertEquals(1, h.modelCalls)
        assertEquals(0, h.proceeds)
    }

    @Test fun anUnmappedObjectIsNeverClaimedJustBecauseLinkMatches() {
        val h = Harness()
        h.map(Any(), "link", "dialog")
        h.send(Any(), "link")
        assertEquals(0, h.modelCalls)
        assertEquals(1, h.proceeds)
    }
}
