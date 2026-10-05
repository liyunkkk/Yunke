package io.github.mangi.eta.data.repository

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUsageFlowTest {
    private fun ledger(input: Long, output: Long = 12, cached: Long = 7, created: Long = 3): String =
        """{"conversationTotalsV1":{"owner":{"in":$input,"out":$output,"k":$cached,"w":$created}}}"""

    @Test
    fun consecutiveUnchangedInputsAreSkippedButAnOlderLedgerCanReturn() = runBlocking {
        val first = ledger(100)
        val second = ledger(200)
        val actual = conversationUsageTotalsFlow(
            flowOf(first, first, first, second, second, first), "owner",
        ).toList()
        assertEquals(listOf(
            ConversationUsageTotals(100, 12, 7, 3),
            ConversationUsageTotals(200, 12, 7, 3),
            ConversationUsageTotals(100, 12, 7, 3),
        ), actual)
    }

    @Test
    fun differentRawInputsStillEmitWhenTheSelectedTotalsAreEqual() = runBlocking {
        val first = ledger(100)
        val second = first.dropLast(1) + """, "otherMetadata":1}"""
        val actual = conversationUsageTotalsFlow(flowOf(first, second), "owner").toList()
        assertEquals(listOf(
            ConversationUsageTotals(100, 12, 7, 3),
            ConversationUsageTotals(100, 12, 7, 3),
        ), actual)
    }

    @Test
    fun malformedEmptyMissingAndBlankIdsKeepLegacyResults() = runBlocking {
        val inputs = listOf("", "broken", "[]", "{}", ledger(321),
            """{"conversationTotalsV1":{"owner":{"in":"bad","out":9}}}""")
        for (id in listOf(null, "", " ", "owner", "missing")) {
            val expected = inputs.map { conversationUsageTotals(it, id) }
            assertEquals(expected, conversationUsageTotalsFlow(inputs.asFlow(), id).toList())
        }
    }

    @Test
    fun allFourLongCountersAndMissingFieldsAreUnchanged() = runBlocking {
        val raw = ledger(8_000_000_001L, 7_000_000_002L, 6_000_000_003L, 5_000_000_004L)
        assertEquals(ConversationUsageTotals(8_000_000_001L, 7_000_000_002L,
            6_000_000_003L, 5_000_000_004L),
            conversationUsageTotalsFlow(flowOf(raw), "owner").first())
        assertEquals(ConversationUsageTotals(), conversationUsageTotalsFlow(
            flowOf("""{"conversationTotalsV1":{"owner":{}}}"""), "owner",
        ).first())
    }

    @Test
    fun defaultProducerRunsAwayFromTheCollectorAndDoesNotMoveTheCollector() = runBlocking {
        val caller = Thread.currentThread()
        val producer = AtomicReference<Thread>()
        val collector = AtomicReference<Thread>()
        val source = flow {
            producer.set(Thread.currentThread())
            emit(ledger(100))
        }
        conversationUsageTotalsFlow(source, "owner").collect { value ->
            collector.set(Thread.currentThread())
            assertEquals(ConversationUsageTotals(100, 12, 7, 3), value)
        }
        assertTrue(producer.get() != null)
        assertNotSame(caller, producer.get())
        assertSame(caller, collector.get())
    }

    @Test
    fun separateCollectorsAndConversationIdsDoNotSharePayloads() = runBlocking {
        val collections = AtomicInteger()
        val raw = """{"conversationTotalsV1":{"a":{"in":11},"b":{"in":22}}}"""
        val source = flow {
            collections.incrementAndGet()
            emit(raw)
        }
        val a = async { conversationUsageTotalsFlow(source, "a").first() }
        val b = async { conversationUsageTotalsFlow(source, "b").first() }
        assertEquals(ConversationUsageTotals(input = 11), a.await())
        assertEquals(ConversationUsageTotals(input = 22), b.await())
        assertEquals(2, collections.get())
        assertEquals(ConversationUsageTotals(input = 11),
            conversationUsageTotalsFlow(source, "a").first())
        assertEquals(3, collections.get())
    }

    @Test
    fun cancellingTheCollectorStopsItsProducer() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        val source = flow {
            try {
                emit(ledger(100))
                awaitCancellation()
            } finally {
                completed.complete(Unit)
            }
        }
        assertEquals(listOf(ConversationUsageTotals(100, 12, 7, 3)),
            conversationUsageTotalsFlow(source, "owner").take(1).toList())
        withTimeout(10_000) { completed.await() }
    }

    @Test
    fun upstreamFailuresAreNotReplacedWithFallbackTotals() = runBlocking {
        var observed: Throwable? = null
        val source = flow<String> { throw IllegalStateException("usage-source-failure") }
        try {
            conversationUsageTotalsFlow(source, "owner").toList()
        } catch (error: IllegalStateException) {
            observed = error
        }
        assertTrue(observed is IllegalStateException)
        assertEquals("usage-source-failure", observed?.message)
    }
}
