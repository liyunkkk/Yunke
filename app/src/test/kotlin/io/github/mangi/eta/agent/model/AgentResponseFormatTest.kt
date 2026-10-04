package io.github.mangi.eta.agent.model

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class AgentResponseFormatTest {
    @Test fun jsonEvidenceOverridesSseHeaderWithoutLookingInsideStrings() {
        val body = "\uFEFF \t\r\n{\"content\":\"data: not SSE\"}"
        val source = Buffer().writeUtf8(body)
        val result = AgentResponseFormat.inspect(source, "text/event-stream; charset=utf-8")
        assertEquals(AgentResponseFormat.Kind.JSON, result.kind)
        assertEquals(7L, result.preambleBytes)
        assertEquals(body, source.readUtf8()) // peek did not consume anything
    }

    @Test fun exactSseFieldsAndHeartbeatOverrideJsonOrAbsentHeader() {
        for (prefix in listOf("data:", "event:", "id:", "retry:", ": heartbeat", "data\r")) {
            for (mime in listOf("application/json", null)) {
                assertEquals(prefix, AgentResponseFormat.Kind.SSE,
                    AgentResponseFormat.inspect(Buffer().writeUtf8(prefix + "\n\n"), mime).kind)
            }
        }
    }

    @Test fun arbitraryTextAndSimilarFieldNamesAreNotSseEvenWithSseMime() {
        for (body in listOf("error with data: inside", "metadata: wrong", "database: wrong", "<html>data: error</html>", "data = wrong")) {
            assertEquals(body, AgentResponseFormat.Kind.UNKNOWN,
                AgentResponseFormat.inspect(Buffer().writeUtf8(body), "text/event-stream").kind)
        }
    }

    @Test fun decisivePrefixDoesNotWaitForAFixedLargeBufferOrAFrame() {
        for ((body, kind, expectedReads) in listOf(
            Triple("{", AgentResponseFormat.Kind.JSON, 1),
            Triple("data:", AgentResponseFormat.Kind.SSE, 5),
            Triple(":", AgentResponseFormat.Kind.SSE, 1),
            Triple("<", AgentResponseFormat.Kind.UNKNOWN, 1),
        )) {
            val source = ByteAtATime(body)
            source.buffer().use { buffered ->
                assertEquals(kind, AgentResponseFormat.inspect(buffered, "application/json").kind)
                assertEquals(expectedReads, source.reads)
            }
        }
    }

    @Test fun undecidedWhitespaceIsStrictlyBoundedAndFallsBackOnlyToHeader() {
        val body = " ".repeat(AgentResponseFormat.MAX_PREFIX_BYTES) + "data: beyond bound\n\n"
        for ((mime, kind) in listOf(
            null to AgentResponseFormat.Kind.UNKNOWN,
            "text/event-stream" to AgentResponseFormat.Kind.SSE,
            "application/json" to AgentResponseFormat.Kind.JSON,
            "application/problem+json" to AgentResponseFormat.Kind.JSON,
        )) {
            val source = ByteAtATime(body)
            source.buffer().use { buffered ->
                val result = AgentResponseFormat.inspect(buffered, mime)
                assertEquals(kind, result.kind)
                assertEquals(AgentResponseFormat.MAX_PREFIX_BYTES.toLong(), result.preambleBytes)
                assertEquals(AgentResponseFormat.MAX_PREFIX_BYTES, source.reads)
            }
        }
    }

    @Test fun bomAndWhitespaceCanStraddleEveryRead() {
        val source = ByteAtATime("\uFEFF \r\n\tdata:")
        source.buffer().use { buffered ->
            val result = AgentResponseFormat.inspect(buffered, "application/json")
            assertEquals(AgentResponseFormat.Kind.SSE, result.kind)
            assertEquals(7L, result.preambleBytes)
            assertEquals(12, source.reads)
        }
    }

    /** Throws rather than returning EOF if sniffing reads past the supplied decisive prefix. */
    private class ByteAtATime(body: String) : Source {
        private val bytes = body.toByteArray(Charsets.UTF_8)
        var reads = 0
            private set
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0L
            check(reads < bytes.size) { "sniffer read past supplied prefix" }
            sink.writeByte(bytes[reads++].toInt())
            return 1L
        }
        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }
}
