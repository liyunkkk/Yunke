package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusProtocolTest {
    @Test fun outputCollectorBoundsStorageWhileDrainingInput() {
        val collector = ByteArrayOutputCollector()
        val source = "0123456789".toByteArray()

        collector.readFrom(ByteArrayInputStream(source), maxBytes = 4)

        assertArrayEquals(source.copyOf(4), collector.bytes())
        assertEquals(source.size.toLong(), collector.totalBytesRead())
        assertTrue(collector.isTruncated())
    }
}
