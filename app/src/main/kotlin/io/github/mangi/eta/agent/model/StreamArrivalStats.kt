package io.github.mangi.eta.agent.model

/** Request-local counters only: no payload, URL, headers, or identifiers from the provider. */
internal class StreamArrivalStats(private val startedNs: Long) {
    private var count = 0L
    private var chars = 0L
    private var firstNs = -1L
    private var lastNs = -1L
    private var maxGapNs = 0L
    private var maxCallbackNs = 0L
    private var reportedNs = startedNs

    @Synchronized fun arrival(nowNs: Long, length: Int) {
        if (count == 0L) firstNs = nowNs
        if (lastNs >= 0L) maxGapNs = maxOf(maxGapNs, (nowNs - lastNs).coerceAtLeast(0))
        lastNs = nowNs
        count++
        chars += length.coerceAtLeast(0)
    }

    @Synchronized fun callback(durationNs: Long) {
        maxCallbackNs = maxOf(maxCallbackNs, durationNs.coerceAtLeast(0))
    }

    @Synchronized fun report(nowNs: Long, final: Boolean = false): String? {
        if (!final && nowNs - reportedNs < 5_000_000_000L) return null
        reportedNs = nowNs
        return "final=$final events=$count chars=$chars firstMs=${if (firstNs < 0) -1 else (firstNs-startedNs).coerceAtLeast(0)/1_000_000} " +
            "maxGapMs=${maxGapNs/1_000_000} maxCallbackMs=${maxCallbackNs/1_000_000}"
    }
}
