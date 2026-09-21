package io.github.mangi.eta.agent.runtime

/** Shared monotonic device clock; missing/invalid old-version stamps are ignored. */
internal object StreamDeliveryTiming {
    const val KEY = "stream_delta_sent_ns"
    fun delayNs(sentNs: Long, receivedNs: Long, live: Boolean): Long? =
        if (live && sentNs > 0 && receivedNs >= sentNs) receivedNs - sentNs else null
}
