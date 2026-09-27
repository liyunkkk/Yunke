package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStopSealTimeoutTest {
    private var clock = 1_000L

    private fun tracker(timeoutMillis: Long = 5_000L): RunStopSealTimeout =
        RunStopSealTimeout(timeoutMillis = timeoutMillis, now = { clock })

    @Test fun sealUnlocksAfterTheDeadlineWhenNoResultArrives() {
        val seals = tracker()
        val ticket = requireNotNull(seals.beginStop("run-1")) { "a stop request must arm a watchdog" }
        assertTrue(seals.isPending("run-1"))

        clock += 4_999L
        assertFalse("must not unlock before the deadline", seals.claimUnlock(ticket))
        assertTrue("an early wake-up must keep the seal", seals.isPending("run-1"))

        clock += 1L
        assertTrue("an expired seal without a result must unlock", seals.claimUnlock(ticket))
        assertFalse("the unlocked run must not stay pending", seals.isPending("run-1"))
    }

    @Test fun resultBeforeTheDeadlineDisarmsTheWatchdog() {
        val seals = tracker()
        val ticket = requireNotNull(seals.beginStop("run-1"))

        clock += 1_000L
        assertTrue("the result path must be able to release the seal", seals.release("run-1"))

        clock += 60_000L
        assertFalse("a released seal must never unlock later", seals.claimUnlock(ticket))
        assertFalse(seals.isPending("run-1"))
        assertEquals(0, seals.pendingCount())
    }

    @Test fun repeatedStopRequestsForTheSameRunShareOneWatchdog() {
        val seals = tracker()
        val first = requireNotNull(seals.beginStop("run-1"))
        assertNull("a repeated stop request must not arm a second watchdog", seals.beginStop("run-1"))
        assertEquals(1, seals.pendingCount())

        clock += 5_000L
        assertTrue(seals.claimUnlock(first))
        assertFalse("the same watchdog must not unlock twice", seals.claimUnlock(first))
        assertFalse(seals.claimUnlock(first))
    }

    @Test fun staleWatchdogTicketCannotUnlockANewerStopRequest() {
        val seals = tracker()
        val stale = requireNotNull(seals.beginStop("run-1"))
        assertTrue(seals.release("run-1"))
        val fresh = requireNotNull(seals.beginStop("run-1"))

        clock += 5_000L
        assertFalse("a stale watchdog must not unlock the newer seal", seals.claimUnlock(stale))
        assertTrue("the newer seal must stay pending", seals.isPending("run-1"))
        assertTrue(seals.claimUnlock(fresh))
    }

    @Test fun releasedRunCanBeSealedAgainLater() {
        val seals = tracker()
        val first = requireNotNull(seals.beginStop("run-1"))
        assertTrue(seals.release("run-1"))
        assertFalse(seals.claimUnlock(first))

        val second = requireNotNull(seals.beginStop("run-1")) { "a later stop must rebuild the seal" }
        clock += 5_000L
        assertTrue(seals.claimUnlock(second))
    }

    @Test fun defaultTimeoutIsPositive() {
        assertTrue(
            "the stop seal timeout must be a positive bound",
            RunStopSealTimeout.DEFAULT_TIMEOUT_MS > 0L,
        )
        val seals = RunStopSealTimeout(timeoutMillis = RunStopSealTimeout.DEFAULT_TIMEOUT_MS, now = { clock })
        assertFalse(seals.isPending("run-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveTimeoutIsRejected() {
        RunStopSealTimeout(timeoutMillis = 0L)
    }

    @Test fun pendingNoticeDoesNotClaimPersistence() {
        assertFalse(
            "the waiting notice is about the run, not about writing the transcript to disk",
            StopSealNotices.PENDING.contains("保存"),
        )
        assertFalse(StopSealNotices.TIMED_OUT.contains("保存"))
        assertFalse(StopSealNotices.TIMED_OUT.contains("已保存"))
        assertTrue(StopSealNotices.TIMED_OUT.contains("未确认"))
    }
}
