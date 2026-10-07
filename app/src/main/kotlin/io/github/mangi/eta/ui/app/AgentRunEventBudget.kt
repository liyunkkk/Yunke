package io.github.mangi.eta.ui.app

import java.util.ArrayDeque

/**
 * FIFO event buffer with a cooperative time budget.
 *
 * Events are removed only after the consumer returns successfully. A budget
 * never drops or reorders events; it only decides when the caller should yield
 * back to the main dispatcher and continue on a later turn.
 */
internal class AgentRunEventBudget<T>(
    private val budgetNs: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val pending = ArrayDeque<T>()

    val isEmpty: Boolean get() = pending.isEmpty()
    val size: Int get() = pending.size

    fun offer(value: T) {
        pending.addLast(value)
    }

    fun drain(
        force: Boolean,
        frameBudget: AgentFrameEventBudget? = null,
        maxEvents: Int = Int.MAX_VALUE,
        consume: (T) -> Unit,
    ): Int {
        if (pending.isEmpty()) return 0
        val started = clock()
        var consumed = 0
        while (pending.isNotEmpty() && consumed < maxEvents) {
            if (!force && frameBudget?.exhausted == true) break
            val value = pending.peekFirst()
            val eventStarted = clock()
            try {
                consume(value)
            } finally {
                frameBudget?.record((clock() - eventStarted).coerceAtLeast(0L))
            }
            pending.removeFirst()
            consumed++
            if (!force && (frameBudget?.exhausted ?: (clock() - started >= budgetNs))) break
        }
        return consumed
    }
}

/** Shared main-thread budget. Only a new Choreographer frame grants more work. */
internal class AgentFrameEventBudget(
    private val budgetNs: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private var frameTimeNs: Long? = null
    private var spentNs = 0L
    private var workDepth = 0
    private var workStartedNs = 0L
    val exhausted: Boolean get() = spentNs >= budgetNs ||
        (workDepth > 0 && (clock() - workStartedNs).coerceAtLeast(0L) >= budgetNs - spentNs)

    /** Charge scheduling/queue bookkeeping too, but neither idle gaps nor nested work twice. */
    fun <R> measureWork(block: () -> R): R {
        val outermost = workDepth == 0
        if (outermost) workStartedNs = clock()
        workDepth++
        try {
            return block()
        } finally {
            workDepth--
            if (outermost) record((clock() - workStartedNs).coerceAtLeast(0L))
        }
    }

    fun beginFrame(timeNs: Long) {
        if (frameTimeNs == timeNs) return
        frameTimeNs = timeNs
        spentNs = 0L
    }

    fun record(durationNs: Long) {
        if (workDepth > 0) return // The enclosing wall-time scope already includes this work.
        val cost = durationNs.coerceAtLeast(0L)
        spentNs = if (cost >= budgetNs - spentNs.coerceAtMost(budgetNs)) budgetNs else spentNs + cost
    }
}
