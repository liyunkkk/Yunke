package io.github.mangi.eta.ui.app

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class LatestConversationSaveQueueTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(15)

    private data class Snapshot(val conversation: Int, val usage: Int)

    @Test
    fun blockedFirstWriteKeepsOnlyLatestPendingPayloadAndAllReceipts() = runBlocking<Unit> {
        withSaveScope {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val writes = mutableListOf<Snapshot>()
            val callbacks = mutableListOf<Int>()
            var merges = 0
            val queue = LatestConversationSaveQueue<Snapshot>(
                scope = this,
                merge = { older, newer ->
                    merges++
                    newer.copy(usage = older.usage + newer.usage)
                },
                write = { snapshot ->
                    writes += snapshot
                    if (writes.size == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    } else {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                    }
                    true
                },
            )
            val first = queue.submit(Snapshot(0, 7)) { callbacks += 0 }
            withTimeout(2_000) { firstStarted.await() }
            val pending = (1..1_000).map { index ->
                queue.submit(Snapshot(index, index)) { callbacks += index }
            }
            assertEquals(listOf(Snapshot(0, 7)), writes)
            assertEquals(999, merges)
            assertFalse(first.isCompleted)
            assertTrue(pending.none { it.isCompleted })
            assertTrue(callbacks.isEmpty())

            releaseFirst.complete(Unit)
            withTimeout(2_000) { secondStarted.await() }
            assertTrue(first.await())
            assertEquals(listOf(0), callbacks)
            assertTrue(pending.none { it.isCompleted })
            assertEquals(listOf(Snapshot(0, 7), Snapshot(1_000, 500_500)), writes)

            releaseSecond.complete(Unit)
            assertTrue(withTimeout(2_000) { pending.awaitAll() }.all { it })
            assertEquals((0..1_000).toList(), callbacks)
            yield()
            assertEquals(2, writes.size)
        }
    }

    @Test
    fun failedBatchFailsEveryCoveredRequestButLaterWritesStillSucceed() = runBlocking<Unit> {
        // Both a false return and an ordinary exception must fail only their own batch.
        for (throws in listOf(false, true)) {
            withSaveScope {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val writes = mutableListOf<Int>()
                val callbacks = mutableListOf<Int>()
                val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                    writes += it
                    when (it) {
                        0 -> {
                            started.complete(Unit)
                            release.await()
                            true
                        }
                        3 -> if (throws) throw IOException("disk unavailable") else false
                        else -> true
                    }
                }
                val first = queue.submit(0) { callbacks += 0 }
                withTimeout(2_000) { started.await() }
                val failed = (1..3).map { value -> queue.submit(value) { callbacks += value } }
                release.complete(Unit)
                assertTrue(withTimeout(2_000) { first.await() })
                assertEquals(listOf(false, false, false), withTimeout(2_000) { failed.awaitAll() })
                assertEquals(listOf(0), callbacks)
                assertTrue(withTimeout(2_000) { queue.submit(4) { callbacks += 4 }.await() })
                assertEquals(listOf(0, 3, 4), writes)
                assertEquals(listOf(0, 4), callbacks)
            }
        }
    }

    @Test
    fun failureOfInFlightWriteDoesNotFailThePendingBatch() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = mutableListOf<Int>()
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                if (it == 0) {
                    started.complete(Unit)
                    release.await()
                    false
                } else {
                    true
                }
            }
            val first = queue.submit(0) { callbacks += 0 }
            withTimeout(2_000) { started.await() }
            val second = queue.submit(1) { callbacks += 1 }
            release.complete(Unit)
            assertFalse(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { second.await() })
            assertEquals(listOf(1), callbacks)
        }
    }

    @Test
    fun throwingSuccessCallbackDoesNotChangeReceiptsOrSkipOtherCallbacks() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = mutableListOf<Int>()
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                if (it == 0) {
                    started.complete(Unit)
                    release.await()
                }
                true
            }
            val first = queue.submit(0)
            withTimeout(2_000) { started.await() }
            val second = queue.submit(1) {
                callbacks += 1
                throw IllegalStateException("broken observer")
            }
            val third = queue.submit(2) { callbacks += 2 }
            release.complete(Unit)
            assertEquals(listOf(true, true, true), withTimeout(2_000) {
                listOf(first, second, third).awaitAll()
            })
            assertEquals(listOf(1, 2), callbacks)
            assertTrue(withTimeout(2_000) { queue.submit(3).await() })
        }
    }

    @Test
    fun scopeCancellationSettlesInFlightAndPendingAndRejectsLaterSubmissions() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            var writes = 0
            var callbacks = 0
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                writes++
                started.complete(Unit)
                neverReleased.await()
                true
            }
            val first = queue.submit(0) { callbacks++ }
            withTimeout(2_000) { started.await() }
            val pending = (1..20).map { queue.submit(it) { callbacks++ } }
            cancel()
            assertFalse(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { pending.awaitAll() }.none { it })
            assertFalse(withTimeout(2_000) { queue.submit(21) { callbacks++ }.await() })
            assertEquals(1, writes)
            assertEquals(0, callbacks)
        }
    }

    @Test
    fun cancellationThrownByWriteClosesQueueEvenIfOwnerIsStillActive() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var callbacks = 0
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                started.complete(Unit)
                release.await()
                throw CancellationException("write cancelled")
            }
            val first = queue.submit(0) { callbacks++ }
            withTimeout(2_000) { started.await() }
            val pending = queue.submit(1) { callbacks++ }
            release.complete(Unit)
            assertFalse(withTimeout(2_000) { first.await() })
            assertFalse(withTimeout(2_000) { pending.await() })
            assertTrue(coroutineContext[Job]!!.isActive)
            assertFalse(withTimeout(2_000) { queue.submit(2).await() })
            assertEquals(0, callbacks)
        }
    }

    @Test
    fun alreadyCancelledScopeNeverStartsAWriteOrLeavesAReceiptPending() = runBlocking<Unit> {
        val dispatcher = PausedDispatcher()
        val job = Job().apply { cancel() }
        val scope = CoroutineScope(dispatcher + job)
        var writes = 0
        var callbacks = 0
        val queue = LatestConversationSaveQueue<Int>(scope, { _, newer -> newer }) {
            writes++
            true
        }
        val result = queue.submit(1) { callbacks++ }
        assertTrue(result.isCompleted)
        assertFalse(result.await())
        dispatcher.runAll()
        assertEquals(0, writes)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationBeforeDispatchedWorkerStartsStillSettlesAcceptedRequests() = runBlocking<Unit> {
        val dispatcher = PausedDispatcher()
        val job = Job()
        val scope = CoroutineScope(dispatcher + job)
        var writes = 0
        var callbacks = 0
        try {
            val queue = LatestConversationSaveQueue<Int>(scope, { _, newer -> newer }) {
                writes++
                true
            }
            val results = (1..10).map { queue.submit(it) { callbacks++ } }
            assertTrue(results.none { it.isCompleted })
            scope.cancel()
            dispatcher.runAll()
            assertTrue(results.all { it.isCompleted })
            assertTrue(withTimeout(2_000) { results.awaitAll() }.none { it })
            assertEquals(0, writes)
            assertEquals(0, callbacks)
        } finally {
            scope.cancel()
            dispatcher.runAll()
        }
    }

    @Test
    fun cancellingOneReceiptDoesNotCancelSharedSaveOrLoseItsCallback() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val callbacks = mutableListOf<Int>()
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                writes += it
                if (it == 0) {
                    started.complete(Unit)
                    release.await()
                }
                true
            }
            val first = queue.submit(0)
            withTimeout(2_000) { started.await() }
            val cancelled = queue.submit(1) { callbacks += 1 }
            val surviving = queue.submit(2) { callbacks += 2 }
            cancelled.cancel()
            release.complete(Unit)
            assertTrue(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { surviving.await() })
            assertTrue(cancelled.isCancelled)
            assertEquals(listOf(0, 2), writes)
            assertEquals(listOf(1, 2), callbacks)
        }
    }

    @Test
    fun successCallbackCanSubmitAgainAndIdleWorkerNeverLosesWakeups() = runBlocking<Unit> {
        withSaveScope {
            val writes = mutableListOf<Int>()
            val callbacks = mutableListOf<Int>()
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                writes += it
                true
            }
            var fromCallback: Deferred<Boolean>? = null
            val first = queue.submit(0) {
                callbacks += 0
                fromCallback = queue.submit(1) { callbacks += 1 }
            }
            assertTrue(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { checkNotNull(fromCallback).await() })
            // Repeatedly submit after the worker has drained its previous batch.
            for (value in 2..200) {
                yield()
                assertTrue(withTimeout(2_000) { queue.submit(value) { callbacks += value }.await() })
            }
            assertEquals((0..200).toList(), writes)
            assertEquals((0..200).toList(), callbacks)
        }
    }

    @Test
    fun concurrentSubmittersKeepAllStatisticsAndCallbacksWithoutParallelWrites() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = AtomicInteger()
            val activeWrites = AtomicInteger()
            val writtenUsage = AtomicInteger()
            val writes = AtomicInteger()
            val writerScope = CoroutineScope(coroutineContext + Dispatchers.Default)
            val queue = LatestConversationSaveQueue<Snapshot>(
                writerScope,
                { older, newer -> newer.copy(usage = older.usage + newer.usage) },
            ) {
                assertEquals(1, activeWrites.incrementAndGet())
                try {
                    if (writes.incrementAndGet() == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    writtenUsage.addAndGet(it.usage)
                    true
                } finally {
                    activeWrites.decrementAndGet()
                }
            }
            val first = queue.submit(Snapshot(0, 1)) { callbacks.incrementAndGet() }
            withTimeout(2_000) { started.await() }
            val receipts = (1..1_000).map { value ->
                async(Dispatchers.Default) {
                    queue.submit(Snapshot(value, 1)) { callbacks.incrementAndGet() }
                }
            }.awaitAll()
            assertTrue(receipts.none { it.isCompleted })
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { (listOf(first) + receipts).awaitAll() }.all { it })
            assertEquals(2, writes.get())
            assertEquals(1_001, writtenUsage.get())
            assertEquals(1_001, callbacks.get())
            assertEquals(0, activeWrites.get())
        }
    }

    @Test
    fun throwingMergeRejectsOnlyIncomingRequestAndPreservesPendingBatch() = runBlocking<Unit> {
        withSaveScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val callbacks = mutableListOf<Int>()
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer ->
                check(newer != 2) { "bad merge" }
                newer
            }) {
                writes += it
                if (it == 0) {
                    started.complete(Unit)
                    release.await()
                }
                true
            }
            val first = queue.submit(0)
            withTimeout(2_000) { started.await() }
            val pending = queue.submit(1) { callbacks += 1 }
            val rejected = queue.submit(2) { callbacks += 2 }
            assertFalse(rejected.await())
            assertFalse(pending.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(2_000) { first.await() })
            assertTrue(withTimeout(2_000) { pending.await() })
            assertEquals(listOf(0, 1), writes)
            assertEquals(listOf(1), callbacks)
        }
    }

    @Test
    fun writerReturningSuccessAfterCancellingOwnerDoesNotAcknowledge() = runBlocking<Unit> {
        withSaveScope {
            val owner = this
            var callbacks = 0
            val queue = LatestConversationSaveQueue<Int>(owner, { _, newer -> newer }) {
                owner.cancel()
                true
            }
            assertFalse(withTimeout(2_000) { queue.submit(1) { callbacks++ }.await() })
            assertEquals(0, callbacks)
            assertFalse(queue.submit(2).await())
        }
    }

    @Test
    fun callbackCancellationLeavesRemainingBatchReceiptsFalse() = runBlocking<Unit> {
        withSaveScope {
            val owner = this
            val start = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queue = LatestConversationSaveQueue<Int>(owner, { _, newer -> newer }) {
                if (it == 0) { start.complete(Unit); release.await() }
                true
            }
            val first = queue.submit(0)
            start.await()
            val canceller = queue.submit(1) { owner.cancel() }
            var called = false
            val rest = queue.submit(2) { called = true }
            release.complete(Unit)
            assertTrue(first.await())
            assertTrue(withTimeout(2_000) { canceller.await() })
            assertFalse(withTimeout(2_000) { rest.await() })
            assertFalse(called)
        }
    }

    @Test
    fun workerSelfCancellationCannotAcknowledgeEvenWhileOwnerRemainsActive() = runBlocking<Unit> {
        withSaveScope {
            var callbacks = 0
            val queue = LatestConversationSaveQueue<Int>(this, { _, newer -> newer }) {
                currentCoroutineContext().cancel()
                true
            }
            assertFalse(withTimeout(2_000) { queue.submit(1) { callbacks++ }.await() })
            assertEquals(0, callbacks)
            assertFalse(queue.submit(2).await())
        }
    }

    // The project uses runBlocking/CompletableDeferred gates, without coroutines-test.
    // A separate child Job lets tests cancel the save scope without cancelling the assertions.
    private suspend fun withSaveScope(block: suspend CoroutineScope.() -> Unit) = coroutineScope {
        val job = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        try {
            scope.block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
