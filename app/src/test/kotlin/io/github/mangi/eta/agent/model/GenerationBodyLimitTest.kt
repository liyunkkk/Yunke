package io.github.mangi.eta.agent.model

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class GenerationBodyLimitTest {
    @Test fun acceptsExactLimitAndEmptyOutput() {
        val bytes = ByteArray(32) { it.toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readGenerationBytes(32))
        assertTrue(ByteArrayInputStream(byteArrayOf()).readGenerationBytes(32).isEmpty())
    }
    @Test fun rejectsOversizedChunkedStreamBeforeReadingItsEntireBody() {
        var reads = 0
        val stream = object : InputStream() { override fun read(): Int { reads++; return 1 } }
        assertThrows(IllegalStateException::class.java) { stream.readGenerationBytes(32) }
        assertEquals(33, reads)
    }
}
