package io.github.mangi.eta.agent.model

import java.io.IOException
import java.util.concurrent.TimeUnit
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class SseLineEndingSourceTest {
    @Test fun crlfIsOneLfAcrossEveryByteBoundary() {
        val body = "id: local\r\nevent: chunk\r\ndata: one\r\ndata: two\r\n\r\n"
        for (chunkSize in listOf(1, 2, 7, 8192)) for (requestSize in listOf(1L, 3L, 8192L)) {
            assertEquals(body.replace("\r\n", "\n"), normalize(body, chunkSize, requestSize))
        }
    }

    @Test fun crOnlyPreservesFieldsCommentsAndMultilineData() {
        val body = ": heartbeat\r\rid: local\revent: chunk\rdata: one\rdata: two\r\r"
        assertEquals(": heartbeat\n\nid: local\nevent: chunk\ndata: one\ndata: two\n\n",
            normalize(body, 1, 1L))
    }

    @Test fun lfOnlyIsUnchanged() {
        val body = ": heartbeat\n\nid: local\nevent: chunk\ndata: one\ndata: two\n\n"
        assertEquals(body, normalize(body, 1, 8192L))
    }

    @Test fun consecutiveCrRemainSeparateLineEndings() {
        assertEquals("a\n\nb\n\n\nc\n\n", normalize("a\r\rb\r\n\r\rc\n\r\n", 1, 2L))
    }

    @Test fun zeroByteReadDoesNotTouchUpstreamSinkOrPendingCrState() {
        val upstream = ChunkedSource("\r\nx".toByteArray(), 1)
        val source = SseLineEndingSource(upstream)
        val sink = Buffer().writeUtf8("prefix")
        assertEquals(0L, source.read(sink, 0L))
        assertEquals(0, upstream.reads)
        assertEquals("prefix", sink.readUtf8())
        assertEquals(1L, source.read(sink, 1L))
        assertEquals("\n", sink.readUtf8())
        assertEquals(0L, source.read(sink, 0L))
        assertEquals(1, upstream.reads)
        assertEquals(0L, sink.size)
        assertEquals(1L, source.read(sink, 1L))
        assertEquals("x", sink.readUtf8())
    }

    @Test fun suppressedLfNeverReturnsZeroForPositiveReads() {
        val source = SseLineEndingSource(ChunkedSource("\r\nx\r\n".toByteArray(), 1))
        val sink = Buffer()
        assertEquals(1L, source.read(sink, 1L))
        assertEquals(1L, source.read(sink, 1L)) // reads past the suppressed LF to x
        assertEquals(1L, source.read(sink, 1L))
        assertEquals(-1L, source.read(sink, 1L)) // suppressed final LF followed by EOF
        assertEquals("\nx\n", sink.readUtf8())
    }

    @Test fun shortCrTerminatedSseDoesNotWaitForAnotherByteOrEof() {
        val body = "id: local\revent: chunk\rdata: one\rdata: two\r\r"
        val upstream = ChunkedSource(body.toByteArray(), 8192, failAfterBytes = true)
        val source = SseLineEndingSource(upstream)
        val sink = Buffer()
        assertEquals(body.length.toLong(), source.read(sink, 8192L))
        assertEquals(body.replace('\r', '\n'), sink.readUtf8())
        assertEquals(1, upstream.reads) // a second read would throw, as if the stream stayed open
    }

    @Test fun eofDoesNotInventLineEndings() {
        for ((body, expected) in listOf(
            "" to "",
            "data: partial" to "data: partial",
            "data: one\r" to "data: one\n",
            "data: one\r\n" to "data: one\n",
            "data: one\r\r" to "data: one\n\n",
        )) assertEquals(expected, normalize(body, 1, 8192L))
    }

    @Test fun utf8AndAllOtherBytesArePreservedWithoutDecoding() {
        val body = "id: 本地\revent: 更新\r\ndata: 你好🙂é\rdata: 끝\n\n"
        for (chunkSize in listOf(1, 2, 7)) {
            assertArrayEquals(body.replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8),
                normalizeBytes(body.toByteArray(Charsets.UTF_8), chunkSize, 1L))
        }
        val bytes = (0..255).filter { it != 0x0a && it != 0x0d }.map { it.toByte() }.toByteArray()
        assertArrayEquals(bytes, normalizeBytes(bytes, 1, 7L))
    }

    @Test fun hugeReadRequestStillUsesBoundedChunksWithoutWaitingForALine() {
        val bytes = "x".repeat(128 * 1024).toByteArray()
        val upstream = ChunkedSource(bytes, Int.MAX_VALUE)
        val source = SseLineEndingSource(upstream)
        val sink = Buffer()
        while (true) {
            val read = source.read(sink, Long.MAX_VALUE)
            if (read == -1L) break
            assertTrue(read in 1L..8192L)
        }
        assertEquals(8192L, upstream.maximumRequested)
        assertArrayEquals(bytes, sink.readByteArray())
    }

    @Test fun timeoutCloseAndTransportFailureAreDelegated() {
        val failure = IOException("cancelled transport")
        val timeout = Timeout()
        var closed = false
        val upstream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = timeout
            override fun close() { closed = true }
        }
        val source = SseLineEndingSource(upstream)
        assertSame(timeout, source.timeout())
        source.timeout().timeout(42, TimeUnit.MILLISECONDS)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(42), timeout.timeoutNanos())
        assertSame(failure, assertThrows(IOException::class.java) { source.read(Buffer(), 1L) })
        source.close()
        assertTrue(closed)
    }

    @Test fun negativeByteCountFailsWithoutReading() {
        val upstream = ChunkedSource("data: one\r\r".toByteArray(), 1)
        val source = SseLineEndingSource(upstream)
        assertThrows(IllegalArgumentException::class.java) { source.read(Buffer(), -1L) }
        assertEquals(0, upstream.reads)
    }

    private fun normalize(body: String, chunkSize: Int, requestSize: Long): String =
        String(normalizeBytes(body.toByteArray(Charsets.UTF_8), chunkSize, requestSize), Charsets.UTF_8)

    private fun normalizeBytes(bytes: ByteArray, chunkSize: Int, requestSize: Long): ByteArray {
        val sink = Buffer()
        SseLineEndingSource(ChunkedSource(bytes, chunkSize)).use { source ->
            while (true) {
                val before = sink.size
                val read = source.read(sink, requestSize)
                if (read == -1L) {
                    assertEquals(before, sink.size)
                    break
                }
                assertTrue(read in 1L..requestSize)
                assertEquals(read, sink.size - before)
            }
        }
        return sink.readByteArray()
    }

    private class ChunkedSource(
        private val bytes: ByteArray,
        private val chunkSize: Int,
        private val failAfterBytes: Boolean = false,
    ) : Source {
        private var position = 0
        var reads = 0
            private set
        var maximumRequested = 0L
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0L
            reads++
            maximumRequested = maxOf(maximumRequested, byteCount)
            if (position == bytes.size) {
                check(!failAfterBytes) { "read past the available bytes of an open stream" }
                return -1L
            }
            val count = minOf(byteCount, chunkSize.toLong(), (bytes.size - position).toLong()).toInt()
            sink.write(bytes, position, count)
            position += count
            return count.toLong()
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }
}
