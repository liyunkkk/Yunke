package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AgentChildToolOwnershipTest {
    private fun concurrently(vararg actions: () -> Unit) {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val workers = actions.map { action ->
            thread(isDaemon = true) {
                try { start.await(); action() }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }
        start.countDown()
        workers.forEach {
            it.join(3000)
            assertFalse("concurrent release worker did not finish", it.isAlive)
        }
        failure.get()?.let { throw it }
    }

    @Test fun parentTerminalAndFinallyCannotCloseAnActiveChildsTools() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        val child = requireNotNull(owner.retain())
        owner.release() // parent controller.cancel at seal
        owner.release() // parent finally (idempotent)
        assertEquals(0, closes)
        assertNull(owner.retain()) // stopped parent cannot create NEW groups
        child.close()
        assertEquals(1, closes)
        assertNull(owner.retain())
    }

    @Test fun childCanFinishBeforeParentAndToolsCloseExactlyOnce() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        val child = requireNotNull(owner.retain())
        child.close()
        child.close()
        assertEquals(0, closes)
        owner.release()
        owner.release()
        assertEquals(1, closes)
    }

    @Test fun frozenOrdinaryAndCurrentReplacementBothRetainWhileParentIsLive() {
        for (ordinaryFirst in listOf(true, false)) {
            var closes = 0
            val owner = AgentChildToolOwnership { closes++ }
            val ordinary = requireNotNull(owner.retain())
            val replacement = requireNotNull(owner.retain())
            owner.release()
            owner.release()
            assertEquals(0, closes)
            val first = if (ordinaryFirst) ordinary else replacement
            val last = if (ordinaryFirst) replacement else ordinary
            first.close()
            first.close()
            assertEquals(0, closes)
            last.close()
            last.close()
            assertEquals(1, closes)
        }
    }

    @Test fun failedSecondGroupConstructionReleasesOnlyItsOwnLease() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        val ordinary = requireNotNull(owner.retain())
        val failedReplacement = requireNotNull(owner.retain())
        failedReplacement.close() // construction finally
        failedReplacement.close() // register-stop callback for the SAME lease
        owner.release()
        assertEquals(0, closes)
        ordinary.close()
        assertEquals(1, closes)
    }

    @Test fun earlierReleasedLeaseCannotReleaseANewerGroup() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        val old = requireNotNull(owner.retain())
        old.close()
        val new = requireNotNull(owner.retain())
        owner.release()
        old.close()
        assertEquals(0, closes)
        new.close()
        assertEquals(1, closes)
    }

    @Test fun childLeaseCannotReleaseTheParentReference() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        requireNotNull(owner.retain()).close()
        assertEquals(0, closes)
        val replacement = requireNotNull(owner.retain())
        owner.release()
        assertEquals(0, closes)
        replacement.close()
        assertEquals(1, closes)
    }

    @Test fun noChildGroupClosesOnParentReleaseAndCannotReopen() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        owner.release()
        owner.release()
        assertEquals(1, closes)
        assertNull(owner.retain())
    }

    @Test fun leasesFromDifferentOwnersNeverShareReferences() {
        var firstCloses = 0
        var secondCloses = 0
        val first = AgentChildToolOwnership { firstCloses++ }
        val second = AgentChildToolOwnership { secondCloses++ }
        val firstChild = requireNotNull(first.retain())
        val secondChild = requireNotNull(second.retain())
        first.release()
        second.release()
        firstChild.close()
        assertEquals(1, firstCloses)
        assertEquals(0, secondCloses)
        secondChild.close()
        assertEquals(1, secondCloses)
    }

    @Test(timeout = 15000) fun duplicateReleaseRaceCannotCloseAnotherActiveGroupsTools() {
        repeat(100) {
            val closes = AtomicInteger()
            val owner = AgentChildToolOwnership { closes.incrementAndGet() }
            val ordinary = requireNotNull(owner.retain())
            val replacement = requireNotNull(owner.retain())
            concurrently(
                { repeat(3) { owner.release() } },
                { repeat(3) { ordinary.close() } },
                { repeat(3) { ordinary.close() } },
            )
            assertEquals(0, closes.get())
            replacement.close()
            assertEquals(1, closes.get())
        }
    }

    @Test(timeout = 15000) fun retainAndParentCancellationRaceIsFailClosedOrKeepsOneLease() {
        repeat(100) {
            val closes = AtomicInteger()
            val owner = AgentChildToolOwnership { closes.incrementAndGet() }
            val lease = AtomicReference<AgentChildToolOwnership.ChildLease?>()
            concurrently({ lease.set(owner.retain()) }, { owner.release() })
            assertNull(owner.retain())
            if (lease.get() == null) assertEquals(1, closes.get())
            else {
                assertEquals(0, closes.get())
                lease.get()!!.close()
                assertEquals(1, closes.get())
            }
        }
    }

    @Test fun bothGroupsCanFinishBeforeParentWithoutClosingParentTools() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++ }
        val ordinary = requireNotNull(owner.retain())
        val replacement = requireNotNull(owner.retain())
        replacement.close()
        ordinary.close()
        replacement.close()
        assertEquals(0, closes)
        owner.release()
        owner.release()
        assertEquals(1, closes)
    }

    @Test(timeout = 15000) fun concurrentFinalReleasesCloseExactlyOnce() {
        repeat(100) {
            val closes = AtomicInteger()
            val owner = AgentChildToolOwnership { closes.incrementAndGet() }
            val ordinary = requireNotNull(owner.retain())
            val replacement = requireNotNull(owner.retain())
            concurrently(
                { repeat(3) { owner.release() } },
                { repeat(3) { ordinary.close() } },
                { repeat(3) { replacement.close() } },
                { repeat(3) { replacement.close() } },
            )
            assertEquals(1, closes.get())
            assertNull(owner.retain())
        }
    }

    @Test(timeout = 15000) fun cleanupDoesNotHoldOwnerMonitorAcrossArbitraryCode() {
        var closes = 0
        val workerFailure = AtomicReference<Throwable?>()
        lateinit var owner: AgentChildToolOwnership
        owner = AgentChildToolOwnership {
            closes++
            val worker = thread(isDaemon = true) {
                try {
                    assertNull(owner.retain())
                    owner.release()
                } catch (failure: Throwable) { workerFailure.set(failure) }
            }
            worker.join(2000)
            assertFalse("cleanup must not block another thread on the owner monitor", worker.isAlive)
            workerFailure.get()?.let { throw it }
        }
        val child = requireNotNull(owner.retain())
        owner.release()
        child.close()
        assertEquals(1, closes)
    }

    @Test fun throwingCleanupIsClaimedOnceAndCannotResurrectOwner() {
        var closes = 0
        val owner = AgentChildToolOwnership { closes++; error("cleanup failed") }
        val child = requireNotNull(owner.retain())
        owner.release()
        assertEquals("cleanup failed", runCatching { child.close() }.exceptionOrNull()?.message)
        owner.release()
        child.close()
        assertEquals(1, closes)
        assertNull(owner.retain())
    }

    @Test fun cleanupCanReenterReleaseWithoutDeadlockOrDoubleClose() {
        var closes = 0
        lateinit var owner: AgentChildToolOwnership
        owner = AgentChildToolOwnership {
            closes++
            assertNull(owner.retain())
            owner.release()
        }
        val child = requireNotNull(owner.retain())
        owner.release()
        child.close()
        assertEquals(1, closes)
    }
}
