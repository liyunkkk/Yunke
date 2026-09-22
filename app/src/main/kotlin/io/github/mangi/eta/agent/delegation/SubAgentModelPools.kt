package io.github.mangi.eta.agent.delegation

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared across child identities/parent runs. Idle threads expire; live cleanup retains its slot. */
internal object SubAgentModelPools {
    private data class Entry(val executor: ThreadPoolExecutor, var limit: Int, var references: Int)
    private val entries = mutableMapOf<String, Entry>()
    data class Lease(val key: String, val executor: ExecutorService, val limit: Int)
    @Synchronized fun acquire(key: String, limit: Int): Lease {
        require(limit >= 0)
        val old = entries[key]
        if (old != null && old.references == 0 && old.executor.activeCount == 0 && old.executor.queue.isEmpty()) {
            old.executor.shutdown()
            entries.remove(key)
        }
        val entry = entries.getOrPut(key) {
            val cap = if (limit == 0) Int.MAX_VALUE else limit
            val executor = ThreadPoolExecutor(cap, cap, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
            executor.allowCoreThreadTimeOut(true)
            Entry(executor, limit, 0)
        }
        resize(entry, limit)
        entry.references++
        return Lease(key, entry.executor, entry.limit)
    }
    @Synchronized fun configure(key: String, limit: Int) {
        require(limit >= 0)
        entries[key]?.let { resize(it, limit) }
    }
    @Synchronized fun currentLimit(lease: Lease): Int = entries[lease.key]?.limit ?: lease.limit
    @Synchronized fun diagnostics(lease: Lease): Map<String,Number> {
        val entry=entries[lease.key] ?: return emptyMap()
        return mapOf("active" to entry.executor.activeCount,"queued" to entry.executor.queue.size,"limit" to entry.limit)
    }
    private fun resize(entry: Entry, limit: Int) {
        if (entry.limit == limit) return
        val cap = if (limit == 0) Int.MAX_VALUE else limit
        val pool = entry.executor
        if (cap > pool.maximumPoolSize) { pool.maximumPoolSize = cap; pool.corePoolSize = cap }
        else { pool.corePoolSize = cap; pool.maximumPoolSize = cap }
        entry.limit = limit
    }
    @Synchronized fun release(lease: Lease) {
        val entry = entries[lease.key] ?: return
        if (entry.executor !== lease.executor || entry.references == 0) return
        entry.references--
        entry.executor.purge()
        if (entry.references == 0 && entry.executor.activeCount == 0 && entry.executor.queue.isEmpty()) {
            entry.executor.shutdown()
            entries.remove(lease.key)
        }
    }
}
