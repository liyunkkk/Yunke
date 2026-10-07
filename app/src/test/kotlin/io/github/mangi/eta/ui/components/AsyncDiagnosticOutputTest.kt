package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class AsyncDiagnosticOutputTest {
    @Test
    fun disablingAsyncOutputDrainsEarlierQueuedLinesBeforeSynchronousWrites() {
        val enabled = AtomicBoolean(true)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val output = AsyncDiagnosticOutput(enabled = enabled::get, sink = received::add)
        try {
            output.write("first")
            enabled.set(false)
            output.write("second")
            assertEquals(listOf("first", "second"), received.toList())
        } finally {
            assertTrue(output.close())
        }
    }

    @Test
    fun concurrentCloseWaitsForInFlightSinkAndReturnsTheSameResult() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val output = AsyncDiagnosticOutput(sink = {
            sinkEntered.countDown()
            releaseSink.await(2, TimeUnit.SECONDS)
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            output.write("blocked")
            val first = executor.submit<Boolean> { output.close() }
            assertTrue(sinkEntered.await(1, TimeUnit.SECONDS))

            val second = executor.submit<Boolean> { output.close() }
            val completedWhileSinkBlocked = try {
                second.get(100, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }
            assertFalse(completedWhileSinkBlocked)

            releaseSink.countDown()
            assertTrue(first.get(1, TimeUnit.SECONDS))
            assertTrue(second.get(1, TimeUnit.SECONDS))
        } finally {
            releaseSink.countDown()
            executor.shutdownNow()
            assertTrue(output.close())
        }
    }
}
