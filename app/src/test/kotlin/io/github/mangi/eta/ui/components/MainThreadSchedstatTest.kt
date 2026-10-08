package io.github.mangi.eta.ui.components

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainThreadSchedstatTest {
    private fun parse(text: String, length: Int = text.length): LongArray? =
        parseMainThreadSchedstat(text.toByteArray(Charsets.US_ASCII), length)

    @Test fun parsesCpuAndRunQueueWithoutTreatingTheThirdCounterAsWait() {
        assertArrayEquals(longArrayOf(123456789012345L, 987654321L), parse("123456789012345 987654321 42\n"))
        assertArrayEquals(longArrayOf(0L, 0L), parse("0 0 1\n"))
        assertArrayEquals(longArrayOf(23L, 45L), parse(" \t23  \t45 67\n"))
        assertArrayEquals(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE),
            parse("${Long.MAX_VALUE} ${Long.MAX_VALUE} 0\n"))
    }

    @Test fun rejectsShortMalformedNegativeAndOverflowingCounters() {
        for (text in listOf("", "1", "1 ", "1 2", "1 x 3\n", "-1 2 3\n", "1 -2 3\n",
            "1 2x 3\n", "9223372036854775808 2 3\n", "1 9223372036854775808 3\n")) {
            assertNull(text, parse(text))
        }
        assertNull(parseMainThreadSchedstat(byteArrayOf(49), -1))
        assertNull(parseMainThreadSchedstat(byteArrayOf(49), 2))
        assertNull(parse("123 456 789\n", 6)) // Second counter is truncated.
    }

    @Test fun respectsReadLengthInsteadOfReusingBytesFromThePreviousSample() {
        val bytes = "1000 2000 3\n".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(longArrayOf(1000, 2000), parseMainThreadSchedstat(bytes, bytes.size))
        assertNull(parseMainThreadSchedstat(bytes, 5))
        assertNull(parseMainThreadSchedstat(bytes, -1))
    }

    @Test fun rereadsFromZeroAndCloseDoesNotReopenTheDescriptor() {
        val source = File.createTempFile("schedstat", ".txt")
        var opens = 0
        val sampler = MainThreadSchedstat { opens++; source.absolutePath }
        try {
            source.writeText("100 20 3\n")
            assertArrayEquals(longArrayOf(100, 20), sampler.sample())
            source.writeText("200 40 5\n")
            assertArrayEquals(longArrayOf(200, 40), sampler.sample())
            assertEquals(1, opens)
            sampler.close()
            sampler.close()
            assertNull(sampler.sample())
            assertEquals(1, opens)
        } finally {
            sampler.close()
            source.delete()
        }
    }

    @Test fun failedOpenStaysUnknownWithoutRetryingEveryDispatch() {
        var attempts = 0
        val sampler = MainThreadSchedstat { attempts++; throw java.io.IOException("unavailable") }
        assertNull(sampler.sample())
        assertNull(sampler.sample())
        assertEquals(1, attempts)
    }
}
