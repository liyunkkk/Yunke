package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.tool.ForegroundExclusiveGate.Admission.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForegroundExclusiveGateTest {
    @Before fun reset() = ForegroundExclusiveGate.resetForTests()
    @After fun cleanup() = ForegroundExclusiveGate.resetForTests()

    @Test fun onlyMainScreenToolsTakeOwnership() {
        listOf("launch_app", "observe_screen", "tap_element", "open_uri", "wait_for_text",
            "wait_for_package", "press_key", "open_system_panel", "set_alarm", "set_timer").forEach {
            assertTrue(it, ForegroundExclusiveGate.shouldSerialize(it))
        }
        listOf("browser_use", "memory_get", "terminal", "web_search", "search_apps").forEach {
            assertFalse(it, ForegroundExclusiveGate.shouldSerialize(it))
        }
    }

    @Test fun askPreflightDoesNotReserveIdleScreen() {
        assertEquals(ACQUIRED, ForegroundExclusiveGate.checkAvailability("ask"))
        assertNull(ForegroundExclusiveGate.ownerForTests())
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("other"))
        assertEquals(BUSY, ForegroundExclusiveGate.checkAvailability("ask"))
        assertEquals(BUSY, ForegroundExclusiveGate.acquire("ask"))
        assertEquals("other", ForegroundExclusiveGate.ownerForTests())
        assertEquals(CLOSED, ForegroundExclusiveGate.checkAvailability("ask") { true })
    }

    @Test fun sameRunReacquiresUntilExplicitRelease() {
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("run-a"))
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("run-a"))
        assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        ForegroundExclusiveGate.release("run-a")
        ForegroundExclusiveGate.release("run-a")
        assertNull(ForegroundExclusiveGate.ownerForTests())
    }

    @Test(timeout = 5000) fun competingRunReturnsBeforeOwnerReleasesAndNeverQueues() {
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("run-a"))
        val result = AtomicReference<ForegroundExclusiveGate.Admission>()
        val done = CountDownLatch(1)
        val contender = Thread {
            try { result.set(ForegroundExclusiveGate.acquire("run-b")) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("Must return while A still owns the screen", done.await(1, TimeUnit.SECONDS))
            assertEquals(BUSY, result.get())
            ForegroundExclusiveGate.release("run-b")
            assertEquals("run-a", ForegroundExclusiveGate.ownerForTests())
        } finally {
            ForegroundExclusiveGate.release("run-a")
            contender.interrupt()
            contender.join(1000)
        }
        assertFalse(contender.isAlive)
        assertNull(ForegroundExclusiveGate.ownerForTests())
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("run-b"))
    }

    @Test(timeout = 5000) fun simultaneousClaimsHaveExactlyOneWinner() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = List(2) { AtomicReference<ForegroundExclusiveGate.Admission>() }
        val workers = results.mapIndexed { i, result -> Thread {
            try { start.await(); result.set(ForegroundExclusiveGate.acquire("run-$i")) }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() } }
        try {
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.get() == ACQUIRED })
            assertEquals(1, results.count { it.get() == BUSY })
            val winner = results.indexOfFirst { it.get() == ACQUIRED }
            ForegroundExclusiveGate.release("run-${1 - winner}")
            assertEquals("run-$winner", ForegroundExclusiveGate.ownerForTests())
        } finally {
            workers.forEach { it.interrupt(); it.join(1000) }
        }
    }

    @Test fun closedAndInvalidClaimsCannotOwnOrDisturbScreen() {
        assertEquals(CLOSED, ForegroundExclusiveGate.acquire("closed") { true })
        assertEquals(CLOSED, ForegroundExclusiveGate.acquire("  "))
        assertNull(ForegroundExclusiveGate.ownerForTests())
        assertEquals(ACQUIRED, ForegroundExclusiveGate.acquire("owner"))
        assertEquals(CLOSED, ForegroundExclusiveGate.acquire("owner") { true })
        assertEquals(CLOSED, ForegroundExclusiveGate.acquire("other") { true })
        assertEquals("owner", ForegroundExclusiveGate.ownerForTests())
    }

    @Test(timeout = 5000) fun interruptWhileMetadataLockIsHeldReturnsClosed() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val started = CountDownLatch(1)
        val result = AtomicReference<ForegroundExclusiveGate.Admission>()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val holder = Thread {
            ForegroundExclusiveGate.acquire("closed-holder") {
                locked.countDown()
                release.await(3, TimeUnit.SECONDS)
                true
            }
        }.apply { isDaemon = true; start() }
        var contender: Thread? = null
        try {
            assertTrue(locked.await(1, TimeUnit.SECONDS))
            contender = Thread {
                started.countDown()
                try {
                    result.set(ForegroundExclusiveGate.acquire("interrupted"))
                    interrupted.set(Thread.currentThread().isInterrupted)
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            contender.interrupt()
            assertTrue("Cancellation must not wait for the metadata holder", done.await(1, TimeUnit.SECONDS))
            assertEquals(CLOSED, result.get())
            assertTrue(interrupted.get())
        } finally {
            release.countDown()
            holder.join(1000)
            contender?.interrupt()
            contender?.join(1000)
        }
        assertNull(ForegroundExclusiveGate.ownerForTests())
    }

    @Test fun interruptedClaimReturnsClosedWithoutClearingInterrupt() {
        Thread.currentThread().interrupt()
        try {
            assertEquals(CLOSED, ForegroundExclusiveGate.acquire("run-a"))
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(ForegroundExclusiveGate.ownerForTests())
        } finally { Thread.interrupted() }
    }
}
