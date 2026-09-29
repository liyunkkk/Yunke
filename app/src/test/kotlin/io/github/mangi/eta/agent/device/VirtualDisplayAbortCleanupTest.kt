package io.github.mangi.eta.agent.device

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayAbortCleanupTest {
    @Test fun absentSessionDoesNothingAndCleanupDoesNotRequireDelivery() {
        assertNull(VirtualDisplayAbortCleanup.failureCode { null })
        assertNull(VirtualDisplayAbortCleanup.failureCode {
            JSONObject().put("ok", true).put("released", true).put("handedOff", false)
        })
    }

    @Test fun successWithoutVerifiedReleaseIsStillFailure() {
        for (receipt in listOf(
            JSONObject().put("ok", true),
            JSONObject().put("ok", true).put("released", false),
            JSONObject().put("ok", "true").put("released", "true"),
            JSONObject().put("ok", false).put("released", true),
        )) assertEquals("AUTO_CLEANUP_FAILED", VirtualDisplayAbortCleanup.failureCode { receipt })
    }

    @Test fun uncertainFailureIsNotRetriedAndOnlyCodeIsReported() {
        var calls = 0
        assertEquals("RELEASE_UNCERTAIN", VirtualDisplayAbortCleanup.failureCode {
            calls++
            JSONObject().put("ok", false).put("error", "RELEASE_UNCERTAIN")
        })
        assertEquals(1, calls)
        assertEquals("AUTO_CLEANUP_FAILED", VirtualDisplayAbortCleanup.failureCode {
            JSONObject().put("error", "untrusted detail must not be echoed")
        })
    }

    @Test fun initialInterruptIsIsolatedOnlyForCleanupAndThenRestored() {
        Thread.currentThread().interrupt()
        try {
            assertNull(VirtualDisplayAbortCleanup.failureCode {
                assertFalse(Thread.currentThread().isInterrupted)
                JSONObject().put("ok", true).put("released", true)
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun newInterruptIsPreservedAndNoRetryRuns() {
        var calls = 0
        try {
            assertEquals("AUTO_CLEANUP_INTERRUPTED", VirtualDisplayAbortCleanup.failureCode {
                calls++
                throw InterruptedException()
            })
            assertEquals(1, calls)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun exceptionDoesNotReplaceTheOriginalRunFailureWithRawDetails() {
        assertEquals("AUTO_CLEANUP_FAILED", VirtualDisplayAbortCleanup.failureCode {
            error("private details")
        })
    }
}
