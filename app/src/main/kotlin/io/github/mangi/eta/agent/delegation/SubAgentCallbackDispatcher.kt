package io.github.mangi.eta.agent.delegation

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Bounded coalescing delivery. External callbacks never run under coordinator/task monitors. */
internal class SubAgentCallbackDispatcher : AutoCloseable {
    private val lock = Any()
    private val pending = linkedMapOf<String, () -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "subagent-events").apply { isDaemon = true }
    }
    private var scheduled = false
    private var closed = false

    fun post(key: String, callback: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            pending.remove(key)
            pending[key] = callback
            while (pending.size > 256) {
                val eldest = pending.keys.firstOrNull { it != "changed" } ?: break
                pending.remove(eldest)
            }
            if (scheduled) return
            scheduled = true
            try { executor.execute(::drain) }
            catch (_: RejectedExecutionException) { pending.clear(); scheduled = false }
        }
    }

    private fun drain() {
        while (true) {
            val callback = synchronized(lock) {
                val key = pending.keys.firstOrNull()
                if (closed || key == null) { scheduled = false; return }
                pending.remove(key)
            }
            runCatching { callback?.invoke() }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; pending.clear() }
        executor.shutdown()
    }
}
