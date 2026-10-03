package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeStopWorkerTest {
    @Test fun stopReturnsWithoutWaitingForCleanupAndRunsOffCallerThread() {
        val executor = Executors.newCachedThreadPool()
        val worker = AgentRuntimeStopWorker(executor) { throw it }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        try {
            assertTrue(worker.submit(Any()) {
                callbackThread.set(Thread.currentThread())
                entered.countDown()
                release.await()
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), callbackThread.get())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun repeatedStopIsCoalescedButAnotherCapturedSessionIsNotBlocked() {
        val executor = Executors.newCachedThreadPool()
        val worker = AgentRuntimeStopWorker(executor) { throw it }
        val first = Any()
        val second = Any()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStopped = CountDownLatch(1)
        val duplicates = AtomicInteger()
        try {
            assertTrue(worker.submit(first) { firstEntered.countDown(); releaseFirst.await() })
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.submit(first) { duplicates.incrementAndGet() })
            assertTrue(worker.submit(second) { secondStopped.countDown() })
            assertTrue(secondStopped.await(2, TimeUnit.SECONDS))
            assertEquals(0, duplicates.get())
        } finally {
            releaseFirst.countDown()
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun serviceDestructionDrainsAcceptedCallbacksAndRejectsNewWork() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        executor.execute { release.await() }
        val stopped = AtomicInteger()
        val teardown = AtomicInteger()
        val failures = AtomicInteger()
        val worker = AgentRuntimeStopWorker(executor) { failures.incrementAndGet() }
        try {
            assertTrue(worker.submit(Any()) { stopped.incrementAndGet() })
            assertTrue(worker.submit(Any()) { throw IllegalStateException("cleanup failed") })
            worker.close(listOf({ teardown.incrementAndGet(); Unit }))
            worker.close(listOf({ teardown.incrementAndGet(); Unit }))
            assertFalse(worker.submit(Any()) { stopped.incrementAndGet() })
            assertEquals(0, stopped.get())
        } finally {
            release.countDown()
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, stopped.get())
        assertEquals(1, teardown.get())
        assertEquals(1, failures.get())
    }

    @Test fun equalLookingSessionsAreStillDifferentOwners() {
        data class Owner(val runId: String)
        val executor = Executors.newCachedThreadPool()
        val worker = AgentRuntimeStopWorker(executor) { throw it }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        try {
            val old = Owner("same-run-id")
            val replacement = Owner("same-run-id")
            assertTrue(worker.submit(old) { entered.countDown(); release.await() })
            assertTrue(worker.submit(replacement) { entered.countDown(); release.await() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
