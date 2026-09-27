package io.github.mangi.eta.ui.app

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serial saves with at most one in-flight payload and one pending payload.
 *
 * [merge] replaces the pending snapshot, not the in-flight snapshot. It must be a quick,
 * non-reentrant operation which retains the newer conversation and combines any usage deltas.
 * Only request receipts/callbacks grow with the number of submissions; old payloads are not
 * retained by the queue. Callbacks should likewise avoid capturing whole snapshots.
 *
 * A receipt becomes true only after its covering write succeeds and its callback has run.
 * False/throwing writes fail that batch without preventing later writes. Scope/worker
 * cancellation fails requests whose success callback has not started with false. A started
 * success callback completes true even if that callback cancels its owner. Cancelling a receipt does
 * not cancel the shared write or suppress its success callback.
 *
 * The worker lives until [scope] is cancelled (use an application-owned scope, not a temporary
 * coroutineScope). It never uses LAZY startup or retires on an empty queue, so submissions
 * cannot race worker startup/retirement and lose a wakeup.
 */
internal class LatestConversationSaveQueue<T>(
    private val scope: CoroutineScope,
    private val merge: (older: T, newer: T) -> T,
    private val write: suspend (T) -> Boolean,
) {
    private class Request(val onSaved: (() -> Unit)?) {
        // Not parented to the worker: even cancellation must settle the receipt explicitly.
        val result = CompletableDeferred<Boolean>()
    }

    private class Payload<T>(val value: T)
    private class Batch<T>(value: T, val requests: MutableList<Request>) {
        var payload: Payload<T>? = Payload(value)
        fun release() { payload = null; requests.clear() }
    }

    private val lock = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var pending: Batch<T>? = null
    private var closed = false

    init {
        val worker = scope.launch {
            for (ignored in wakeups) {
                val batch = synchronized(lock) {
                    pending.also { pending = null }
                } ?: continue
                try {
                    val saved = try {
                        write(checkNotNull(batch.payload).value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    batch.payload = null
                    // A non-cooperative writer may return after cancellation.
                    currentCoroutineContext().ensureActive()
                    finish(batch, saved, activeContext = currentCoroutineContext())
                } finally {
                    // Covers cancellation during write, and even an unexpected fatal callback.
                    batch.requests.forEach { it.result.complete(false) }
                    // Do not rely on coroutine spill-slot liveness to release a saved transcript.
                    batch.release()
                }
            }
        }
        // Unlike a finally inside launch, this also runs when the scope was already cancelled
        // and the coroutine body never starts. Registering after launch is safe even if it ends
        // synchronously: invokeOnCompletion immediately observes an already completed job.
        worker.invokeOnCompletion {
            val abandoned = synchronized(lock) {
                closed = true
                pending.also { pending = null }
            }
            wakeups.close()
            abandoned?.let { try { finish(it, false) } finally { it.release() } }
        }
    }

    fun submit(value: T, onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        val request = Request(onSaved)
        val accepted = synchronized(lock) {
            if (closed || !scope.isActive) {
                false
            } else {
                val batch = pending
                if (batch == null) {
                    pending = Batch(value, mutableListOf(request))
                    true
                } else {
                    try {
                        batch.payload = Payload(merge(checkNotNull(batch.payload).value, value))
                        batch.requests.add(request)
                        true
                    } catch (_: Exception) {
                        // A bad merge must not strand the incoming receipt or replace the
                        // existing batch. merge should not mutate its arguments before failing.
                        false
                    }
                }
            }
        }
        if (accepted) {
            // Payloads live under lock, never in the channel. A stale/conflated wakeup is fine:
            // the worker takes whichever pending batch exists when it consumes the signal.
            wakeups.trySend(Unit)
        } else {
            request.result.complete(false)
        }
        return request.result
    }

    private fun finish(batch: Batch<T>, saved: Boolean, activeContext: CoroutineContext? = null) {
        for (request in batch.requests) {
            // This guard is outside the completion finally: a cancelled request is false.
            if (saved) activeContext?.ensureActive()
            try {
                if (saved) {
                    try {
                        request.onSaved?.invoke()
                    } catch (_: Exception) {
                        // A callback is not the write. Its failure cannot undo a successful
                        // commit or prevent the other requests from observing that commit.
                    }
                }
            } finally {
                request.result.complete(saved)
            }
        }
    }
}
