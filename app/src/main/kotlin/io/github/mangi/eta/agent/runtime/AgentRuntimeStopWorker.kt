package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Stop callbacks may close a browser, cancel sockets or wait on a child coordinator. They must
 * never run on Android's main looper (which also delivers results and runs the UI watchdog).
 * Each captured session gets at most one pending callback. A slow session cannot hold another
 * session's cancellation behind it, and service destruction drains rather than interrupts work.
 */
internal class AgentRuntimeStopWorker(
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "eta-runtime-stop").apply { isDaemon = true }
    },
    private val onFailure: (Exception) -> Unit,
) {
    private val lock = Any()
    private val pending = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var closed = false

    fun submit(owner: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (closed || !pending.add(owner)) return@synchronized false
        try {
            executor.execute {
                try { safely(action) }
                finally { synchronized(lock) { pending.remove(owner) } }
            }
            true
        } catch (failure: Exception) {
            pending.remove(owner)
            report(failure)
            false
        }
    }

    /** Captured teardown callbacks run even if earlier stop work is still blocked. */
    fun close(teardown: List<() -> Unit> = emptyList()) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        try {
            teardown.forEach { action ->
                try { executor.execute { safely(action) } }
                catch (failure: Exception) { report(failure) }
            }
        } finally {
            // Never shutdownNow: accepted cancellation must outlive the service's lifecycle.
            executor.shutdown()
        }
    }

    private fun safely(action: () -> Unit) {
        try { action() }
        catch (failure: Exception) { report(failure) }
    }

    private fun report(failure: Exception) {
        runCatching { onFailure(failure) }
    }
}
