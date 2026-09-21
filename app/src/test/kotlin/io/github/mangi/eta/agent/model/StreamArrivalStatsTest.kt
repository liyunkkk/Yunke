package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class StreamArrivalStatsTest {
    @Test fun recordsFirstGapAndCallbackWithoutLoggingEachToken() {
        val stats = StreamArrivalStats(0)
        stats.arrival(1_000_000_000, 3)
        stats.callback(2_000_000)
        assertNull(stats.report(1_001_000_000))
        stats.arrival(5_500_000_000, 8)
        val report = stats.report(5_500_000_000)!!
        assertTrue(report.contains("events=2 chars=11 firstMs=1000"))
        assertTrue(report.contains("maxGapMs=4500 maxCallbackMs=2"))
        assertNull(stats.report(5_501_000_000))
        assertTrue(stats.report(5_501_000_000, final = true)!!.contains("final=true"))
    }

    @Test fun emptyOrFailedRequestStillHasFinalSummary() {
        assertTrue(StreamArrivalStats(10).report(20, true)!!.contains("events=0 chars=0 firstMs=-1"))
    }

    @Test fun countersStayBoundedWithManyArrivals() {
        val stats = StreamArrivalStats(0)
        repeat(100_000) { stats.arrival(it.toLong(), 1) }
        val report = stats.report(100_000, true)!!
        assertTrue(report.contains("events=100000 chars=100000"))
        assertTrue(report.length < 200)
    }
}
